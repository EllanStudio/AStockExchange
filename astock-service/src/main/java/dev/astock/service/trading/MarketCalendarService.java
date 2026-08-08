package dev.astock.service.trading;

import dev.astock.domain.rule.MarketPhase;
import dev.astock.domain.rule.TradingSessionPolicy;
import dev.astock.service.config.AStockProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

@Service
public class MarketCalendarService {
    private final JdbcTemplate jdbc;
    private final TradingSessionPolicy sessions;
    private final AStockProperties properties;
    private final Clock clock;
    private final ZoneId zone;

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
        this.zone = ZoneId.of(properties.market().timezone());
    }

    public LocalDate tradeDate() {
        return Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate();
    }

    public MarketPhase currentPhase() {
        LocalDateTime dateTime = LocalDateTime.ofInstant(clock.instant(), zone);
        return sessions.phaseAt(dateTime, holidaysAround(dateTime.toLocalDate()));
    }

    public boolean executionOpen() {
        return !properties.market().enforceSessions() || currentPhase() == MarketPhase.CONTINUOUS;
    }

    public LocalDate settlementDate(LocalDate purchaseDate, int tPlusDays) {
        LocalDate result = purchaseDate;
        Set<LocalDate> holidays = holidaysAround(purchaseDate.plusDays(Math.max(7, tPlusDays * 3L)));
        for (int day = 0; day < tPlusDays; day++) result = sessions.nextTradingDay(result, holidays);
        return result;
    }

    private Set<LocalDate> holidaysAround(LocalDate center) {
        var result = new HashSet<LocalDate>();
        jdbc.queryForList("""
                SELECT trade_date FROM astock_market_calendar
                WHERE is_trading_day = FALSE AND trade_date BETWEEN ? AND ?
                """, java.sql.Date.class,
                java.sql.Date.valueOf(center.minusDays(14)), java.sql.Date.valueOf(center.plusDays(45)))
                .forEach(date -> result.add(date.toLocalDate()));
        return result;
    }
}
