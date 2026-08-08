package dev.astock.service.market;

public class MarketDataException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public MarketDataException(String message) {
        super(message);
    }

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
