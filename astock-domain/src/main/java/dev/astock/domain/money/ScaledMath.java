package dev.astock.domain.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Fixed-point conversions shared by validation, reservation and settlement. */
public final class ScaledMath {
    public static final long PRICE_UNITS_PER_CURRENCY_UNIT = 10_000L;
    /** @deprecated use {@link #PRICE_UNITS_PER_CURRENCY_UNIT}. */
    @Deprecated(forRemoval = false)
    public static final long PRICE_UNITS_PER_CNY = PRICE_UNITS_PER_CURRENCY_UNIT;
    public static final long CASH_MINOR_PER_GAME_COIN = 100L;
    public static final long BPS_DENOMINATOR = 10_000L;

    private ScaledMath() {
    }

    public static long priceUnits(BigDecimal nativePrice) {
        if (nativePrice == null || nativePrice.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        return nativePrice.multiply(BigDecimal.valueOf(PRICE_UNITS_PER_CURRENCY_UNIT))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** @deprecated prices may be CNY, HKD or USD; use {@link #currency(long)}. */
    @Deprecated(forRemoval = false)
    public static BigDecimal cny(long priceUnits) {
        return currency(priceUnits);
    }

    public static BigDecimal currency(long priceUnits) {
        return BigDecimal.valueOf(priceUnits, 4);
    }

    /**
     * Converts shares times a native-currency price into hundredths of a game
     * coin. {@code gameCoinsPerCurrencyUnit} is an operator-defined integer
     * game-economy rate, not a live foreign-exchange quote.
     */
    public static long notionalCash(long quantity, long priceUnits, long gameCoinsPerCurrencyUnit) {
        if (quantity < 0 || priceUnits < 0 || gameCoinsPerCurrencyUnit <= 0) {
            throw new IllegalArgumentException("invalid notional input");
        }
        BigInteger numerator = BigInteger.valueOf(quantity)
                .multiply(BigInteger.valueOf(priceUnits))
                .multiply(BigInteger.valueOf(gameCoinsPerCurrencyUnit));
        return divideHalfUp(numerator, BigInteger.valueOf(100L));
    }

    public static long fee(long notional, int feeBps, long minimumFee) {
        if (notional < 0 || feeBps < 0 || minimumFee < 0) throw new IllegalArgumentException("invalid fee input");
        long calculated = divideHalfUp(
                BigInteger.valueOf(notional).multiply(BigInteger.valueOf(feeBps)),
                BigInteger.valueOf(BPS_DENOMINATOR)
        );
        return Math.max(calculated, minimumFee);
    }

    public static long addBps(long value, int bps) {
        if (value < 0 || bps < 0) throw new IllegalArgumentException("value and bps must not be negative");
        return multiplyRatio(value, BPS_DENOMINATOR + bps, BPS_DENOMINATOR);
    }

    public static long subtractBps(long value, int bps) {
        if (value < 0 || bps < 0 || bps >= BPS_DENOMINATOR) {
            throw new IllegalArgumentException("invalid value or bps");
        }
        return multiplyRatio(value, BPS_DENOMINATOR - bps, BPS_DENOMINATOR);
    }

    public static long safeTotal(long notional, long fee) {
        return Math.addExact(notional, fee);
    }

    private static long multiplyRatio(long value, long multiplier, long divisor) {
        return divideHalfUp(
                BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier)),
                BigInteger.valueOf(divisor)
        );
    }

    private static long divideHalfUp(BigInteger numerator, BigInteger denominator) {
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        BigInteger quotient = parts[0];
        if (parts[1].abs().shiftLeft(1).compareTo(denominator.abs()) >= 0) {
            quotient = quotient.add(BigInteger.valueOf(numerator.signum() * denominator.signum()));
        }
        return quotient.longValueExact();
    }
}
