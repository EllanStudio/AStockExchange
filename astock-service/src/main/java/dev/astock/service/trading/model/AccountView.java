package dev.astock.service.trading.model;

import java.util.UUID;

public record AccountView(
        long accountId,
        UUID playerUuid,
        long cashAvailable,
        long cashFrozen,
        long version
) {
}
