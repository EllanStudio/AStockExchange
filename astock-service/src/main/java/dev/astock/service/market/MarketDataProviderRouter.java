package dev.astock.service.market;

import dev.astock.service.config.AStockProperties;
import dev.astock.service.security.SecurityInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MarketDataProviderRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketDataProviderRouter.class);
    private final Map<String, MarketDataProvider> providers;
    private final AStockProperties properties;
    private final Clock clock;
    private final AtomicInteger consecutiveErrors = new AtomicInteger();
    private final AtomicLong breakerUntil = new AtomicLong();
    private volatile String activeSource;
    private volatile String lastError;

    public MarketDataProviderRouter(List<MarketDataProvider> providers, AStockProperties properties, Clock clock) {
        this.providers = new LinkedHashMap<>();
        providers.forEach(provider -> this.providers.put(provider.name(), provider));
        this.properties = properties;
        this.clock = clock;
        this.activeSource = properties.data().primary();
        if (!"mock".equals(properties.data().primary()) && "mock".equals(properties.data().fallback())) {
            throw new IllegalStateException("mock must never be the fallback for a real execution source");
        }
        if ("mock".equals(properties.data().primary())) {
            LOGGER.warn("Synthetic mock quotes are active; this mode is for development only");
        }
    }

    public List<ProviderQuote> fetchBulk(List<SecurityInfo> securities) {
        return execute(provider -> provider.fetchBulk(securities));
    }

    public Optional<ProviderQuote> fetchDetail(SecurityInfo security) {
        return execute(provider -> provider.fetchDetail(security));
    }

    public Optional<List<ProviderQuote>> fetchComparisonBulk(List<SecurityInfo> securities) {
        String fallbackName = properties.data().fallback();
        if (fallbackName == null || fallbackName.isBlank() || fallbackName.equals(activeSource)) return Optional.empty();
        try {
            return Optional.of(require(fallbackName).fetchBulk(securities));
        } catch (RuntimeException exception) {
            LOGGER.warn("Comparison market provider {} failed", fallbackName, exception);
            return Optional.empty();
        }
    }

    public Optional<ProviderQuote> fetchComparisonDetail(SecurityInfo security) {
        String fallbackName = properties.data().fallback();
        if (fallbackName == null || fallbackName.isBlank() || fallbackName.equals(activeSource)) return Optional.empty();
        try {
            return require(fallbackName).fetchDetail(security);
        } catch (RuntimeException exception) {
            LOGGER.warn("Comparison detail provider {} failed for {}", fallbackName, security.symbol(), exception);
            return Optional.empty();
        }
    }

    public SourceStatus status() {
        return new SourceStatus(properties.data().primary(), properties.data().fallback(), activeSource,
                consecutiveErrors.get(), breakerUntil.get(), lastError);
    }

    private <T> T execute(ProviderCall<T> call) {
        String primaryName = properties.data().primary();
        String fallbackName = properties.data().fallback();
        MarketDataProvider primary = require(primaryName);
        boolean breakerOpen = clock.millis() < breakerUntil.get();
        if (!breakerOpen) {
            try {
                T result = call.apply(primary);
                consecutiveErrors.set(0);
                activeSource = primary.name();
                lastError = null;
                return result;
            } catch (RuntimeException exception) {
                lastError = exception.getMessage();
                int failures = consecutiveErrors.incrementAndGet();
                LOGGER.warn("Market provider {} failed ({}/{})", primary.name(), failures,
                        properties.data().consecutiveErrorBreaker(), exception);
                if (failures >= properties.data().consecutiveErrorBreaker()) {
                    breakerUntil.set(clock.millis() + 30_000L);
                }
            }
        }
        if (fallbackName != null && !fallbackName.isBlank() && !fallbackName.equals(primaryName)) {
            MarketDataProvider fallback = require(fallbackName);
            T result = call.apply(fallback);
            activeSource = fallback.name();
            return result;
        }
        throw new MarketDataException("primary market provider unavailable: " + primaryName);
    }

    private MarketDataProvider require(String name) {
        MarketDataProvider provider = providers.get(name);
        if (provider == null) throw new MarketDataException("unknown market data provider: " + name);
        return provider;
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T apply(MarketDataProvider provider);
    }

    public record SourceStatus(
            String configuredPrimary,
            String configuredFallback,
            String activeSource,
            int consecutiveErrors,
            long breakerUntil,
            String lastError
    ) {
    }
}
