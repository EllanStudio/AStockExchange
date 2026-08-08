package dev.astock.service.api;

import dev.astock.service.market.QuoteCoordinator;
import dev.astock.service.trading.AdminService;
import dev.astock.service.trading.OrderCommandBus;
import dev.astock.service.trading.RiskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    private final QuoteCoordinator quotes;
    private final OrderCommandBus commands;

    public AdminController(AdminService admin, QuoteCoordinator quotes, OrderCommandBus commands) {
        this.admin = admin;
        this.quotes = quotes;
        this.commands = commands;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return admin.status();
    }

    @GetMapping("/source")
    public Object source() {
        return quotes.status().source();
    }

    @PostMapping("/securities/{symbol}/freeze")
    public Map<String, Object> freeze(@PathVariable String symbol, @RequestBody(required = false) FreezeRequest request) {
        String normalized = MarketController.normalizePathSymbol(symbol);
        String reason = request == null || request.reason() == null ? "ADMIN_FREEZE" : request.reason();
        commands.call(() -> {
            admin.freeze(normalized, reason);
            return null;
        });
        return Map.of("symbol", normalized, "frozen", true, "reason", reason);
    }

    @PostMapping("/securities/{symbol}/unfreeze")
    public Map<String, Object> unfreeze(@PathVariable String symbol) {
        String normalized = MarketController.normalizePathSymbol(symbol);
        commands.call(() -> {
            admin.unfreeze(normalized);
            return null;
        });
        return Map.of("symbol", normalized, "frozen", false);
    }

    @GetMapping("/reconcile/{playerUuid}")
    public AdminService.ReconcileResult reconcile(@PathVariable UUID playerUuid) {
        return commands.call(() -> admin.reconcile(playerUuid));
    }

    @GetMapping("/audit/{orderId}")
    public Map<String, Object> audit(@PathVariable String orderId) {
        return admin.audit(orderId);
    }

    @GetMapping("/treasury")
    public RiskService.TreasurySnapshot treasury() {
        return admin.treasury();
    }

    public record FreezeRequest(String reason) {
    }
}
