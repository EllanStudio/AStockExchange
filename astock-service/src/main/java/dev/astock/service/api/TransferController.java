package dev.astock.service.api;

import dev.astock.service.trading.OrderCommandBus;
import dev.astock.service.trading.TransferService;
import dev.astock.service.trading.model.TransferDirection;
import dev.astock.service.trading.model.TransferView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final OrderCommandBus commands;
    private final TransferService transfers;

    public TransferController(OrderCommandBus commands, TransferService transfers) {
        this.commands = commands;
        this.transfers = transfers;
    }

    @PostMapping
    public TransferView begin(@Valid @RequestBody BeginTransferRequest request) {
        return commands.call(() -> transfers.begin(request.clientRequestId(), request.playerUuid(),
                request.direction(), request.amount()));
    }

    @PostMapping("/{transferId}/confirm-economy")
    public TransferView confirm(@PathVariable String transferId) {
        return commands.call(() -> transfers.confirmEconomyMutation(transferId));
    }

    @PostMapping("/{transferId}/compensate")
    public TransferView compensate(@PathVariable String transferId,
                                   @RequestBody(required = false) CompensateRequest request) {
        String reason = request == null || request.reason() == null ? "ECONOMY_OPERATION_FAILED" : request.reason();
        return commands.call(() -> transfers.compensate(transferId, reason));
    }

    @GetMapping("/{transferId}")
    public TransferView transfer(@PathVariable String transferId) {
        return transfers.require(transferId);
    }

    @GetMapping("/pending")
    public List<TransferView> pending(@RequestParam UUID playerUuid) {
        return transfers.pending(playerUuid);
    }

    public record BeginTransferRequest(
            @NotBlank String clientRequestId,
            @NotNull UUID playerUuid,
            @NotNull TransferDirection direction,
            @Positive long amount
    ) {
    }

    public record CompensateRequest(String reason) {
    }
}
