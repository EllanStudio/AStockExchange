package dev.astock.domain.order;

public enum OrderStatus {
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED;
    }
}
