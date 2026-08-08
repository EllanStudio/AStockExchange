package dev.astock.service.trading;

public class TradeRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public TradeRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
