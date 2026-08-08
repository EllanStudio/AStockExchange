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
        }
        if (!value.matches("(?:SH|SZ)\\.\\d{6}")) throw new IllegalArgumentException("股票代码格式无效");
        return value;
    }

    public static String rootMessage(Throwable throwable) {
        Throwable value = throwable;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }
}
