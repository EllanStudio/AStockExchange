package dev.astock.domain.order;

import dev.astock.domain.quote.CanonicalQuote;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record PlaceOrderCommand(
        String clientRequestId,
        UUID playerUuid,
        String symbol,
        OrderSide side,
        OrderType type,
        long quantity,
        Long limitPrice
) {
    public PlaceOrderCommand {
        clientRequestId = Objects.requireNonNull(clientRequestId, "clientRequestId").trim();
        if (clientRequestId.isEmpty() || clientRequestId.length() > 64) {
            throw new IllegalArgumentException("clientRequestId must contain 1-64 characters");
        }
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        symbol = CanonicalQuote.normalizeSymbol(symbol.toUpperCase(Locale.ROOT));
        side = Objects.requireNonNull(side, "side");
        type = Objects.requireNonNull(type, "type");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice <= 0)) {
            throw new IllegalArgumentException("a positive limitPrice is required for limit orders");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("market orders must not provide limitPrice");
        }
    }
}
