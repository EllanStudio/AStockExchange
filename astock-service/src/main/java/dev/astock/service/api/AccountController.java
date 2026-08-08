package dev.astock.service.api;

import dev.astock.service.trading.TradingQueryService;
import dev.astock.service.trading.model.AccountView;
import dev.astock.service.trading.model.PositionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{playerUuid}")
public class AccountController {
    private final TradingQueryService queries;

    public AccountController(TradingQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public AccountView account(@PathVariable UUID playerUuid) {
        return queries.account(playerUuid);
    }

    @GetMapping("/positions")
    public List<PositionView> positions(@PathVariable UUID playerUuid) {
        return queries.positions(playerUuid);
    }

    @GetMapping("/orders")
    public Object orders(@PathVariable UUID playerUuid, @RequestParam(defaultValue = "50") int limit) {
        return queries.orders(playerUuid, limit);
    }
}
