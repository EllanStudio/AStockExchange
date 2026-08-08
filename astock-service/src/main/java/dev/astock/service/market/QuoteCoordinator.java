package dev.astock.service.market;

import dev.astock.domain.quote.CanonicalQuote;
import dev.astock.domain.quote.QuoteAssessment;
import dev.astock.domain.quote.QuoteQualityGate;
import dev.astock.service.config.AStockProperties;
import dev.astock.service.security.SecurityCatalog;
import dev.astock.service.security.SecurityInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class QuoteCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuoteCoordinator.class);
    private final SecurityCatalog catalog;
    private final MarketDataProviderRouter router;
    private final QuoteNormalizer normalizer;
    private final QuoteQualityGate qualityGate;
    private final ApplicationEventPublisher events;
    private final AStockProperties properties;
    private final Clock clock;
    private final ZoneId marketZone;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, CanonicalQuote> latest = new ConcurrentHashMap<>();
    private final Map<String, DetailFlight> detailFlights = new ConcurrentHashMap<>();
    private final ExecutorService detailExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile long lastSuccessfulRefresh;
    private volatile String lastRefreshError;

    public QuoteCoordinator(
            SecurityCatalog catalog,
            MarketDataProviderRouter router,
            QuoteNormalizer normalizer,
            QuoteQualityGate qualityGate,
            ApplicationEventPublisher events,
            AStockProperties properties,
            Clock clock
    ) {
        this.catalog = catalog;
        this.router = router;
        this.normalizer = normalizer;
        this.qualityGate = qualityGate;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.marketZone = ZoneId.of(properties.market().timezone());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialRefresh() {
        refreshAll();
    }

    @Scheduled(fixedDelayString = "${astock.data.bulk-refresh:10s}")
    public void refreshAll() {
        LocalDate date = Instant.ofEpochMilli(clock.millis()).atZone(marketZone).toLocalDate();
        List<SecurityInfo> securities = catalog.findEnabled(date);
        if (securities.isEmpty()) return;
        try {
            List<ProviderQuote> rawQuotes = router.fetchBulk(securities);
            Map<String, ProviderQuote> comparisons = router.fetchComparisonBulk(securities)
                    .orElseGet(List::of).stream()
                    .collect(java.util.stream.Collectors.toMap(ProviderQuote::symbol, value -> value, (left, right) -> left));
            Map<String, SecurityInfo> bySymbol = securities.stream()
                    .collect(java.util.stream.Collectors.toMap(SecurityInfo::symbol, value -> value));
            long epoch = sequence.incrementAndGet();
            for (ProviderQuote raw : rawQuotes) {
                SecurityInfo security = bySymbol.get(raw.symbol());
                if (security != null) accept(security, raw, epoch, Optional.ofNullable(comparisons.get(raw.symbol())));
            }
            lastSuccessfulRefresh = clock.millis();
            lastRefreshError = null;
        } catch (RuntimeException exception) {
            lastRefreshError = exception.getMessage();
            LOGGER.error("Bulk quote refresh failed; execution remains fail-closed", exception);
        }
    }

    public Optional<CanonicalQuote> current(String symbol) {
        return Optional.ofNullable(latest.get(symbol.trim().toUpperCase(Locale.ROOT)));
    }

    public List<CanonicalQuote> snapshot() {
        return latest.values().stream().sorted(Comparator.comparing(CanonicalQuote::symbol)).toList();
    }

    public CompletableFuture<CanonicalQuote> refreshDetail(String rawSymbol) {
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        long now = clock.millis();
        DetailFlight flight = detailFlights.compute(symbol, (key, existing) -> {
            if (existing != null && now - existing.startedAt() < properties.data().detailSingleFlight().toMillis()) {
                return existing;
            }
            var future = CompletableFuture.supplyAsync(() -> loadDetail(key), detailExecutor);
            return new DetailFlight(now, future);
        });
        return flight.future();
    }

    public QuoteServiceStatus status() {
        return new QuoteServiceStatus(latest.size(), sequence.get(), lastSuccessfulRefresh,
                lastRefreshError, router.status());
    }

    private CanonicalQuote loadDetail(String symbol) {
        LocalDate date = Instant.ofEpochMilli(clock.millis()).atZone(marketZone).toLocalDate();
        SecurityInfo security = catalog.findBySymbol(symbol, date)
                .orElseThrow(() -> new IllegalArgumentException("unknown security: " + symbol));
        ProviderQuote raw = router.fetchDetail(security)
                .orElseThrow(() -> new MarketDataException("no detail quote for " + symbol));
        return accept(security, raw, sequence.incrementAndGet(), router.fetchComparisonDetail(security)).quote();
    }

    private QuoteAssessment accept(SecurityInfo security, ProviderQuote raw, long epoch,
                                   Optional<ProviderQuote> comparison) {
        CanonicalQuote normalized = normalizer.normalize(security, raw, epoch, clock.millis());
        Optional<CanonicalQuote> normalizedComparison = comparison.map(value ->
                normalizer.normalize(security, value, epoch, clock.millis()));
        QuoteAssessment assessment = qualityGate.assess(normalized, security.rule().priceLimitBps(), normalizedComparison);
        if (assessment.acceptedForDisplay()) {
            latest.put(security.symbol(), assessment.quote());
            events.publishEvent(new QuoteUpdatedEvent(assessment.quote(), assessment.executable()));
        } else {
            LOGGER.warn("Rejected quote {} from {}: {}", security.symbol(), raw.source(), assessment.violations());
        }
        return assessment;
    }

    @PreDestroy
    void close() {
        detailExecutor.close();
    }

    private record DetailFlight(long startedAt, CompletableFuture<CanonicalQuote> future) {
    }

    public record QuoteServiceStatus(
            int quoteCount,
            long latestSequence,
            long lastSuccessfulRefresh,
            String lastRefreshError,
            MarketDataProviderRouter.SourceStatus source
    ) {
    }
}
