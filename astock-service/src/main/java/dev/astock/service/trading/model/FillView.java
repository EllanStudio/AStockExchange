package dev.astock.service.trading.model;

import java.time.Instant;

public record FillView(
        String fillId,
        String orderId,
        String symbol,
        long quantity,
        long price,
        long notional,
        long fee,
        long quoteSequence,
        String quoteSource,
        long quoteTimestamp,
        Instant createdAt
) {
}
