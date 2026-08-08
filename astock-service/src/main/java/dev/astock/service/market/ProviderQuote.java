package dev.astock.service.market;

import dev.astock.domain.quote.TradingStatus;

import java.math.BigDecimal;

public record ProviderQuote(
        String symbol,
        String name,
        long sourceTimestamp,
        BigDecimal lastPrice,
        BigDecimal previousClose,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal bid1Price,
        long bid1Volume,
        BigDecimal ask1Price,
        long ask1Volume,
        long volume,
        long turnover,
        TradingStatus status,
        String source
) {
}
