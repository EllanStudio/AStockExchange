package dev.astock.service.market;

import com.longbridge.Config;
import com.longbridge.OpenApiException;
import com.longbridge.quote.Depth;
import com.longbridge.quote.QuoteContext;
import com.longbridge.quote.SecurityDepth;
import com.longbridge.quote.SecurityQuote;
import com.longbridge.quote.TradeStatus;
import dev.astock.domain.quote.CanonicalQuote;
import dev.astock.domain.quote.TradingStatus;
import dev.astock.service.config.AStockProperties;
import dev.astock.service.security.SecurityInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Licensed Longbridge OpenAPI adapter for mainland China, Hong Kong and United
 * States equities. Operators remain responsible for quote entitlements and
 * in-game display/redistribution rights.
 */
@Component
public class LongbridgeMarketDataProvider implements MarketDataProvider, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LongbridgeMarketDataProvider.class);
    private static final int MAX_QUOTE_BATCH = 500;
    private static final int MAX_CONCURRENT_REQUESTS = 5;
    private static final int MAX_REQUESTS_PER_SECOND = 10;

    private final AStockProperties properties;
    private final Clock clock;
    private final Object contextMonitor = new Object();
    private final Object rateMonitor = new Object();
    private final ArrayDeque<Long> requestStarts = new ArrayDeque<>();
    private final Semaphore requestSlots = new Semaphore(MAX_CONCURRENT_REQUESTS, true);
    private volatile QuoteContext context;
    private Config config;

    public LongbridgeMarketDataProvider(AStockProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "longbridge";
    }

    @Override
    public List<ProviderQuote> fetchBulk(List<SecurityInfo> securities) {
        if (securities.isEmpty()) return List.of();
        Map<String, SecurityInfo> byProviderSymbol = new HashMap<>();
        securities.forEach(security -> byProviderSymbol.put(
                toLongbridgeSymbol(security.symbol()), security
        ));

        List<String> symbols = List.copyOf(byProviderSymbol.keySet());
        List<ProviderQuote> result = new ArrayList<>(symbols.size());
        for (int start = 0; start < symbols.size(); start += MAX_QUOTE_BATCH) {
            int end = Math.min(start + MAX_QUOTE_BATCH, symbols.size());
            String[] batch = symbols.subList(start, end).toArray(String[]::new);
            SecurityQuote[] quotes = await(requestQuotes(batch), "Longbridge bulk quote");
            if (quotes == null) continue;
            for (SecurityQuote quote : quotes) {
                if (quote == null || quote.getSymbol() == null) continue;
                SecurityInfo security = byProviderSymbol.get(quote.getSymbol().toUpperCase(Locale.ROOT));
                if (security == null) {
                    security = byProviderSymbol.get(toLongbridgeSymbol(fromLongbridgeSymbol(quote.getSymbol())));
                }
                if (security != null) result.add(toProviderQuote(security, quote, null));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<ProviderQuote> fetchDetail(SecurityInfo security) {
        String symbol = toLongbridgeSymbol(security.symbol());
        CompletableFuture<SecurityQuote[]> quoteFuture = requestQuotes(new String[]{symbol});
        CompletableFuture<SecurityDepth> depthFuture = requestDepth(symbol);
        SecurityQuote[] quotes = await(quoteFuture, "Longbridge detail quote for " + security.symbol());
        if (quotes == null || quotes.length == 0 || quotes[0] == null) return Optional.empty();

        SecurityDepth depth = null;
        if (depthFuture != null) {
            try {
                depth = await(depthFuture, "Longbridge depth for " + security.symbol());
            } catch (MarketDataException exception) {
                // A quote without a book remains displayable but can never be
                // executable under QuoteQualityGate/TradingEngine.
                LOGGER.warn("Longbridge depth unavailable for {}; execution remains disabled: {}",
                        security.symbol(), exception.getMessage());
            }
        }
        return Optional.of(toProviderQuote(security, quotes[0], depth));
    }

    private CompletableFuture<SecurityQuote[]> requestQuotes(String[] symbols) {
        return startRequest(value -> value.getQuote(symbols), "Longbridge quote request was rejected");
    }

    private CompletableFuture<SecurityDepth> requestDepth(String symbol) {
        try {
            return startRequest(value -> value.getDepth(symbol), "Longbridge depth request was rejected");
        } catch (MarketDataException exception) {
            LOGGER.warn("Longbridge depth request was rejected for {}; execution remains disabled", symbol);
            return null;
        }
    }

    private <T> CompletableFuture<T> startRequest(SdkRequest<T> request, String failureMessage) {
        acquireRequestSlot();
        try {
            CompletableFuture<T> future = request.start(context());
            future.whenComplete((value, failure) -> requestSlots.release());
            return future;
        } catch (OpenApiException exception) {
            requestSlots.release();
            throw new MarketDataException(failureMessage, exception);
        } catch (RuntimeException exception) {
            requestSlots.release();
            throw exception;
        }
    }

    private void acquireRequestSlot() {
        boolean acquired = false;
        try {
            requestSlots.acquire();
            acquired = true;
            awaitRateWindow();
        } catch (InterruptedException exception) {
            if (acquired) requestSlots.release();
            Thread.currentThread().interrupt();
            throw new MarketDataException("Longbridge rate-limit wait was interrupted", exception);
        }
    }

    private void awaitRateWindow() throws InterruptedException {
        long oneSecond = TimeUnit.SECONDS.toNanos(1);
        while (true) {
            long waitNanos;
            synchronized (rateMonitor) {
                long now = System.nanoTime();
                while (!requestStarts.isEmpty() && now - requestStarts.getFirst() >= oneSecond) {
                    requestStarts.removeFirst();
                }
                if (requestStarts.size() < MAX_REQUESTS_PER_SECOND) {
                    requestStarts.addLast(now);
                    return;
                }
                waitNanos = oneSecond - (now - requestStarts.getFirst());
            }
            TimeUnit.NANOSECONDS.sleep(Math.max(1, waitNanos));
        }
    }

    private ProviderQuote toProviderQuote(SecurityInfo security, SecurityQuote quote, SecurityDepth depth) {
        Depth bid = depth == null ? null : best(depth.getBids());
        Depth ask = depth == null ? null : best(depth.getAsks());
        OffsetDateTime timestamp = quote.getTimestamp();
        long sourceTimestamp = timestamp == null ? clock.millis() : timestamp.toInstant().toEpochMilli();
        return new ProviderQuote(
                security.symbol(), security.name(), sourceTimestamp,
                positive(quote.getLastDone()), positive(quote.getPrevClose()), positive(quote.getOpen()),
                positive(quote.getHigh()), positive(quote.getLow()),
                bid == null ? null : positive(bid.getPrice()), bid == null ? 0 : bid.getVolume(),
                ask == null ? null : positive(ask.getPrice()), ask == null ? 0 : ask.getVolume(),
                Math.max(0, quote.getVolume()), wholeNumber(quote.getTurnover()),
                tradingStatus(quote.getTradeStatus()), name()
        );
    }

    private QuoteContext context() {
        QuoteContext current = context;
        if (current != null) return current;
        synchronized (contextMonitor) {
            if (context != null) return context;
            try {
                config = Config.fromApikeyEnv();
                config.disablePrintQuotePackages();
                context = QuoteContext.create(config);
                return context;
            } catch (OpenApiException | RuntimeException exception) {
                closeConfigAfterInitializationFailure();
                throw new MarketDataException(
                        "Longbridge initialization failed; configure LONGBRIDGE_APP_KEY, "
                                + "LONGBRIDGE_APP_SECRET and LONGBRIDGE_ACCESS_TOKEN",
                        exception
                );
            }
        }
    }

    private <T> T await(CompletableFuture<T> future, String operation) {
        try {
            return future.get(properties.data().requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(operation + " was interrupted", exception);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new MarketDataException(operation + " timed out", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new MarketDataException(operation + " failed", cause);
        }
    }

    private static Depth best(Depth[] levels) {
        if (levels == null) return null;
        return Arrays.stream(levels)
                .filter(level -> level != null && positive(level.getPrice()) != null && level.getVolume() > 0)
                .min(Comparator.comparingInt(Depth::getPosition))
                .orElse(null);
    }

    private static BigDecimal positive(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static long wholeNumber(BigDecimal value) {
        if (value == null || value.signum() <= 0) return 0;
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static TradingStatus tradingStatus(TradeStatus status) {
        if (status == null) return TradingStatus.UNKNOWN;
        return switch (status) {
            case Normal -> TradingStatus.TRADING;
            case Halted, Fuse, SplitStockHalts, SuspendTrade -> TradingStatus.HALTED;
            case Delisted, CodeMoved, Expired -> TradingStatus.CLOSED;
            case PrepareList, ToBeOpened, WarrantPrepareList -> TradingStatus.UNKNOWN;
        };
    }

    static String toLongbridgeSymbol(String rawSymbol) {
        String symbol = CanonicalQuote.normalizeSymbol(rawSymbol);
        String exchange = CanonicalQuote.exchangeOf(symbol);
        String ticker = symbol.substring(symbol.indexOf('.') + 1);
        if ("HK".equals(exchange)) ticker = stripLeadingZeros(ticker);
        return ticker + "." + exchange;
    }

    static String fromLongbridgeSymbol(String rawSymbol) {
        if (rawSymbol == null) throw new IllegalArgumentException("Longbridge symbol is required");
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        int separator = symbol.lastIndexOf('.');
        if (separator <= 0 || separator == symbol.length() - 1) {
            throw new IllegalArgumentException("invalid Longbridge symbol: " + rawSymbol);
        }
        String ticker = symbol.substring(0, separator);
        String exchange = symbol.substring(separator + 1);
        return switch (exchange) {
            case "SH", "SZ" -> CanonicalQuote.normalizeSymbol(exchange + "." + leftPad(ticker, 6));
            case "HK" -> CanonicalQuote.normalizeSymbol("HK." + leftPad(ticker, 5));
            case "US" -> CanonicalQuote.normalizeSymbol("US." + ticker);
            default -> throw new IllegalArgumentException("unsupported Longbridge exchange: " + exchange);
        };
    }

    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') index++;
        return value.substring(index);
    }

    private static String leftPad(String value, int width) {
        if (!value.matches("\\d+") || value.length() > width) {
            throw new IllegalArgumentException("invalid numeric ticker: " + value);
        }
        return "0".repeat(width - value.length()) + value;
    }

    @Override
    @PreDestroy
    public void close() {
        QuoteContext oldContext;
        Config oldConfig;
        synchronized (contextMonitor) {
            oldContext = context;
            oldConfig = config;
            context = null;
            config = null;
        }
        closeQuietly(oldContext, "quote context");
        closeQuietly(oldConfig, "configuration");
    }

    private void closeConfigAfterInitializationFailure() {
        Config failed = config;
        config = null;
        closeQuietly(failed, "failed configuration");
    }

    private static void closeQuietly(AutoCloseable closeable, String description) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            LOGGER.warn("Could not close Longbridge {}", description, exception);
        }
    }

    @FunctionalInterface
    private interface SdkRequest<T> {
        CompletableFuture<T> start(QuoteContext context) throws OpenApiException;
    }
}
