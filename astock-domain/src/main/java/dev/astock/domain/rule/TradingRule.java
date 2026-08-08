package dev.astock.domain.rule;

import java.time.LocalDate;

public record TradingRule(
        int securityId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        long lotSize,
        long tickSize,
        int priceLimitBps,
        int tPlusDays,
        boolean enabled
) {
    public TradingRule {
        if (securityId <= 0 || effectiveFrom == null) throw new IllegalArgumentException("invalid rule identity");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo is before effectiveFrom");
        }
        if (lotSize <= 0 || tickSize <= 0 || priceLimitBps <= 0 || tPlusDays < 0) {
            throw new IllegalArgumentException("invalid trading rule values");
        }
    }

    public boolean appliesOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    public void validateOrder(long quantity, Long limitPrice) {
        if (!enabled) throw new IllegalStateException("security is disabled");
        if (quantity <= 0 || quantity % lotSize != 0) {
            throw new IllegalArgumentException("quantity must be a positive multiple of " + lotSize);
        }
        if (limitPrice != null && (limitPrice <= 0 || limitPrice % tickSize != 0)) {
            throw new IllegalArgumentException("limit price must align to tick size " + tickSize);
        }
    }
}
