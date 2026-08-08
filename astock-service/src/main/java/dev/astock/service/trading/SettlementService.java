package dev.astock.service.trading;

import dev.astock.service.trading.LedgerWriter.LedgerLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class SettlementService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MarketCalendarService calendar;
    private final OrderCommandBus commandBus;
    private final LedgerWriter ledger;

    public SettlementService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            MarketCalendarService calendar,
            OrderCommandBus commandBus,
            LedgerWriter ledger
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.calendar = calendar;
        this.commandBus = commandBus;
        this.ledger = ledger;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void settleOnStartup() {
        commandBus.submit(this::settleDueLots);
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    public void scheduledSettlement() {
        commandBus.submit(this::settleDueLots);
    }

    public int settleDueLots() {
        LocalDate tradeDate = calendar.tradeDate();
        List<DuePosition> due = jdbc.query("""
                SELECT account_id, security_id, SUM(remaining_quantity) AS quantity
                FROM astock_position_lot
                WHERE settled = FALSE AND unlock_trade_date <= ? AND remaining_quantity > 0
                GROUP BY account_id, security_id
                """, (rs, row) -> new DuePosition(rs.getLong(1), rs.getInt(2), rs.getLong(3)),
                Date.valueOf(tradeDate));
        for (DuePosition position : due) {
            transactions.executeWithoutResult(status -> settle(position, tradeDate));
        }
        if (!due.isEmpty()) LOGGER.info("Settled {} due position lots for {}", due.size(), tradeDate);
        return due.size();
    }

    private void settle(DuePosition due, LocalDate date) {
        List<Long> lockedLots = jdbc.query("""
                SELECT remaining_quantity FROM astock_position_lot
                WHERE account_id = ? AND security_id = ? AND settled = FALSE
                  AND unlock_trade_date <= ? FOR UPDATE
                """, (rs, row) -> rs.getLong(1), due.accountId(), due.securityId(), Date.valueOf(date));
        long amount = lockedLots.stream().reduce(0L, Math::addExact);
        if (amount <= 0) return;
        jdbc.queryForObject("""
                SELECT version FROM astock_position WHERE account_id = ? AND security_id = ? FOR UPDATE
                """, Long.class, due.accountId(), due.securityId());
        jdbc.update("""
                UPDATE astock_position SET quantity_available = quantity_available + ?, version = version + 1
                WHERE account_id = ? AND security_id = ?
                """, amount, due.accountId(), due.securityId());
        jdbc.update("""
                UPDATE astock_position_lot SET settled = TRUE
                WHERE account_id = ? AND security_id = ? AND settled = FALSE AND unlock_trade_date <= ?
                """, due.accountId(), due.securityId(), Date.valueOf(date));
        String currency = "SHARES:" + due.securityId();
        ledger.write(UUID.randomUUID().toString(), "SETTLEMENT",
                due.accountId() + ":" + due.securityId() + ":" + date, List.of(
                        new LedgerLine("PLAYER_SHARES_LOCKED", Long.toString(due.accountId()), currency, -amount, "T_PLUS_ONE"),
                        new LedgerLine("PLAYER_SHARES_AVAILABLE", Long.toString(due.accountId()), currency, amount, "T_PLUS_ONE")
                ));
    }

    private record DuePosition(long accountId, int securityId, long quantity) {
    }
}
