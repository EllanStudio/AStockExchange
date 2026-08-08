package dev.astock.service.trading.model;

import dev.astock.domain.order.OrderSide;
import dev.astock.domain.order.OrderStatus;
import dev.astock.domain.order.OrderType;

import java.time.Instant;

public record OrderView(
        String orderId,
        String clientRequestId,
        long accountId,
        int securityId,
        String symbol,
        OrderSide side,
        OrderType type,
        long quantity,
        long remainingQuantity,
        Long limitPrice,
        Long priceCap,
        long acceptedQuoteSequence,
        OrderStatus status,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt
) {
}
