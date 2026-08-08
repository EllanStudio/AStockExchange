package dev.astock.service.api;

import dev.astock.domain.order.OrderSide;
import dev.astock.domain.order.OrderType;
import dev.astock.domain.order.PlaceOrderCommand;
import dev.astock.service.trading.OrderCommandBus;
import dev.astock.service.trading.TradingEngine;
import dev.astock.service.trading.TradingQueryService;
import dev.astock.service.trading.model.FillView;
import dev.astock.service.trading.model.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderCommandBus commands;
    private final TradingEngine engine;
    private final TradingQueryService queries;

    public OrderController(OrderCommandBus commands, TradingEngine engine, TradingQueryService queries) {
        this.commands = commands;
        this.engine = engine;
        this.queries = queries;
    }

    @PostMapping
    public OrderView place(@Valid @RequestBody PlaceOrderRequest request) {
        var command = new PlaceOrderCommand(
                request.clientRequestId(), request.playerUuid(), MarketController.normalizePathSymbol(request.symbol()),
                request.side(), request.type(), request.quantity(), request.limitPrice()
        );
        return commands.call(() -> engine.place(command));
    }

    @DeleteMapping("/{orderId}")
    public OrderView cancel(@PathVariable String orderId, @RequestParam UUID playerUuid) {
        return commands.call(() -> engine.cancel(playerUuid, orderId));
    }

    @GetMapping("/{orderId}")
    public OrderView order(@PathVariable String orderId) {
        return queries.order(orderId).orElseThrow(() -> new IllegalArgumentException("unknown order: " + orderId));
    }

    @GetMapping("/{orderId}/fills")
    public List<FillView> fills(@PathVariable String orderId) {
        return queries.fills(orderId);
    }

    public record PlaceOrderRequest(
            @NotBlank String clientRequestId,
            @NotNull UUID playerUuid,
            @NotBlank String symbol,
            @NotNull OrderSide side,
            @NotNull OrderType type,
            @Positive long quantity,
            Long limitPrice
    ) {
    }
}
