package dev.astock.service.trading;

import dev.astock.domain.money.ScaledMath;
import dev.astock.domain.order.OrderSide;
import dev.astock.domain.order.OrderStatus;
import dev.astock.domain.order.OrderType;
import dev.astock.domain.order.PlaceOrderCommand;
import dev.astock.domain.quote.CanonicalQuote;
import dev.astock.domain.quote.QuoteQuality;
import dev.astock.domain.quote.TradingStatus;
import dev.astock.service.config.AStockProperties;
import dev.astock.service.market.QuoteCoordinator;
import dev.astock.service.market.QuoteUpdatedEvent;
import dev.astock.service.security.SecurityCatalog;
import dev.astock.service.security.SecurityInfo;
import dev.astock.service.trading.LedgerWriter.LedgerLine;
import dev.astock.service.trading.model.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(TradingEngine.class);
    private static final String CASH_CURRENCY = "GAME_COIN_MINOR";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecurityCatalog catalog;
    private final QuoteCoordinator quotes;
    private final TradingQueryService queryService;
    private final LedgerWriter ledger;
    private final OutboxWriter outbox;
    private final RiskService risk;
    private final MarketCalendarService calendar;
    private final OrderCommandBus commandBus;
    private final AStockProperties properties;
    private final Clock clock;

    public TradingEngine(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SecurityCatalog catalog,
            QuoteCoordinator quotes,
            TradingQueryService queryService,
            LedgerWriter ledger,
            OutboxWriter outbox,
            RiskService risk,
            MarketCalendarService calendar,
            OrderCommandBus commandBus,
            AStockProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.catalog = catalog;
        this.quotes = quotes;
        this.queryService = queryService;
        this.ledger = ledger;
        this.outbox = outbox;
        this.risk = risk;
        this.calendar = calendar;
        this.commandBus = commandBus;
        this.properties = properties;
        this.clock = clock;
    }

    public OrderView place(PlaceOrderCommand command) {
        Placement placement = transactions.execute(status -> placeInTransaction(command));
        if (placement == null) throw new IllegalStateException("order transaction returned no result");
        if (placement.created()) {
            quotes.refreshDetail(command.symbol()).exceptionally(exception -> {
                LOGGER.warn("Pre-execution refresh failed for {}", command.symbol(), exception);
                return null;
            });
        }
        return queryService.order(placement.orderId())
                .orElseThrow(() -> new IllegalStateException("created order disappeared"));
    }

    public OrderView cancel(UUID playerUuid, String orderId) {
        transactions.executeWithoutResult(status -> cancelInTransaction(playerUuid, orderId, "PLAYER_CANCELED", false));
        return queryService.order(orderId).orElseThrow(() -> new IllegalArgumentException("unknown order"));
    }

    public void cancelOpenOrdersForSecurity(int securityId, String reason) {
        List<String> ids = jdbc.queryForList("""
                SELECT order_id FROM astock_order
                WHERE security_id = ? AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                ORDER BY created_at
                """, String.class, securityId);
        for (String id : ids) {
            transactions.executeWithoutResult(status -> cancelInTransaction(null, id, reason, true));
        }
    }

    @EventListener
    public void quoteUpdated(QuoteUpdatedEvent event) {
        if (event.executable()) {
            commandBus.submit(() -> matchQuote(event.quote()));
        } else if (calendar.executionOpen() && needsDetailRefresh(event.quote())) {
            // Bulk overview feeds often do not carry a book. Use them only as a
            // cheap trigger, then confirm the order against a fresh detail book.
            quotes.refreshDetail(event.quote().symbol()).exceptionally(exception -> {
                LOGGER.debug("Triggered detail refresh failed for {}", event.quote().symbol(), exception);
                return null;
            });
        }
    }

    public void matchQuote(CanonicalQuote quote) {
        if (!calendar.executionOpen() || !isExecutableNow(quote)) return;
        List<String> ids = jdbc.queryForList("""
                SELECT order_id FROM astock_order
                WHERE security_id = ? AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                  AND accepted_quote_sequence < ?
                ORDER BY created_at, order_id
                """, String.class, quote.securityId(), quote.sequence());
        long buyLiquidity = quote.ask1Volume();
        long sellLiquidity = quote.bid1Volume();
        for (String id : ids) {
            long available = orderSide(id) == OrderSide.BUY ? buyLiquidity : sellLiquidity;
            if (available <= 0) continue;
            try {
                Long filled = transactions.execute(status -> fillOne(id, quote, available));
                long quantity = filled == null ? 0 : filled;
                if (orderSide(id) == OrderSide.BUY) buyLiquidity -= quantity;
                else sellLiquidity -= quantity;
            } catch (TradeRejectedException rejection) {
                LOGGER.info("Order {} was not matched: {}", id, rejection.getMessage());
            } catch (RuntimeException exception) {
                LOGGER.error("Order {} matching failed and was rolled back", id, exception);
            }
        }
    }

    private Placement placeInTransaction(PlaceOrderCommand command) {
        Optional<OrderView> duplicate = queryService.orderByClientRequest(command.clientRequestId());
        if (duplicate.isPresent()) return new Placement(duplicate.get().orderId(), false);

        LocalDate tradeDate = calendar.tradeDate();
        SecurityInfo security = catalog.findBySymbol(command.symbol(), tradeDate)
                .orElseThrow(() -> new TradeRejectedException("SECURITY_UNKNOWN", "unknown security"));
        if (!security.enabled()) throw new TradeRejectedException("SECURITY_FROZEN", "security is frozen");
        security.rule().validateOrder(command.quantity(), command.limitPrice());

        CanonicalQuote quote = quotes.current(security.symbol())
                .orElseThrow(() -> new TradeRejectedException("QUOTE_MISSING", "no market quote is available"));
        ensureDisplayQuote(quote);
        if (command.limitPrice() != null) {
            long lowerLimit = alignDown(ScaledMath.subtractBps(quote.previousClose(),
                    security.rule().priceLimitBps()), security.rule().tickSize());
            long upperLimit = alignUp(ScaledMath.addBps(quote.previousClose(),
                    security.rule().priceLimitBps()), security.rule().tickSize());
            if (command.limitPrice() < lowerLimit || command.limitPrice() > upperLimit) {
                throw new TradeRejectedException("DAILY_PRICE_LIMIT",
                        "limit price is outside the active daily price band");
            }
        }
        long accountId = lockOrCreateAccount(command.playerUuid());

        long anchor = command.side() == OrderSide.BUY
                ? positiveOr(quote.ask1Price(), quote.lastPrice())
                : positiveOr(quote.bid1Price(), quote.lastPrice());
        long priceCap;
        if (command.type() == OrderType.LIMIT) {
            priceCap = command.limitPrice();
        } else if (command.side() == OrderSide.BUY) {
            priceCap = alignUp(ScaledMath.addBps(anchor, properties.pricing().marketOrderPriceCapBps()),
                    security.rule().tickSize());
        } else {
            priceCap = alignDown(ScaledMath.subtractBps(anchor, properties.pricing().marketOrderPriceCapBps()),
                    security.rule().tickSize());
        }
        long proposedNotional = ScaledMath.notionalCash(command.quantity(), priceCap,
                properties.pricing().gameCoinsPerCny());
        if (command.type() == OrderType.MARKET
                && proposedNotional > properties.risk().trading().marketOrderMaxNotional()) {
            throw new TradeRejectedException("MARKET_NOTIONAL", "market order notional exceeds the configured limit");
        }
        risk.checkPlacement(accountId, security.id(), security.symbol(), command.side(), proposedNotional, tradeDate);

        String orderId = UUID.randomUUID().toString();
        long reservedCash = 0;
        long reservedQuantity = 0;
        if (command.side() == OrderSide.BUY) {
            long fee = ScaledMath.fee(proposedNotional, properties.pricing().feeBps(), properties.pricing().minimumFee());
            reservedCash = ScaledMath.safeTotal(proposedNotional, fee);
            AccountRow account = lockAccount(accountId);
            if (account.cashAvailable() < reservedCash) {
                throw new TradeRejectedException("INSUFFICIENT_CASH", "insufficient broker cash");
            }
            jdbc.update("""
                    UPDATE astock_account
                    SET cash_available = cash_available - ?, cash_frozen = cash_frozen + ?,
                        version = version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE account_id = ?
                    """, reservedCash, reservedCash, accountId);
            ledger.write(UUID.randomUUID().toString(), "ORDER", orderId, List.of(
                    line("PLAYER_CASH_AVAILABLE", accountId, CASH_CURRENCY, -reservedCash, "FREEZE"),
                    line("PLAYER_CASH_FROZEN", accountId, CASH_CURRENCY, reservedCash, "FREEZE")
            ));
        } else {
            PositionRow position = lockPosition(accountId, security.id())
                    .orElseThrow(() -> new TradeRejectedException("INSUFFICIENT_SHARES", "no position to sell"));
            if (position.quantityAvailable() < command.quantity()) {
                throw new TradeRejectedException("T_PLUS_ONE_OR_FROZEN", "insufficient T+1 available shares");
            }
            reservedQuantity = command.quantity();
            jdbc.update("""
                    UPDATE astock_position
                    SET quantity_available = quantity_available - ?, quantity_frozen = quantity_frozen + ?,
                        version = version + 1
                    WHERE account_id = ? AND security_id = ?
                    """, reservedQuantity, reservedQuantity, accountId, security.id());
            String shares = sharesCurrency(security.id());
            ledger.write(UUID.randomUUID().toString(), "ORDER", orderId, List.of(
                    line("PLAYER_SHARES_AVAILABLE", accountId, shares, -reservedQuantity, "FREEZE"),
                    line("PLAYER_SHARES_FROZEN", accountId, shares, reservedQuantity, "FREEZE")
            ));
        }

        jdbc.update("""
                INSERT INTO astock_order (
                    order_id, client_request_id, account_id, security_id, side, order_type,
                    quantity, remaining_quantity, limit_price, price_cap, reserved_cash,
                    reserved_quantity, accepted_quote_sequence, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED')
                """, orderId, command.clientRequestId(), accountId, security.id(), command.side().name(),
                command.type().name(), command.quantity(), command.quantity(), command.limitPrice(), priceCap,
                reservedCash, reservedQuantity, quote.sequence());
        outbox.append("ORDER", orderId, "ORDER_ACCEPTED", "{\"orderId\":\"" + orderId + "\"}");
        return new Placement(orderId, true);
    }

    private long fillOne(String orderId, CanonicalQuote quote, long visibleLiquidity) {
        DbOrder order = lockOrder(orderId).orElse(null);
        if (order == null || order.status().isTerminal() || quote.sequence() <= order.acceptedSequence()) return 0;
        if (!isExecutableNow(quote)) return 0;
        SecurityInfo security = catalog.findById(order.securityId(), calendar.tradeDate())
                .orElseThrow(() -> new TradeRejectedException("RULE_MISSING", "no active trading rule"));
        if (!security.enabled()) return 0;

        int totalSpread = Math.addExact(properties.pricing().baseSpreadBps(),
                properties.pricing().additionalSlippageBps());
        long executionPrice;
        if (order.side() == OrderSide.BUY) {
            if (order.type() == OrderType.LIMIT && quote.ask1Price() > order.limitPrice()) return 0;
            executionPrice = alignUp(ScaledMath.addBps(quote.ask1Price(), totalSpread), security.rule().tickSize());
            if (executionPrice > order.priceCap()) {
                if (order.type() == OrderType.MARKET) rejectOrder(order, "MARKET_PRICE_CAP");
                return 0;
            }
        } else {
            if (order.type() == OrderType.LIMIT && quote.bid1Price() < order.limitPrice()) return 0;
            executionPrice = alignDown(ScaledMath.subtractBps(quote.bid1Price(), totalSpread), security.rule().tickSize());
            if (executionPrice < order.priceCap()) {
                if (order.type() == OrderType.MARKET) rejectOrder(order, "MARKET_PRICE_CAP");
                return 0;
            }
        }

        long quantity = Math.min(order.remainingQuantity(), visibleLiquidity);
        quantity = alignDown(quantity, security.rule().lotSize());
        if (quantity <= 0) return 0;
        if (order.side() == OrderSide.BUY) {
            Long inventory = jdbc.queryForObject("""
                    SELECT quantity FROM astock_system_inventory WHERE security_id = ? FOR UPDATE
                    """, Long.class, security.id());
            quantity = alignDown(Math.min(quantity, inventory == null ? 0 : inventory), security.rule().lotSize());
            if (quantity <= 0) throw new TradeRejectedException("NPC_LIQUIDITY", "market maker inventory exhausted");
            settleBuy(order, security, quote, executionPrice, quantity);
        } else {
            settleSell(order, security, quote, executionPrice, quantity);
        }
        return quantity;
    }

    private void settleBuy(DbOrder order, SecurityInfo security, CanonicalQuote quote,
                           long executionPrice, long quantity) {
        long notional = ScaledMath.notionalCash(quantity, executionPrice, properties.pricing().gameCoinsPerCny());
        long previousNotional = sumOrder(order.orderId(), "notional");
        long previousFees = sumOrder(order.orderId(), "fee");
        long totalFee = ScaledMath.fee(Math.addExact(previousNotional, notional),
                properties.pricing().feeBps(), properties.pricing().minimumFee());
        long fee = Math.max(0, totalFee - previousFees);
        long actualTotal = ScaledMath.safeTotal(notional, fee);
        long remaining = order.remainingQuantity() - quantity;
        if (order.reservedCash() < actualTotal) {
            throw new IllegalStateException("buy reservation invariant violated");
        }
        // Keep the unused reservation until the final partial fill. This avoids
        // charging/reserving the minimum fee more than once across partial fills.
        long remainingReserve = remaining == 0 ? 0 : order.reservedCash() - actualTotal;
        long release = remaining == 0 ? order.reservedCash() - actualTotal : 0;
        AccountRow account = lockAccount(order.accountId());
        if (account.cashFrozen() < actualTotal + release) throw new IllegalStateException("frozen cash projection drift");

        jdbc.update("""
                UPDATE astock_account
                SET cash_frozen = cash_frozen - ?, cash_available = cash_available + ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE account_id = ?
                """, actualTotal + release, release, order.accountId());
        adjustSystemCash("MARKET_MAKER", notional);
        adjustSystemCash("FEES", fee);
        jdbc.update("""
                UPDATE astock_system_inventory SET quantity = quantity - ?, version = version + 1
                WHERE security_id = ?
                """, quantity, security.id());

        PositionRow position = lockOrCreatePosition(order.accountId(), security.id());
        long newTotal = Math.addExact(position.quantityTotal(), quantity);
        long average = weightedAverage(position.quantityTotal(), position.averageCost(), quantity, executionPrice);
        LocalDate tradeDate = calendar.tradeDate();
        LocalDate unlockDate = calendar.settlementDate(tradeDate, security.rule().tPlusDays());
        boolean immediatelyAvailable = !unlockDate.isAfter(tradeDate);
        jdbc.update("""
                UPDATE astock_position
                SET quantity_total = ?, quantity_available = quantity_available + ?, average_cost = ?,
                    version = version + 1
                WHERE account_id = ? AND security_id = ?
                """, newTotal, immediatelyAvailable ? quantity : 0, average, order.accountId(), security.id());
        jdbc.update("""
                INSERT INTO astock_position_lot (
                    account_id, security_id, quantity, remaining_quantity, buy_trade_date,
                    unlock_trade_date, cost_price, settled
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, order.accountId(), security.id(), quantity, quantity, Date.valueOf(tradeDate),
                Date.valueOf(unlockDate), executionPrice, immediatelyAvailable);

        finishFill(order, quote, executionPrice, quantity, notional, fee, remaining, remainingReserve);
        List<LedgerLine> lines = new ArrayList<>();
        lines.add(line("PLAYER_CASH_FROZEN", order.accountId(), CASH_CURRENCY, -actualTotal, "FILL"));
        lines.add(new LedgerLine("SYSTEM_CASH", "MARKET_MAKER", CASH_CURRENCY, notional, "FILL"));
        lines.add(new LedgerLine("SYSTEM_CASH", "FEES", CASH_CURRENCY, fee, "FEE"));
        if (release > 0) {
            lines.add(line("PLAYER_CASH_FROZEN", order.accountId(), CASH_CURRENCY, -release, "RELEASE"));
            lines.add(line("PLAYER_CASH_AVAILABLE", order.accountId(), CASH_CURRENCY, release, "RELEASE"));
        }
        String shares = sharesCurrency(security.id());
        lines.add(new LedgerLine("SYSTEM_INVENTORY", "MARKET_MAKER", shares, -quantity, "FILL"));
        lines.add(line(immediatelyAvailable ? "PLAYER_SHARES_AVAILABLE" : "PLAYER_SHARES_LOCKED",
                order.accountId(), shares, quantity, "FILL"));
        ledger.write(UUID.randomUUID().toString(), "FILL", order.orderId(), lines);
    }

    private void settleSell(DbOrder order, SecurityInfo security, CanonicalQuote quote,
                            long executionPrice, long quantity) {
        long notional = ScaledMath.notionalCash(quantity, executionPrice, properties.pricing().gameCoinsPerCny());
        long previousNotional = sumOrder(order.orderId(), "notional");
        long previousFees = sumOrder(order.orderId(), "fee");
        long totalFee = ScaledMath.fee(Math.addExact(previousNotional, notional),
                properties.pricing().feeBps(), properties.pricing().minimumFee());
        long fee = Math.max(0, totalFee - previousFees);
        if (fee > notional) throw new TradeRejectedException("FEE_EXCEEDS_FILL", "fill is below minimum fee");
        long payout = notional - fee;
        long treasury = systemCashForUpdate("MARKET_MAKER");
        if (treasury < notional) throw new TradeRejectedException("TREASURY_CASH", "market maker has insufficient cash");
        AccountRow account = lockAccount(order.accountId());
        PositionRow position = lockPosition(order.accountId(), security.id())
                .orElseThrow(() -> new IllegalStateException("sell position disappeared"));
        if (position.quantityFrozen() < quantity || order.reservedQuantity() < quantity) {
            throw new IllegalStateException("frozen share projection drift");
        }
        consumeLots(order.accountId(), security.id(), quantity, calendar.tradeDate());

        jdbc.update("""
                UPDATE astock_account
                SET cash_available = cash_available + ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE account_id = ?
                """, payout, order.accountId());
        long newTotal = position.quantityTotal() - quantity;
        jdbc.update("""
                UPDATE astock_position
                SET quantity_total = quantity_total - ?, quantity_frozen = quantity_frozen - ?,
                    average_cost = CASE WHEN quantity_total - ? = 0 THEN 0 ELSE average_cost END,
                    version = version + 1
                WHERE account_id = ? AND security_id = ?
                """, quantity, quantity, quantity, order.accountId(), security.id());
        adjustSystemCash("MARKET_MAKER", -notional);
        adjustSystemCash("FEES", fee);
        jdbc.update("""
                UPDATE astock_system_inventory SET quantity = quantity + ?, version = version + 1
                WHERE security_id = ?
                """, quantity, security.id());

        long remaining = order.remainingQuantity() - quantity;
        finishFill(order, quote, executionPrice, quantity, notional, fee, remaining, 0);
        jdbc.update("UPDATE astock_order SET reserved_quantity = ? WHERE order_id = ?", remaining, order.orderId());
        String shares = sharesCurrency(security.id());
        ledger.write(UUID.randomUUID().toString(), "FILL", order.orderId(), List.of(
                line("PLAYER_CASH_AVAILABLE", order.accountId(), CASH_CURRENCY, payout, "FILL"),
                new LedgerLine("SYSTEM_CASH", "MARKET_MAKER", CASH_CURRENCY, -notional, "FILL"),
                new LedgerLine("SYSTEM_CASH", "FEES", CASH_CURRENCY, fee, "FEE"),
                line("PLAYER_SHARES_FROZEN", order.accountId(), shares, -quantity, "FILL"),
                new LedgerLine("SYSTEM_INVENTORY", "MARKET_MAKER", shares, quantity, "FILL")
        ));
        if (newTotal == 0) {
            LOGGER.debug("Position {}:{} closed", order.accountId(), security.symbol());
        }
    }

    private void finishFill(DbOrder order, CanonicalQuote quote, long price, long quantity,
                            long notional, long fee, long remaining, long remainingReserve) {
        String fillId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO astock_fill (
                    fill_id, order_id, security_id, quantity, price, notional, fee,
                    quote_sequence, quote_source, quote_timestamp
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fillId, order.orderId(), order.securityId(), quantity, price, notional, fee,
                quote.sequence(), quote.source(), quote.sourceTimestamp());
        OrderStatus status = remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        jdbc.update("""
                UPDATE astock_order
                SET remaining_quantity = ?, reserved_cash = ?, status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                """, remaining, remainingReserve, status.name(), order.orderId());
        outbox.append("ORDER", order.orderId(), "ORDER_" + status.name(),
                "{\"orderId\":\"" + order.orderId() + "\",\"fillId\":\"" + fillId + "\"}");
    }

    private void cancelInTransaction(UUID playerUuid, String orderId, String reason, boolean admin) {
        DbOrder order = lockOrder(orderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown order: " + orderId));
        if (!admin) {
            String owner = jdbc.queryForObject("SELECT player_uuid FROM astock_account WHERE account_id = ?",
                    String.class, order.accountId());
            if (owner == null || !owner.equals(playerUuid.toString())) {
                throw new TradeRejectedException("NOT_ORDER_OWNER", "order belongs to another account");
            }
        }
        if (order.status().isTerminal()) return;
        releaseReservation(order, "CANCEL");
        jdbc.update("""
                UPDATE astock_order SET status = 'CANCELED', reject_reason = ?, reserved_cash = 0,
                    reserved_quantity = 0, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?
                """, reason, orderId);
        outbox.append("ORDER", orderId, "ORDER_CANCELED", "{\"orderId\":\"" + orderId + "\"}");
    }

    private void rejectOrder(DbOrder order, String reason) {
        releaseReservation(order, "REJECT");
        jdbc.update("""
                UPDATE astock_order SET status = 'REJECTED', reject_reason = ?, reserved_cash = 0,
                    reserved_quantity = 0, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?
                """, reason, order.orderId());
        outbox.append("ORDER", order.orderId(), "ORDER_REJECTED", "{\"reason\":\"" + reason + "\"}");
    }

    private void releaseReservation(DbOrder order, String entryType) {
        if (order.side() == OrderSide.BUY && order.reservedCash() > 0) {
            lockAccount(order.accountId());
            jdbc.update("""
                    UPDATE astock_account SET cash_frozen = cash_frozen - ?, cash_available = cash_available + ?,
                        version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE account_id = ?
                    """, order.reservedCash(), order.reservedCash(), order.accountId());
            ledger.write(UUID.randomUUID().toString(), "ORDER", order.orderId(), List.of(
                    line("PLAYER_CASH_FROZEN", order.accountId(), CASH_CURRENCY, -order.reservedCash(), entryType),
                    line("PLAYER_CASH_AVAILABLE", order.accountId(), CASH_CURRENCY, order.reservedCash(), entryType)
            ));
        } else if (order.side() == OrderSide.SELL && order.reservedQuantity() > 0) {
            lockPosition(order.accountId(), order.securityId())
                    .orElseThrow(() -> new IllegalStateException("sell position disappeared"));
            jdbc.update("""
                    UPDATE astock_position SET quantity_frozen = quantity_frozen - ?,
                        quantity_available = quantity_available + ?, version = version + 1
                    WHERE account_id = ? AND security_id = ?
                    """, order.reservedQuantity(), order.reservedQuantity(), order.accountId(), order.securityId());
            String shares = sharesCurrency(order.securityId());
            ledger.write(UUID.randomUUID().toString(), "ORDER", order.orderId(), List.of(
                    line("PLAYER_SHARES_FROZEN", order.accountId(), shares, -order.reservedQuantity(), entryType),
                    line("PLAYER_SHARES_AVAILABLE", order.accountId(), shares, order.reservedQuantity(), entryType)
            ));
        }
    }

    private void consumeLots(long accountId, int securityId, long quantity, LocalDate tradeDate) {
        List<LotRow> lots = jdbc.query("""
                SELECT lot_id, remaining_quantity FROM astock_position_lot
                WHERE account_id = ? AND security_id = ? AND settled = TRUE
                  AND unlock_trade_date <= ? AND remaining_quantity > 0
                ORDER BY buy_trade_date, lot_id FOR UPDATE
                """, (rs, row) -> new LotRow(rs.getLong(1), rs.getLong(2)),
                accountId, securityId, Date.valueOf(tradeDate));
        long remaining = quantity;
        for (LotRow lot : lots) {
            if (remaining == 0) break;
            long used = Math.min(remaining, lot.remaining());
            jdbc.update("UPDATE astock_position_lot SET remaining_quantity = remaining_quantity - ? WHERE lot_id = ?",
                    used, lot.id());
            remaining -= used;
        }
        if (remaining != 0) throw new IllegalStateException("position lot availability drift");
    }

    private long lockOrCreateAccount(UUID playerUuid) {
        List<Long> ids = jdbc.queryForList("SELECT account_id FROM astock_account WHERE player_uuid = ?",
                Long.class, playerUuid.toString());
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO astock_account (player_uuid) VALUES (?)", playerUuid.toString());
            ids = jdbc.queryForList("SELECT account_id FROM astock_account WHERE player_uuid = ?",
                    Long.class, playerUuid.toString());
        }
        long id = ids.getFirst();
        lockAccount(id);
        return id;
    }

    private AccountRow lockAccount(long accountId) {
        return jdbc.query("""
                SELECT account_id, cash_available, cash_frozen FROM astock_account
                WHERE account_id = ? FOR UPDATE
                """, (rs, row) -> new AccountRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)), accountId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("account disappeared"));
    }

    private Optional<PositionRow> lockPosition(long accountId, int securityId) {
        return jdbc.query("""
                SELECT account_id, security_id, quantity_total, quantity_available, quantity_frozen, average_cost
                FROM astock_position WHERE account_id = ? AND security_id = ? FOR UPDATE
                """, this::mapPosition, accountId, securityId).stream().findFirst();
    }

    private PositionRow lockOrCreatePosition(long accountId, int securityId) {
        Optional<PositionRow> existing = lockPosition(accountId, securityId);
        if (existing.isPresent()) return existing.get();
        jdbc.update("INSERT INTO astock_position (account_id, security_id) VALUES (?, ?)", accountId, securityId);
        return lockPosition(accountId, securityId).orElseThrow();
    }

    private Optional<DbOrder> lockOrder(String orderId) {
        return jdbc.query("""
                SELECT order_id, account_id, security_id, side, order_type, remaining_quantity,
                       limit_price, price_cap, reserved_cash, reserved_quantity,
                       accepted_quote_sequence, status
                FROM astock_order WHERE order_id = ? FOR UPDATE
                """, this::mapOrder, orderId).stream().findFirst();
    }

    private OrderSide orderSide(String orderId) {
        String side = jdbc.queryForObject("SELECT side FROM astock_order WHERE order_id = ?", String.class, orderId);
        return OrderSide.valueOf(side);
    }

    private boolean needsDetailRefresh(CanonicalQuote quote) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM astock_order
                WHERE security_id = ? AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                  AND accepted_quote_sequence < ?
                  AND (
                    order_type = 'MARKET'
                    OR (side = 'BUY' AND limit_price >= ?)
                    OR (side = 'SELL' AND limit_price <= ?)
                  )
                """, Integer.class, quote.securityId(), quote.sequence(), quote.lastPrice(), quote.lastPrice());
        return count != null && count > 0;
    }

    private long sumOrder(String orderId, String column) {
        if (!column.equals("notional") && !column.equals("fee")) throw new IllegalArgumentException("invalid fill column");
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(" + column + "), 0) FROM astock_fill WHERE order_id = ?",
                Long.class, orderId);
        return value == null ? 0 : value;
    }

    private void adjustSystemCash(String account, long amount) {
        jdbc.update("""
                UPDATE astock_system_account SET cash_balance = cash_balance + ?, version = version + 1
                WHERE account_code = ?
                """, amount, account);
    }

    private long systemCashForUpdate(String account) {
        Long value = jdbc.queryForObject("""
                SELECT cash_balance FROM astock_system_account WHERE account_code = ? FOR UPDATE
                """, Long.class, account);
        if (value == null) throw new IllegalStateException("missing system account " + account);
        return value;
    }

    private void ensureDisplayQuote(CanonicalQuote quote) {
        if (quote.quality() == QuoteQuality.REJECTED || quote.quality() == QuoteQuality.CONFLICT
                || quote.quality() == QuoteQuality.STALE) {
            throw new TradeRejectedException("QUOTE_QUALITY", "quote quality does not allow orders");
        }
        long age = Math.max(0, clock.millis() - quote.sourceTimestamp());
        if (age > properties.data().displayStaleAfter().toMillis()) {
            throw new TradeRejectedException("QUOTE_STALE", "quote is stale");
        }
    }

    private boolean isExecutableNow(CanonicalQuote quote) {
        long age = Math.max(0, clock.millis() - quote.sourceTimestamp());
        return quote.status() == TradingStatus.TRADING && quote.hasExecutableBook()
                && quote.quality() == QuoteQuality.VALID
                && age <= properties.data().executionStaleAfter().toMillis();
    }

    private static long weightedAverage(long oldQuantity, long oldPrice, long addedQuantity, long addedPrice) {
        long total = Math.addExact(oldQuantity, addedQuantity);
        if (total == 0) return 0;
        BigInteger numerator = BigInteger.valueOf(oldQuantity).multiply(BigInteger.valueOf(oldPrice))
                .add(BigInteger.valueOf(addedQuantity).multiply(BigInteger.valueOf(addedPrice)));
        BigInteger[] divided = numerator.divideAndRemainder(BigInteger.valueOf(total));
        BigInteger rounded = divided[0];
        if (divided[1].shiftLeft(1).compareTo(BigInteger.valueOf(total)) >= 0) rounded = rounded.add(BigInteger.ONE);
        return rounded.longValueExact();
    }

    private static long alignUp(long value, long tick) {
        long remainder = value % tick;
        return remainder == 0 ? value : Math.addExact(value, tick - remainder);
    }

    private static long alignDown(long value, long unit) {
        return value - value % unit;
    }

    private static long positiveOr(long preferred, long fallback) {
        if (preferred > 0) return preferred;
        if (fallback > 0) return fallback;
        throw new TradeRejectedException("QUOTE_PRICE", "quote has no usable price");
    }

    private static String sharesCurrency(int securityId) {
        return "SHARES:" + securityId;
    }

    private static LedgerLine line(String type, long accountId, String currency, long amount, String entry) {
        return new LedgerLine(type, Long.toString(accountId), currency, amount, entry);
    }

    private PositionRow mapPosition(ResultSet rs, int row) throws SQLException {
        return new PositionRow(rs.getLong("account_id"), rs.getInt("security_id"),
                rs.getLong("quantity_total"), rs.getLong("quantity_available"),
                rs.getLong("quantity_frozen"), rs.getLong("average_cost"));
    }

    private DbOrder mapOrder(ResultSet rs, int row) throws SQLException {
        Long limit = rs.getObject("limit_price") == null ? null : rs.getLong("limit_price");
        return new DbOrder(rs.getString("order_id"), rs.getLong("account_id"), rs.getInt("security_id"),
                OrderSide.valueOf(rs.getString("side")), OrderType.valueOf(rs.getString("order_type")),
                rs.getLong("remaining_quantity"), limit, rs.getLong("price_cap"),
                rs.getLong("reserved_cash"), rs.getLong("reserved_quantity"),
                rs.getLong("accepted_quote_sequence"), OrderStatus.valueOf(rs.getString("status")));
    }

    private record Placement(String orderId, boolean created) {
    }

    private record AccountRow(long id, long cashAvailable, long cashFrozen) {
    }

    private record PositionRow(long accountId, int securityId, long quantityTotal,
                               long quantityAvailable, long quantityFrozen, long averageCost) {
    }

    private record DbOrder(String orderId, long accountId, int securityId, OrderSide side, OrderType type,
                           long remainingQuantity, Long limitPrice, long priceCap, long reservedCash,
                           long reservedQuantity, long acceptedSequence, OrderStatus status) {
    }

    private record LotRow(long id, long remaining) {
    }
}
