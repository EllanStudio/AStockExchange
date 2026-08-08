package dev.astock.service.trading.model;

public enum TransferStatus {
    REQUESTED,
    ECONOMY_DEBITED,
    BROKER_CREDITED,
    BROKER_DEBITED,
    ECONOMY_CREDITED,
    COMPLETED,
    COMPENSATED,
    FAILED
}
