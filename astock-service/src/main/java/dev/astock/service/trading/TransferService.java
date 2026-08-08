package dev.astock.service.trading;

import dev.astock.service.config.AStockProperties;
import dev.astock.service.trading.LedgerWriter.LedgerLine;
import dev.astock.service.trading.model.TransferDirection;
import dev.astock.service.trading.model.TransferStatus;
import dev.astock.service.trading.model.TransferView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {
    private static final String CASH = "GAME_COIN_MINOR";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LedgerWriter ledger;
    private final OutboxWriter outbox;
    private final AStockProperties properties;

    public TransferService(JdbcTemplate jdbc, TransactionTemplate transactions,
                           LedgerWriter ledger, OutboxWriter outbox, AStockProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ledger = ledger;
        this.outbox = outbox;
        this.properties = properties;
    }

    public TransferView begin(String clientRequestId, UUID playerUuid,
                              TransferDirection direction, long amount) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 64) {
            throw new IllegalArgumentException("invalid clientRequestId");
        }
        if (amount <= 0) throw new IllegalArgumentException("transfer amount must be positive");
        if (amount > properties.economy().maxTransferAmount()) {
            throw new TradeRejectedException("TRANSFER_LIMIT", "transfer amount exceeds the configured limit");
        }
        String id = transactions.execute(status -> {
            Optional<TransferView> duplicate = byClientRequest(clientRequestId);
            if (duplicate.isPresent()) return duplicate.get().transferId();
            long accountId = lockOrCreateAccount(playerUuid);
            String transferId = UUID.randomUUID().toString();
            TransferStatus initial = TransferStatus.REQUESTED;
            if (direction == TransferDirection.WITHDRAW) {
                AccountCash account = lockAccount(accountId);
                if (account.available() < amount) {
                    throw new TradeRejectedException("INSUFFICIENT_CASH", "insufficient broker cash for withdrawal");
                }
                jdbc.update("""
                        UPDATE astock_account SET cash_available = cash_available - ?, version = version + 1,
                            updated_at = CURRENT_TIMESTAMP WHERE account_id = ?
                        """, amount, accountId);
                initial = TransferStatus.BROKER_DEBITED;
                ledger.write(UUID.randomUUID().toString(), "TRANSFER", transferId, List.of(
                        new LedgerLine("PLAYER_CASH_AVAILABLE", Long.toString(accountId), CASH, -amount, "WITHDRAW_RESERVE"),
                        new LedgerLine("TRANSFER_HOLD", transferId, CASH, amount, "WITHDRAW_RESERVE")
                ));
            }
            jdbc.update("""
                    INSERT INTO astock_economy_transfer (
                        transfer_id, client_request_id, player_uuid, direction, amount, status
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, transferId, clientRequestId, playerUuid.toString(), direction.name(), amount, initial.name());
            outbox.append("TRANSFER", transferId, "TRANSFER_" + initial.name(),
                    "{\"transferId\":\"" + transferId + "\"}");
            return transferId;
        });
        return require(id);
    }

    /** Called after the Paper-side economy mutation has durably reached its journal. */
    public TransferView confirmEconomyMutation(String transferId) {
        transactions.executeWithoutResult(status -> {
            TransferView transfer = lockTransfer(transferId);
            if (transfer.status() == TransferStatus.COMPLETED) return;
            long accountId = lockOrCreateAccount(transfer.playerUuid());
            if (transfer.direction() == TransferDirection.DEPOSIT) {
                if (transfer.status() != TransferStatus.REQUESTED
                        && transfer.status() != TransferStatus.ECONOMY_DEBITED) {
                    throw new IllegalStateException("deposit cannot be confirmed from " + transfer.status());
                }
                lockAccount(accountId);
                jdbc.update("""
                        UPDATE astock_economy_transfer SET status = 'ECONOMY_DEBITED', updated_at = CURRENT_TIMESTAMP
                        WHERE transfer_id = ?
                        """, transferId);
                jdbc.update("""
                        UPDATE astock_account SET cash_available = cash_available + ?, version = version + 1,
                            updated_at = CURRENT_TIMESTAMP WHERE account_id = ?
                        """, transfer.amount(), accountId);
                ledger.write(UUID.randomUUID().toString(), "TRANSFER", transferId, List.of(
                        new LedgerLine("EXTERNAL_ECONOMY", transfer.playerUuid().toString(), CASH,
                                -transfer.amount(), "DEPOSIT"),
                        new LedgerLine("PLAYER_CASH_AVAILABLE", Long.toString(accountId), CASH,
                                transfer.amount(), "DEPOSIT")
                ));
            } else {
                if (transfer.status() != TransferStatus.BROKER_DEBITED
                        && transfer.status() != TransferStatus.ECONOMY_CREDITED) {
                    throw new IllegalStateException("withdrawal cannot be confirmed from " + transfer.status());
                }
                jdbc.update("""
                        UPDATE astock_economy_transfer SET status = 'ECONOMY_CREDITED', updated_at = CURRENT_TIMESTAMP
                        WHERE transfer_id = ?
                        """, transferId);
                ledger.write(UUID.randomUUID().toString(), "TRANSFER", transferId, List.of(
                        new LedgerLine("TRANSFER_HOLD", transferId, CASH, -transfer.amount(), "WITHDRAW"),
                        new LedgerLine("EXTERNAL_ECONOMY", transfer.playerUuid().toString(), CASH,
                                transfer.amount(), "WITHDRAW")
                ));
            }
            jdbc.update("""
                    UPDATE astock_economy_transfer SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
                    WHERE transfer_id = ?
                    """, transferId);
            outbox.append("TRANSFER", transferId, "TRANSFER_COMPLETED",
                    "{\"transferId\":\"" + transferId + "\"}");
        });
        return require(transferId);
    }

    public TransferView compensate(String transferId, String reason) {
        transactions.executeWithoutResult(status -> {
            TransferView transfer = lockTransfer(transferId);
            if (transfer.status() == TransferStatus.COMPLETED || transfer.status() == TransferStatus.COMPENSATED
                    || transfer.status() == TransferStatus.FAILED) return;
            if (transfer.direction() == TransferDirection.DEPOSIT) {
                if (transfer.status() != TransferStatus.REQUESTED) {
                    throw new IllegalStateException("ambiguous deposit requires reconciliation");
                }
                jdbc.update("""
                        UPDATE astock_economy_transfer SET status = 'FAILED', failure_reason = ?,
                            updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ?
                        """, reason, transferId);
            } else {
                if (transfer.status() != TransferStatus.BROKER_DEBITED) {
                    throw new IllegalStateException("withdrawal cannot be compensated from " + transfer.status());
                }
                long accountId = lockOrCreateAccount(transfer.playerUuid());
                lockAccount(accountId);
                jdbc.update("""
                        UPDATE astock_account SET cash_available = cash_available + ?, version = version + 1,
                            updated_at = CURRENT_TIMESTAMP WHERE account_id = ?
                        """, transfer.amount(), accountId);
                ledger.write(UUID.randomUUID().toString(), "TRANSFER", transferId, List.of(
                        new LedgerLine("TRANSFER_HOLD", transferId, CASH, -transfer.amount(), "COMPENSATE"),
                        new LedgerLine("PLAYER_CASH_AVAILABLE", Long.toString(accountId), CASH,
                                transfer.amount(), "COMPENSATE")
                ));
                jdbc.update("""
                        UPDATE astock_economy_transfer SET status = 'COMPENSATED', failure_reason = ?,
                            updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ?
                        """, reason, transferId);
            }
        });
        return require(transferId);
    }

    public TransferView require(String transferId) {
        return byId(transferId).orElseThrow(() -> new IllegalArgumentException("unknown transfer: " + transferId));
    }

    public List<TransferView> pending(UUID playerUuid) {
        return jdbc.query("""
                SELECT * FROM astock_economy_transfer
                WHERE player_uuid = ? AND status NOT IN ('COMPLETED', 'COMPENSATED', 'FAILED')
                ORDER BY created_at
                """, this::map, playerUuid.toString());
    }

    private Optional<TransferView> byClientRequest(String id) {
        return jdbc.query("SELECT * FROM astock_economy_transfer WHERE client_request_id = ?", this::map, id)
                .stream().findFirst();
    }

    private Optional<TransferView> byId(String id) {
        return jdbc.query("SELECT * FROM astock_economy_transfer WHERE transfer_id = ?", this::map, id)
                .stream().findFirst();
    }

    private TransferView lockTransfer(String id) {
        return jdbc.query("SELECT * FROM astock_economy_transfer WHERE transfer_id = ? FOR UPDATE", this::map, id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("unknown transfer: " + id));
    }

    private long lockOrCreateAccount(UUID playerUuid) {
        List<Long> ids = jdbc.queryForList("SELECT account_id FROM astock_account WHERE player_uuid = ?",
                Long.class, playerUuid.toString());
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO astock_account (player_uuid) VALUES (?)", playerUuid.toString());
            ids = jdbc.queryForList("SELECT account_id FROM astock_account WHERE player_uuid = ?",
                    Long.class, playerUuid.toString());
        }
        lockAccount(ids.getFirst());
        return ids.getFirst();
    }

    private AccountCash lockAccount(long accountId) {
        return jdbc.query("""
                SELECT cash_available, cash_frozen FROM astock_account WHERE account_id = ? FOR UPDATE
                """, (rs, row) -> new AccountCash(rs.getLong(1), rs.getLong(2)), accountId)
                .stream().findFirst().orElseThrow();
    }

    private TransferView map(ResultSet rs, int row) throws SQLException {
        return new TransferView(
                rs.getString("transfer_id"), rs.getString("client_request_id"),
                UUID.fromString(rs.getString("player_uuid")),
                TransferDirection.valueOf(rs.getString("direction")), rs.getLong("amount"),
                TransferStatus.valueOf(rs.getString("status")), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private record AccountCash(long available, long frozen) {
    }
}
