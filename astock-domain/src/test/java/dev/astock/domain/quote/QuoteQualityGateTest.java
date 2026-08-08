package dev.astock.domain.quote;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteQualityGateTest {
    private static final long NOW = 1_800_000_000_000L;
    private final QuoteQualityGate gate = new QuoteQualityGate(
            QuoteQualityPolicy.defaults(),
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    );

    @Test
    void acceptsFreshMonotonicExecutableQuote() {
        QuoteAssessment result = gate.assess(quote(1, NOW - 500, 1_000), 1_000);
        assertThat(result.acceptedForDisplay()).isTrue();
        assertThat(result.executable()).isTrue();
        assertThat(result.quote().quality()).isEqualTo(QuoteQuality.VALID);
    }

    @Test
    void rejectsRollbackAndDoesNotReplaceGoodState() {
        gate.assess(quote(2, NOW - 500, 1_000), 1_000);
        QuoteAssessment bad = gate.assess(quote(1, NOW - 600, 900), 1_000);
        QuoteAssessment next = gate.assess(quote(3, NOW - 400, 1_100), 1_000);
        assertThat(bad.acceptedForDisplay()).isFalse();
        assertThat(bad.violations()).contains("sequence did not advance", "source timestamp moved backwards", "intraday volume decreased");
        assertThat(next.acceptedForDisplay()).isTrue();
    }

    @Test
    void displaysButWillNotExecuteOnMissingBook() {
        CanonicalQuote quote = quote(1, NOW - 500, 1_000);
        quote = new CanonicalQuote(quote.securityId(), quote.symbol(), quote.sequence(), quote.sourceTimestamp(),
                quote.receivedTimestamp(), quote.lastPrice(), quote.previousClose(), quote.openPrice(), quote.highPrice(),
                quote.lowPrice(), 0, 0, quote.ask1Price(), quote.ask1Volume(), quote.volume(), quote.turnover(),
                quote.status(), quote.quality(), quote.source());
        QuoteAssessment result = gate.assess(quote, 1_000);
        assertThat(result.acceptedForDisplay()).isTrue();
        assertThat(result.executable()).isFalse();
        assertThat(result.quote().quality()).isEqualTo(QuoteQuality.DEGRADED);
    }

    @Test
    void exposesSourceConflictButFreezesExecution() {
        CanonicalQuote primary = quote(1, NOW - 500, 1_000);
        CanonicalQuote fallback = new CanonicalQuote(1, "SH.600519", 1, NOW - 500, NOW,
                1_010_000, 1_000_000, 995_000, 1_020_000, 990_000,
                1_009_900, 5_000, 1_010_100, 5_000, 1_000, 1_000_000_000,
                TradingStatus.TRADING, QuoteQuality.VALID, "fallback");
        QuoteAssessment result = gate.assess(primary, 1_000, Optional.of(fallback));
        assertThat(result.acceptedForDisplay()).isTrue();
        assertThat(result.executable()).isFalse();
        assertThat(result.quote().quality()).isEqualTo(QuoteQuality.CONFLICT);
    }

    private static CanonicalQuote quote(long sequence, long timestamp, long volume) {
        return new CanonicalQuote(1, "SH.600519", sequence, timestamp, NOW,
                1_000_000, 1_000_000, 995_000, 1_010_000, 990_000,
                999_900, 5_000, 1_000_100, 5_000, volume, 1_000_000_000,
                TradingStatus.TRADING, QuoteQuality.VALID, "test");
    }
}
