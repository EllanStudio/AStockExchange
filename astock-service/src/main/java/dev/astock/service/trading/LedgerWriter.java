package dev.astock.service.trading;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LedgerWriter {
    private final JdbcTemplate jdbc;

    public LedgerWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String transactionId, String referenceType, String referenceId, List<LedgerLine> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("ledger transaction must have lines");
        Map<String, Long> totals = new HashMap<>();
        for (LedgerLine line : lines) {
            totals.merge(line.currency(), line.amount(), Math::addExact);
        }
        totals.forEach((currency, total) -> {
            if (total != 0) throw new IllegalStateException("unbalanced ledger " + currency + ": " + total);
        });
        jdbc.batchUpdate("""
                INSERT INTO astock_ledger_entry (
                    transaction_id, account_type, account_id, currency, amount,
                    entry_type, reference_type, reference_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, lines, lines.size(), (statement, line) -> {
            statement.setString(1, transactionId);
            statement.setString(2, line.accountType());
            statement.setString(3, line.accountId());
            statement.setString(4, line.currency());
            statement.setLong(5, line.amount());
            statement.setString(6, line.entryType());
            statement.setString(7, referenceType);
            statement.setString(8, referenceId);
        });
    }

    public record LedgerLine(
            String accountType,
            String accountId,
            String currency,
            long amount,
            String entryType
    ) {
    }
}
