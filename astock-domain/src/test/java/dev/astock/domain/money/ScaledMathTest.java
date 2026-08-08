package dev.astock.domain.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaledMathTest {
    @Test
    void convertsPriceAndNotionalWithoutFloatingPoint() {
        long price = ScaledMath.priceUnits(new BigDecimal("10.12345"));
        assertThat(price).isEqualTo(101_235L);
        assertThat(ScaledMath.notionalCash(100, price, 100)).isEqualTo(10_123_500L);
    }

    @Test
    void checksOverflowAtTheBoundary() {
        assertThatThrownBy(() -> ScaledMath.notionalCash(Long.MAX_VALUE, Long.MAX_VALUE, 100))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void calculatesSpreadAndMinimumFee() {
        assertThat(ScaledMath.addBps(100_000, 15)).isEqualTo(100_150);
        assertThat(ScaledMath.subtractBps(100_000, 15)).isEqualTo(99_850);
        assertThat(ScaledMath.fee(100_000, 3, 100)).isEqualTo(100);
    }
}
