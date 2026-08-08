package dev.astock.service;

import dev.astock.domain.order.OrderSide;
import dev.astock.domain.order.OrderStatus;
import dev.astock.domain.order.OrderType;
import dev.astock.domain.order.PlaceOrderCommand;
import dev.astock.service.market.QuoteCoordinator;
import dev.astock.service.trading.AdminService;
import dev.astock.service.trading.OrderCommandBus;
import dev.astock.service.trading.SettlementService;
import dev.astock.service.trading.TradeRejectedException;
import dev.astock.service.trading.TradingEngine;
import dev.astock.service.trading.TradingQueryService;
import dev.astock.service.trading.TransferService;
import dev.astock.service.trading.model.TransferDirection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TradingFlowIntegrationTest {
    @Autowired OrderCommandBus commands;
    @Autowired TransferService transfers;
    @Autowired TradingEngine engine;
    @Autowired TradingQueryService queries;
    @Autowired QuoteCoordinator quotes;
    @Autowired SettlementService settlement;
    @Autowired AdminService admin;
    @Autowired JdbcTemplate jdbc;

    @Test
    void executesIdempotentNextQuoteTPlusOneRoundTripWithBalancedLedger() {
        UUID player = UUID.randomUUID();
        var transfer = commands.call(() -> transfers.begin("deposit-1", player,
                TransferDirection.DEPOSIT, 10_000_000_000L));
        commands.call(() -> transfers.confirmEconomyMutation(transfer.transferId()));
        assertThat(queries.account(player).cashAvailable()).isEqualTo(10_000_000_000L);

        var buyRequest = new PlaceOrderCommand("buy-1", player, "SH.600000",
                OrderSide.BUY, OrderType.MARKET, 100, null);
        var accepted = commands.call(() -> engine.place(buyRequest));
        assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(queries.positions(player)).isEmpty();

        quotes.refreshDetail("SH.600000").join();
        commands.call(() -> null); // single-writer barrier after the quote event
        var filled = queries.order(accepted.orderId()).orElseThrow();
        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(queries.positions(player)).singleElement().satisfies(position -> {
            assertThat(position.quantityTotal()).isEqualTo(100);
            assertThat(position.quantityAvailable()).isZero();
        });

        var duplicate = commands.call(() -> engine.place(buyRequest));
        assertThat(duplicate.orderId()).isEqualTo(accepted.orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM astock_order WHERE client_request_id = 'buy-1'",
                Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> commands.call(() -> engine.place(new PlaceOrderCommand(
                "sell-too-early", player, "SH.600000", OrderSide.SELL, OrderType.MARKET, 100, null))))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("T+1");

        jdbc.update("UPDATE astock_position_lot SET unlock_trade_date = ?",
                java.sql.Date.valueOf(LocalDate.now().minusDays(1)));
        commands.call(() -> settlement.settleDueLots());
        assertThat(queries.positions(player).getFirst().quantityAvailable()).isEqualTo(100);

        var sell = commands.call(() -> engine.place(new PlaceOrderCommand(
                "sell-1", player, "SH.600000", OrderSide.SELL, OrderType.MARKET, 100, null)));
        quotes.refreshDetail("SH.600000").join();
        commands.call(() -> null);
        assertThat(queries.order(sell.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(queries.positions(player)).isEmpty();

        Integer unbalanced = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT transaction_id, currency FROM astock_ledger_entry
                    GROUP BY transaction_id, currency HAVING SUM(amount) <> 0
                ) broken
                """, Integer.class);
        assertThat(unbalanced).isZero();
        assertThat(admin.reconcile(player).balanced()).isTrue();
    }

    @Test
    void cancelReleasesBuyReservation() {
        UUID player = UUID.randomUUID();
        var transfer = commands.call(() -> transfers.begin("deposit-cancel", player,
                TransferDirection.DEPOSIT, 1_000_000_000L));
        commands.call(() -> transfers.confirmEconomyMutation(transfer.transferId()));
        long before = queries.account(player).cashAvailable();
        var quote = quotes.current("SH.600000").orElseThrow();
        long lowLimit = Math.max(100, (quote.lastPrice() * 95 / 100 / 100) * 100);
        var order = commands.call(() -> engine.place(new PlaceOrderCommand(
                "limit-cancel", player, "SH.600000", OrderSide.BUY, OrderType.LIMIT, 100, lowLimit)));
        assertThat(queries.account(player).cashFrozen()).isPositive();
        commands.call(() -> engine.cancel(player, order.orderId()));
        assertThat(queries.account(player).cashAvailable()).isEqualTo(before);
        assertThat(queries.account(player).cashFrozen()).isZero();
    }

    @Test
    void executesUnitedStatesOddLotAsImmediatelySellablePosition() {
        UUID player = UUID.randomUUID();
        var transfer = commands.call(() -> transfers.begin("deposit-us", player,
                TransferDirection.DEPOSIT, 1_000_000_000L));
        commands.call(() -> transfers.confirmEconomyMutation(transfer.transferId()));

        var buy = commands.call(() -> engine.place(new PlaceOrderCommand(
                "buy-us", player, "US.AAPL", OrderSide.BUY, OrderType.MARKET, 1, null
        )));
        quotes.refreshDetail("US.AAPL").join();
        commands.call(() -> null);
        assertThat(queries.order(buy.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(queries.positions(player)).singleElement().satisfies(position -> {
            assertThat(position.symbol()).isEqualTo("US.AAPL");
            assertThat(position.quantityTotal()).isEqualTo(1);
            assertThat(position.quantityAvailable()).isEqualTo(1);
        });

        var sell = commands.call(() -> engine.place(new PlaceOrderCommand(
                "sell-us", player, "US.AAPL", OrderSide.SELL, OrderType.MARKET, 1, null
        )));
        quotes.refreshDetail("US.AAPL").join();
        commands.call(() -> null);
        assertThat(queries.order(sell.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(queries.positions(player)).isEmpty();
    }
}
