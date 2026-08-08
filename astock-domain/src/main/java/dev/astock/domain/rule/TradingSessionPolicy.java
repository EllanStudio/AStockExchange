package dev.astock.domain.rule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

public final class TradingSessionPolicy {
    private static final LocalTime PRE_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MORNING_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MORNING_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime AFTERNOON_OPEN = LocalTime.of(13, 0);
    private static final LocalTime CONTINUOUS_CLOSE = LocalTime.of(14, 57);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 0);

    public MarketPhase phaseAt(LocalDateTime shanghaiTime, Set<LocalDate> holidays) {
        LocalDate date = shanghaiTime.toLocalDate();
        if (!isTradingDay(date, holidays)) return MarketPhase.HOLIDAY;
        LocalTime time = shanghaiTime.toLocalTime();
        if (time.isBefore(PRE_OPEN)) return MarketPhase.CLOSED;
        if (time.isBefore(MORNING_OPEN)) return MarketPhase.PRE_OPEN;
        if (time.isBefore(MORNING_CLOSE)) return MarketPhase.CONTINUOUS;
        if (time.isBefore(AFTERNOON_OPEN)) return MarketPhase.MIDDAY_BREAK;
        if (time.isBefore(CONTINUOUS_CLOSE)) return MarketPhase.CONTINUOUS;
        if (time.isBefore(MARKET_CLOSE)) return MarketPhase.CLOSING_AUCTION;
        return MarketPhase.CLOSED;
    }

    public LocalDate nextTradingDay(LocalDate after, Set<LocalDate> holidays) {
        LocalDate date = after.plusDays(1);
        while (!isTradingDay(date, holidays)) date = date.plusDays(1);
        return date;
    }

    public boolean isTradingDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }
}
