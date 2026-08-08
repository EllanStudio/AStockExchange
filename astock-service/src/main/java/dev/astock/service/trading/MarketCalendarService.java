package dev.astock.service.trading;

import dev.astock.domain.rule.MarketPhase;
import dev.astock.domain.rule.TradingSessionPolicy;
import dev.astock.service.config.AStockProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketCalendarService {
    private static final Duration HOLIDAY_CACHE_TTL = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    private final TradingSessionPolicy sessions;
    private final AStockProperties properties;
    private final Clock clock;
    private final ZoneId defaultZone;
    private final Map<HolidayWindow, CachedHolidays> holidayCache = new ConcurrentHashMap<>();

    public MarketCalendarService(
            JdbcTemplate jdbc,
            TradingSessionPolicy sessions,
            AStockProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        this.defaultZone = ZoneId.of(properties.market().timezone());
    }

    public LocalDate tradeDate() {
        return Instant.ofEpochMilli(clock.millis()).atZone(defaultZone).toLocalDate();
    }

    public LocalDate tradeDate(String exchange) {
        return clock.instant().atZone(sessions.zoneFor(exchange)).toLocalDate();
    }

    public MarketPhase currentPhase() {
        return currentPhase("SH");
    }

    public MarketPhase currentPhase(String exchange) {
        LocalDate tradeDate = tradeDate(exchange);
        return sessions.phaseAt(exchange, clock.instant(), holidaysAround(exchange, tradeDate));
    }

    public boolean executionOpen() {
        return executionOpen("SH");
    }

    public boolean executionOpen(String exchange) {
        return !properties.market().enforceSessions() || currentPhase(exchange) == MarketPhase.CONTINUOUS;
    }

    public LocalDate settlementDate(LocalDate purchaseDate, int tPlusDays) {
        return settlementDate("SH", purchaseDate, tPlusDays);
    }

    public LocalDate settlementDate(String exchange, LocalDate purchaseDate, int tPlusDays) {
        LocalDate result = purchaseDate;
        Set<LocalDate> holidays = holidaysAround(
                exchange, purchaseDate.plusDays(Math.max(7, tPlusDays * 3L))
        );
        for (int day = 0; day < tPlusDays; day++) result = sessions.nextTradingDay(result, holidays);
        return result;
    }

    private Set<LocalDate> holidaysAround(String exchange, LocalDate center) {
        HolidayWindow window = new HolidayWindow(marketCode(exchange), center);
        CachedHolidays cached = holidayCache.get(window);
        if (cached != null && clock.millis() < cached.expiresAt()) return cached.holidays();
        var result = new HashSet<LocalDate>();
        jdbc.queryForList("""
                SELECT trade_date FROM astock_exchange_calendar
                WHERE market_code = ? AND is_trading_day = FALSE AND trade_date BETWEEN ? AND ?
                """, java.sql.Date.class,
                window.marketCode(), java.sql.Date.valueOf(center.minusDays(14)),
                java.sql.Date.valueOf(center.plusDays(45)))
                .forEach(date -> result.add(date.toLocalDate()));
        Set<LocalDate> holidays = Set.copyOf(result);
        holidayCache.put(window, new CachedHolidays(
                clock.millis() + HOLIDAY_CACHE_TTL.toMillis(), holidays
        ));
        return holidays;
    }

    private static String marketCode(String exchange) {
        String normalized = exchange.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SH", "SZ", "CN" -> "CN";
            case "HK", "US" -> normalized;
            default -> throw new IllegalArgumentException("unsupported exchange: " + exchange);
        };
    }

    private record HolidayWindow(String marketCode, LocalDate center) {
    }

    private record CachedHolidays(long expiresAt, Set<LocalDate> holidays) {
    }
}
