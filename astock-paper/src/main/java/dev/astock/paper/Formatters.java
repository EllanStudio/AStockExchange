package dev.astock.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Locale;

public final class Formatters {
    private Formatters() {
    }

    public static String price(long units) {
        return BigDecimal.valueOf(units, 4).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String price(String symbol, long units) {
        String currency = symbol.startsWith("HK.") ? "HK$"
                : symbol.startsWith("US.") ? "$" : "¥";
        return currency + price(units);
    }

    public static String cash(long minor) {
        return new DecimalFormat("#,##0.00").format(BigDecimal.valueOf(minor, 2));
    }

    public static long priceUnits(String value) {
        return new BigDecimal(value).multiply(BigDecimal.valueOf(10_000))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long cashMinor(String value) {
        return new BigDecimal(value).multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String symbol(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('_', '.');
        if (value.matches("\\d{6}")) {
            value = (value.startsWith("5") || value.startsWith("6") || value.startsWith("9") ? "SH." : "SZ.") + value;
        } else if (value.matches("\\d{1,5}")) {
            value = "HK." + "0".repeat(5 - value.length()) + value;
        } else if (value.matches("HK\\.\\d{1,5}")) {
            String ticker = value.substring(3);
            value = "HK." + "0".repeat(5 - ticker.length()) + ticker;
        } else if (!value.matches("(?:SH|SZ|HK|US)\\..+")) {
            value = "US." + value;
        }
        if (!value.matches("(?:SH|SZ)\\.\\d{6}")
                && !value.matches("HK\\.\\d{5}")
                && !value.matches("US\\.(?=.{1,15}$)[A-Z][A-Z0-9]*(?:[.-][A-Z0-9]+)*")) {
            throw new IllegalArgumentException("股票代码格式无效");
        }
        return value;
    }

    public static String rootMessage(Throwable throwable) {
        Throwable value = throwable;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }
}
