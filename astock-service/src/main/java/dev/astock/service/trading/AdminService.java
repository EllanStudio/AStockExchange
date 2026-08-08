package dev.astock.service.trading;

import dev.astock.service.market.QuoteCoordinator;
import dev.astock.service.security.SecurityCatalog;
import dev.astock.service.security.SecurityInfo;
import dev.astock.service.trading.model.AccountView;
import dev.astock.service.trading.model.FillView;
import dev.astock.service.trading.model.OrderView;
import dev.astock.service.trading.model.PositionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final QuoteCoordinator quotes;
    private final MarketCalendarService calendar;
    private final SecurityCatalog catalog;
    private final TradingEngine engine;
    private final TradingQueryService queries;
    private final RiskService risk;

    public AdminService(JdbcTemplate jdbc, QuoteCoordinator quotes, MarketCalendarService calendar,
                        SecurityCatalog catalog, TradingEngine engine, TradingQueryService queries,
                        RiskService risk) {
        this.jdbc = jdbc;
        this.quotes = quotes;
        this.calendar = calendar;
        this.catalog = catalog;
        this.engine = engine;
        this.queries = queries;
        this.risk = risk;
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "UP");
        result.put("database", jdbc.queryForObject("SELECT 1", Integer.class) != null ? "UP" : "DOWN");
        result.put("marketPhase", calendar.currentPhase());
        result.put("executionOpen", calendar.executionOpen());
        result.put("quotes", quotes.status());
        result.put("openOrders", jdbc.queryForObject("""
                SELECT COUNT(*) FROM astock_order WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, Integer.class));
        result.put("pendingTransfers", jdbc.queryForObject("""
                SELECT COUNT(*) FROM astock_economy_transfer
                WHERE status NOT IN ('COMPLETED', 'COMPENSATED', 'FAILED')
                """, Integer.class));
        return result;
    }

    public void freeze(String symbol, String reason) {
        SecurityInfo security = catalog.findBySymbol(symbol, calendar.tradeDate())
                .orElseThrow(() -> new IllegalArgumentException("unknown security: " + symbol));
        catalog.freeze(security.symbol(), reason);
        engine.cancelOpenOrdersForSecurity(security.id(), "SECURITY_FROZEN:" + reason);
    }

    public void unfreeze(String symbol) {
        catalog.unfreeze(symbol);
    }

    public ReconcileResult reconcile(UUID playerUuid) {
        AccountView account = queries.account(playerUuid);
        if (account.accountId() == 0) {
            return new ReconcileResult(playerUuid, true, 0, 0, List.of(), "account does not exist");
        }
        long projectedCash = Math.addExact(account.cashAvailable(), account.cashFrozen());
        Long ledgerCash = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM astock_ledger_entry
                WHERE account_id = ? AND account_type LIKE 'PLAYER_CASH%'
                """, Long.class, Long.toString(account.accountId()));
        Map<Integer, Long> projectedShares = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT security_id, quantity_total FROM astock_position WHERE account_id = ?
                """, account.accountId()).forEach(row -> projectedShares.put(
                ((Number) row.get("security_id")).intValue(), ((Number) row.get("quantity_total")).longValue()));
        Map<Integer, Long> journalShares = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT currency, SUM(amount) AS amount FROM astock_ledger_entry
                WHERE account_id = ? AND currency LIKE 'SHARES:%' AND account_type LIKE 'PLAYER_SHARES%'
                GROUP BY currency
                """, Long.toString(account.accountId())).forEach(row -> {
            String currency = row.get("currency").toString();
            journalShares.put(Integer.parseInt(currency.substring("SHARES:".length())),
                    ((Number) row.get("amount")).longValue());
        });
        var securityIds = new java.util.TreeSet<Integer>();
        securityIds.addAll(projectedShares.keySet());
        securityIds.addAll(journalShares.keySet());
        List<ShareDifference> differences = securityIds.stream().map(securityId -> {
            String symbol = jdbc.queryForObject("SELECT symbol FROM astock_security WHERE security_id = ?",
                    String.class, securityId);
            long projection = projectedShares.getOrDefault(securityId, 0L);
            long journal = journalShares.getOrDefault(securityId, 0L);
            return new ShareDifference(symbol == null ? Integer.toString(securityId) : symbol,
                    projection, journal, projection - journal);
        }).filter(value -> value.difference() != 0).toList();
        long cashJournal = ledgerCash == null ? 0 : ledgerCash;
        boolean balanced = projectedCash == cashJournal && differences.isEmpty();
        return new ReconcileResult(playerUuid, balanced, projectedCash, cashJournal, differences,
                balanced ? "projections match immutable ledger" : "projection drift detected; trading should be frozen for this account");
    }

    public Map<String, Object> audit(String orderId) {
        OrderView order = queries.order(orderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown order: " + orderId));
        List<FillView> fills = queries.fills(orderId);
        List<Map<String, Object>> ledgerEntries = jdbc.queryForList("""
                SELECT entry_id, transaction_id, account_type, account_id, currency, amount,
                       entry_type, reference_type, reference_id, created_at
                FROM astock_ledger_entry WHERE reference_id = ? ORDER BY entry_id
                """, orderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("fills", fills);
        result.put("ledgerEntries", ledgerEntries);
        return result;
    }

    public RiskService.TreasurySnapshot treasury() {
        RiskService.TreasurySnapshot snapshot = risk.treasury();
        jdbc.update("""
                INSERT INTO astock_treasury_exposure (liability, treasury_balance, coverage_ratio_bps)
                VALUES (?, ?, ?)
                """, snapshot.liability(), snapshot.treasuryBalance(), snapshot.coverageRatioBps());
        return snapshot;
    }

    public record ReconcileResult(
            UUID playerUuid,
            boolean balanced,
            long projectedCash,
            long ledgerCash,
            List<ShareDifference> shareDifferences,
            String message
    ) {
    }

    public record ShareDifference(String symbol, long projected, long ledger, long difference) {
    }
}
