package dev.astock.domain.quote;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provider-neutral quote. Prices use 1/10,000 of the security's native
 * currency, quantities use shares and timestamps use Unix epoch milliseconds.
 */
public record CanonicalQuote(
        int securityId,
        String symbol,
        long sequence,
        long sourceTimestamp,
        long receivedTimestamp,
        long lastPrice,
        long previousClose,
        long openPrice,
        long highPrice,
        long lowPrice,
        long bid1Price,
        long bid1Volume,
        long ask1Price,
        long ask1Volume,
        long volume,
        long turnover,
        TradingStatus status,
        QuoteQuality quality,
        String source
) {
    private static final Pattern CN_SYMBOL = Pattern.compile("(?:SH|SZ)\\.\\d{6}");
    private static final Pattern HK_SYMBOL = Pattern.compile("HK\\.\\d{5}");
    private static final Pattern US_SYMBOL = Pattern.compile(
            "US\\.(?=.{1,15}$)[A-Z][A-Z0-9]*(?:[.-][A-Z0-9]+)*"
    );

    public CanonicalQuote {
        if (securityId <= 0) {
            throw new IllegalArgumentException("securityId must be positive");
        }
        symbol = normalizeSymbol(symbol);
        if (sequence < 0 || sourceTimestamp < 0 || receivedTimestamp < 0) {
            throw new IllegalArgumentException("sequence and timestamps must not be negative");
        }
        status = Objects.requireNonNull(status, "status");
        quality = Objects.requireNonNull(quality, "quality");
        source = Objects.requireNonNull(source, "source").trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }

    public CanonicalQuote withQuality(QuoteQuality newQuality) {
        return new CanonicalQuote(
                securityId, symbol, sequence, sourceTimestamp, receivedTimestamp,
                lastPrice, previousClose, openPrice, highPrice, lowPrice,
                bid1Price, bid1Volume, ask1Price, ask1Volume, volume, turnover,
                status, newQuality, source
        );
    }

    public boolean hasExecutableBook() {
        return bid1Price > 0 && bid1Volume > 0 && ask1Price > 0 && ask1Volume > 0;
    }

    public static String normalizeSymbol(String value) {
        Objects.requireNonNull(value, "symbol");
        String symbol = value.trim().toUpperCase(Locale.ROOT);
        if (!CN_SYMBOL.matcher(symbol).matches()
                && !HK_SYMBOL.matcher(symbol).matches()
                && !US_SYMBOL.matcher(symbol).matches()) {
            throw new IllegalArgumentException(
                    "symbol must look like SH.600519, SZ.000001, HK.00700 or US.AAPL"
            );
        }
        return symbol;
    }

    public static String exchangeOf(String value) {
        String symbol = normalizeSymbol(value);
        return symbol.substring(0, symbol.indexOf('.'));
    }
}
