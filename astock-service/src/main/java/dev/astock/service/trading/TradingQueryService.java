package dev.astock.service.trading;

import dev.astock.domain.order.OrderSide;
import dev.astock.domain.order.OrderStatus;
import dev.astock.domain.order.OrderType;
import dev.astock.service.trading.model.AccountView;
import dev.astock.service.trading.model.FillView;
import dev.astock.service.trading.model.OrderView;
import dev.astock.service.trading.model.PositionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradingQueryService {
    private static final String ORDER_SELECT = """
            SELECT o.*, s.symbol
            FROM astock_order o JOIN astock_security s ON s.security_id = o.security_id
            """;
    private final JdbcTemplate jdbc;

    public TradingQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountView account(UUID playerUuid) {
        List<AccountView> result = jdbc.query("""
                SELECT account_id, player_uuid, cash_available, cash_frozen, version
                FROM astock_account WHERE player_uuid = ?
                """, (rs, row) -> new AccountView(
                rs.getLong("account_id"), UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("cash_available"), rs.getLong("cash_frozen"), rs.getLong("version")
        ), playerUuid.toString());
        return result.stream().findFirst().orElse(new AccountView(0, playerUuid, 0, 0, 0));
    }

    public List<PositionView> positions(UUID playerUuid) {
        return jdbc.query("""
                SELECT p.security_id, s.symbol, s.name, p.quantity_total, p.quantity_available,
                       p.quantity_frozen, p.average_cost
                FROM astock_position p
                JOIN astock_account a ON a.account_id = p.account_id
                JOIN astock_security s ON s.security_id = p.security_id
                WHERE a.player_uuid = ? AND p.quantity_total > 0
                ORDER BY s.symbol
                """, (rs, row) -> new PositionView(
                rs.getInt("security_id"), rs.getString("symbol"), rs.getString("name"),
                rs.getLong("quantity_total"), rs.getLong("quantity_available"),
                rs.getLong("quantity_frozen"), rs.getLong("average_cost")
        ), playerUuid.toString());
    }

    public List<OrderView> orders(UUID playerUuid, int limit) {
        return jdbc.query(ORDER_SELECT + """
                JOIN astock_account a ON a.account_id = o.account_id
                WHERE a.player_uuid = ? ORDER BY o.created_at DESC LIMIT ?
                """, this::mapOrder, playerUuid.toString(), Math.min(Math.max(limit, 1), 200));
    }

    public Optional<OrderView> order(String orderId) {
        return jdbc.query(ORDER_SELECT + " WHERE o.order_id = ?", this::mapOrder, orderId)
                .stream().findFirst();
    }

    public Optional<OrderView> orderByClientRequest(String requestId) {
        return jdbc.query(ORDER_SELECT + " WHERE o.client_request_id = ?", this::mapOrder, requestId)
                .stream().findFirst();
    }

    public List<FillView> fills(String orderId) {
        return jdbc.query("""
                SELECT f.*, s.symbol FROM astock_fill f
                JOIN astock_security s ON s.security_id = f.security_id
                WHERE f.order_id = ? ORDER BY f.created_at, f.fill_id
                """, (rs, row) -> new FillView(
                rs.getString("fill_id"), rs.getString("order_id"), rs.getString("symbol"),
                rs.getLong("quantity"), rs.getLong("price"), rs.getLong("notional"),
                rs.getLong("fee"), rs.getLong("quote_sequence"), rs.getString("quote_source"),
                rs.getLong("quote_timestamp"), instant(rs.getTimestamp("created_at"))
        ), orderId);
    }

    private OrderView mapOrder(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Object limit = rs.getObject("limit_price");
        Object cap = rs.getObject("price_cap");
        return new OrderView(
                rs.getString("order_id"), rs.getString("client_request_id"), rs.getLong("account_id"),
                rs.getInt("security_id"), rs.getString("symbol"), OrderSide.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("order_type")), rs.getLong("quantity"),
                rs.getLong("remaining_quantity"), limit == null ? null : rs.getLong("limit_price"),
                cap == null ? null : rs.getLong("price_cap"), rs.getLong("accepted_quote_sequence"),
                OrderStatus.valueOf(rs.getString("status")), rs.getString("reject_reason"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
