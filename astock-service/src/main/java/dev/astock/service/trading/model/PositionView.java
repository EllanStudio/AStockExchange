package dev.astock.service.trading.model;

public record PositionView(
        int securityId,
        String symbol,
        String name,
        long quantityTotal,
        long quantityAvailable,
        long quantityFrozen,
        long averageCost
) {
}
