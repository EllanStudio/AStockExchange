package dev.astock.domain.rule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TradingSessionPolicyTest {
    private final TradingSessionPolicy policy = new TradingSessionPolicy();

    @Test
    void modelsContinuousAndNonExecutionPhases() {
        assertThat(policy.phaseAt(LocalDateTime.of(2026, 8, 7, 10, 0), Set.of()))
                .isEqualTo(MarketPhase.CONTINUOUS);
        assertThat(policy.phaseAt(LocalDateTime.of(2026, 8, 7, 12, 0), Set.of()))
                .isEqualTo(MarketPhase.MIDDAY_BREAK);
        assertThat(policy.phaseAt(LocalDateTime.of(2026, 8, 7, 14, 58), Set.of()))
                .isEqualTo(MarketPhase.CLOSING_AUCTION);
    }

    @Test
    void skipsWeekendAndHolidayForSettlement() {
        LocalDate friday = LocalDate.of(2026, 8, 7);
        LocalDate monday = LocalDate.of(2026, 8, 10);
        assertThat(policy.nextTradingDay(friday, Set.of(monday)))
                .isEqualTo(LocalDate.of(2026, 8, 11));
    }
}
