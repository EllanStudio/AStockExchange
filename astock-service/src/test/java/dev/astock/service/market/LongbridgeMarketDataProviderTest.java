package dev.astock.service.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongbridgeMarketDataProviderTest {
    @Test
    void mapsCanonicalSymbolsToLongbridgeSymbols() {
        assertThat(LongbridgeMarketDataProvider.toLongbridgeSymbol("SH.600519"))
                .isEqualTo("600519.SH");
        assertThat(LongbridgeMarketDataProvider.toLongbridgeSymbol("HK.00700"))
                .isEqualTo("700.HK");
        assertThat(LongbridgeMarketDataProvider.toLongbridgeSymbol("US.BRK.B"))
                .isEqualTo("BRK.B.US");
    }

    @Test
    void mapsLongbridgeSymbolsBackWithoutLosingPaddingOrClassSuffixes() {
        assertThat(LongbridgeMarketDataProvider.fromLongbridgeSymbol("1.SZ"))
                .isEqualTo("SZ.000001");
        assertThat(LongbridgeMarketDataProvider.fromLongbridgeSymbol("700.HK"))
                .isEqualTo("HK.00700");
        assertThat(LongbridgeMarketDataProvider.fromLongbridgeSymbol("BRK.B.US"))
                .isEqualTo("US.BRK.B");
        assertThatThrownBy(() -> LongbridgeMarketDataProvider.fromLongbridgeSymbol("BTC.CC"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
