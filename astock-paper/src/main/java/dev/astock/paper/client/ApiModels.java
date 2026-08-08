package dev.astock.paper.client;

import java.util.UUID;

public final class ApiModels {
    private ApiModels() {
    }

    public record Quote(
            int securityId,
            String symbol,
            long sequence,
            long sourceTimestamp,
            long receivedTimestamp,
            long lastPrice,
            long previousClose,
            long openPrice,
            long highPrice,
            long lowPrice,
            long bid1Price,
            long bid1Volume,
            long ask1Price,
            long ask1Volume,
            long volume,
            long turnover,
            String status,
            String quality,
            String source
    ) {
        public boolean executable() {
            return "TRADING".equals(status) && "VALID".equals(quality)
                    && bid1Price > 0 && bid1Volume > 0 && ask1Price > 0 && ask1Volume > 0;
        }
    }

    public record Account(long accountId, UUID playerUuid, long cashAvailable, long cashFrozen, long version) {
    }

    public record Position(
            int securityId,
            String symbol,
            String name,
            long quantityTotal,
            long quantityAvailable,
            long quantityFrozen,
            long averageCost
    ) {
    }

    public record Order(
            String orderId,
            String clientRequestId,
            long accountId,
            int securityId,
            String symbol,
            String side,
            String type,
            long quantity,
            long remainingQuantity,
            Long limitPrice,
            Long priceCap,
            long acceptedQuoteSequence,
            String status,
            String rejectReason,
            String createdAt,
            String updatedAt
    ) {
    }

    public record Transfer(
            String transferId,
            String clientRequestId,
            UUID playerUuid,
            String direction,
            long amount,
            String status,
            String failureReason,
            String createdAt,
            String updatedAt
    ) {
    }

    public record PlaceOrderRequest(
            String clientRequestId,
            UUID playerUuid,
            String symbol,
            String side,
            String type,
            long quantity,
            Long limitPrice
    ) {
    }

    public record BeginTransferRequest(
            String clientRequestId,
            UUID playerUuid,
            String direction,
            long amount
    ) {
    }

    public record CompensateRequest(String reason) {
    }
}
