package dev.astock.domain.rule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

public final class TradingSessionPolicy {
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId HONG_KONG_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final ZoneId UNITED_STATES_ZONE = ZoneId.of("America/New_York");

    public MarketPhase phaseAt(LocalDateTime shanghaiTime, Set<LocalDate> holidays) {
        return phaseAt("SH", shanghaiTime, holidays);
    }

    public MarketPhase phaseAt(String exchange, Instant instant, Set<LocalDate> holidays) {
        return phaseAt(exchange, LocalDateTime.ofInstant(instant, zoneFor(exchange)), holidays);
    }

    public MarketPhase phaseAt(String exchange, LocalDateTime localTime, Set<LocalDate> holidays) {
        LocalDate date = localTime.toLocalDate();
        if (!isTradingDay(date, holidays)) return MarketPhase.HOLIDAY;
        LocalTime time = localTime.toLocalTime();
        return switch (normalizeExchange(exchange)) {
            case "SH", "SZ" -> chinaPhase(time);
            case "HK" -> hongKongPhase(time);
            case "US" -> unitedStatesPhase(time);
            default -> throw new IllegalArgumentException("unsupported exchange: " + exchange);
        };
    }

    public ZoneId zoneFor(String exchange) {
        return switch (normalizeExchange(exchange)) {
            case "SH", "SZ" -> CHINA_ZONE;
            case "HK" -> HONG_KONG_ZONE;
            case "US" -> UNITED_STATES_ZONE;
            default -> throw new IllegalArgumentException("unsupported exchange: " + exchange);
        };
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

    private static MarketPhase chinaPhase(LocalTime time) {
        if (time.isBefore(LocalTime.of(9, 15))) return MarketPhase.CLOSED;
        if (time.isBefore(LocalTime.of(9, 30))) return MarketPhase.PRE_OPEN;
        if (time.isBefore(LocalTime.of(11, 30))) return MarketPhase.CONTINUOUS;
        if (time.isBefore(LocalTime.of(13, 0))) return MarketPhase.MIDDAY_BREAK;
        if (time.isBefore(LocalTime.of(14, 57))) return MarketPhase.CONTINUOUS;
        if (time.isBefore(LocalTime.of(15, 0))) return MarketPhase.CLOSING_AUCTION;
        return MarketPhase.CLOSED;
    }

    private static MarketPhase hongKongPhase(LocalTime time) {
        if (time.isBefore(LocalTime.of(9, 0))) return MarketPhase.CLOSED;
        if (time.isBefore(LocalTime.of(9, 30))) return MarketPhase.PRE_OPEN;
        if (time.isBefore(LocalTime.NOON)) return MarketPhase.CONTINUOUS;
        if (time.isBefore(LocalTime.of(13, 0))) return MarketPhase.MIDDAY_BREAK;
        if (time.isBefore(LocalTime.of(16, 0))) return MarketPhase.CONTINUOUS;
        if (time.isBefore(LocalTime.of(16, 10))) return MarketPhase.CLOSING_AUCTION;
        return MarketPhase.CLOSED;
    }

    private static MarketPhase unitedStatesPhase(LocalTime time) {
        if (time.isBefore(LocalTime.of(4, 0))) return MarketPhase.CLOSED;
        if (time.isBefore(LocalTime.of(9, 30))) return MarketPhase.PRE_OPEN;
        if (time.isBefore(LocalTime.of(16, 0))) return MarketPhase.CONTINUOUS;
        return MarketPhase.CLOSED;
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) throw new IllegalArgumentException("exchange is required");
        String normalized = exchange.trim().toUpperCase(Locale.ROOT);
        return "CN".equals(normalized) ? "SH" : normalized;
    }
}
