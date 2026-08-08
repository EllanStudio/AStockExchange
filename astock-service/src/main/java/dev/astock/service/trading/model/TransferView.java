package dev.astock.service.trading.model;

import java.time.Instant;
import java.util.UUID;

public record TransferView(
        String transferId,
        String clientRequestId,
        UUID playerUuid,
        TransferDirection direction,
        long amount,
        TransferStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
