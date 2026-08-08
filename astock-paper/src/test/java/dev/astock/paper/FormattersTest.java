package dev.astock.paper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormattersTest {
    @Test
    void infersMarketPrefixesAndNativeCurrencyLabels() {
        assertThat(Formatters.symbol("600519")).isEqualTo("SH.600519");
        assertThat(Formatters.symbol("700")).isEqualTo("HK.00700");
        assertThat(Formatters.symbol("aapl")).isEqualTo("US.AAPL");
        assertThat(Formatters.price("HK.00700", 1_234_500)).isEqualTo("HK$123.45");
        assertThat(Formatters.price("US.AAPL", 2_500_000)).isEqualTo("$250.00");
    }
}
