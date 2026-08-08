package dev.astock.service.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketControllerTest {
    @Test
    void infersCanonicalMarketPrefixesForConveniencePaths() {
        assertThat(MarketController.normalizePathSymbol("600519")).isEqualTo("SH.600519");
        assertThat(MarketController.normalizePathSymbol("700")).isEqualTo("HK.00700");
        assertThat(MarketController.normalizePathSymbol("US_AAPL")).isEqualTo("US.AAPL");
        assertThat(MarketController.normalizePathSymbol("brk.b")).isEqualTo("US.BRK.B");
    }
}
