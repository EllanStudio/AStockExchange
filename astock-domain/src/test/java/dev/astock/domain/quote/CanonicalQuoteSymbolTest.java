package dev.astock.domain.quote;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalQuoteSymbolTest {
    @Test
    void normalizesMainlandHongKongAndUnitedStatesSymbols() {
        assertThat(CanonicalQuote.normalizeSymbol(" sh.600519 ")).isEqualTo("SH.600519");
        assertThat(CanonicalQuote.normalizeSymbol("hk.00700")).isEqualTo("HK.00700");
        assertThat(CanonicalQuote.normalizeSymbol("us.brk.b")).isEqualTo("US.BRK.B");
        assertThat(CanonicalQuote.exchangeOf("US.AAPL")).isEqualTo("US");
    }

    @Test
    void rejectsAmbiguousOrMalformedSymbols() {
        assertThatThrownBy(() -> CanonicalQuote.normalizeSymbol("00700"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalQuote.normalizeSymbol("US.BAD..CODE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
