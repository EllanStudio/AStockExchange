package dev.astock.domain.quote;

import java.time.Duration;

public record QuoteQualityPolicy(
        Duration displayStaleAfter,
        Duration executionStaleAfter,
        int sourceDivergenceBps,
        int priceConsistencyToleranceBps,
        int priceLimitToleranceBps
) {
    public QuoteQualityPolicy {
        if (displayStaleAfter == null || executionStaleAfter == null) {
            throw new IllegalArgumentException("stale durations are required");
        }
        if (displayStaleAfter.isNegative() || displayStaleAfter.isZero()
                || executionStaleAfter.isNegative() || executionStaleAfter.isZero()) {
            throw new IllegalArgumentException("stale durations must be positive");
        }
        if (sourceDivergenceBps < 0 || priceConsistencyToleranceBps < 0 || priceLimitToleranceBps < 0) {
            throw new IllegalArgumentException("basis-point thresholds must not be negative");
        }
    }

    public static QuoteQualityPolicy defaults() {
        return new QuoteQualityPolicy(Duration.ofSeconds(15), Duration.ofSeconds(5), 35, 10, 20);
    }
}
