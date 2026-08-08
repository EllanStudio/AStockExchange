package dev.astock.service.security;

import dev.astock.domain.rule.TradingRule;

public record SecurityInfo(
        int id,
        String symbol,
        String name,
        String exchange,
        String board,
        String currency,
        boolean enabled,
        String frozenReason,
        TradingRule rule
) {
}
