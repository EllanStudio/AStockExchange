package dev.astock.service.security;

import dev.astock.domain.rule.TradingRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class SecurityCatalog {
    private static final String ACTIVE_QUERY = """
            SELECT s.security_id, s.symbol, s.name, s.exchange, s.board, s.currency,
                   s.enabled, s.frozen_reason,
                   r.effective_from, r.effective_to, r.lot_size, r.tick_size,
                   r.price_limit_bps, r.t_plus_days, r.enabled AS rule_enabled
            FROM astock_security s
            JOIN astock_security_rule r ON r.security_id = s.security_id
            WHERE r.effective_from <= ? AND (r.effective_to IS NULL OR r.effective_to >= ?)
            """;

    private final JdbcTemplate jdbc;

    public SecurityCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SecurityInfo> findEnabled(LocalDate date) {
        return jdbc.query(ACTIVE_QUERY + " AND s.enabled = TRUE AND r.enabled = TRUE ORDER BY s.security_id",
                this::map, Date.valueOf(date), Date.valueOf(date));
    }

    public List<SecurityInfo> findEnabled(String exchange, LocalDate date) {
        return jdbc.query(ACTIVE_QUERY + """
                 AND s.enabled = TRUE AND r.enabled = TRUE AND s.exchange = ?
                 ORDER BY s.security_id
                """, this::map, Date.valueOf(date), Date.valueOf(date),
                exchange.trim().toUpperCase(Locale.ROOT));
    }

    public Optional<SecurityInfo> findBySymbol(String rawSymbol, LocalDate date) {
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        List<SecurityInfo> result = jdbc.query(ACTIVE_QUERY + " AND s.symbol = ? ORDER BY r.effective_from DESC",
                this::map, Date.valueOf(date), Date.valueOf(date), symbol);
        return result.stream().findFirst();
    }

    public Optional<SecurityInfo> findById(int securityId, LocalDate date) {
        List<SecurityInfo> result = jdbc.query(ACTIVE_QUERY + " AND s.security_id = ? ORDER BY r.effective_from DESC",
                this::map, Date.valueOf(date), Date.valueOf(date), securityId);
        return result.stream().findFirst();
    }

    public void freeze(String symbol, String reason) {
        int changed = jdbc.update("""
                UPDATE astock_security SET enabled = FALSE, frozen_reason = ?, updated_at = CURRENT_TIMESTAMP
                WHERE symbol = ?
                """, reason, symbol.toUpperCase(Locale.ROOT));
        if (changed == 0) throw new IllegalArgumentException("unknown security: " + symbol);
    }

    public void unfreeze(String symbol) {
        int changed = jdbc.update("""
                UPDATE astock_security SET enabled = TRUE, frozen_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE symbol = ?
                """, symbol.toUpperCase(Locale.ROOT));
        if (changed == 0) throw new IllegalArgumentException("unknown security: " + symbol);
    }

    private SecurityInfo map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        LocalDate from = rs.getDate("effective_from").toLocalDate();
        Date toDate = rs.getDate("effective_to");
        LocalDate to = toDate == null ? null : toDate.toLocalDate();
        var rule = new TradingRule(
                rs.getInt("security_id"), from, to,
                rs.getLong("lot_size"), rs.getLong("tick_size"),
                rs.getInt("price_limit_bps"), rs.getInt("t_plus_days"),
                rs.getBoolean("rule_enabled")
        );
        return new SecurityInfo(
                rs.getInt("security_id"), rs.getString("symbol"), rs.getString("name"),
                rs.getString("exchange"), rs.getString("board"), rs.getString("currency"),
                rs.getBoolean("enabled"), rs.getString("frozen_reason"), rule
        );
    }
}
