package dev.astock.paper.economy;

import dev.astock.paper.AStockPaperPlugin;
import dev.astock.paper.client.AStockServiceClient;
import dev.astock.paper.client.ApiModels.BeginTransferRequest;
import dev.astock.paper.client.ApiModels.Transfer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TransferCoordinator implements AutoCloseable {
    private final AStockPaperPlugin plugin;
    private final AStockServiceClient client;
    private final EconomyBridge economy;
    private final TransferJournal journal;
    private final String serverId;
    private final ExecutorService journalExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "astock-transfer-journal");
        thread.setDaemon(false);
        return thread;
    });

    public TransferCoordinator(AStockPaperPlugin plugin, AStockServiceClient client,
                               EconomyBridge economy, TransferJournal journal, String serverId) {
        this.plugin = plugin;
        this.client = client;
        this.economy = economy;
        this.journal = journal;
        this.serverId = serverId;
    }

    public boolean available() {
        return economy.available();
    }

    public CompletableFuture<Transfer> deposit(Player player, long amountMinor) {
        return execute(player, amountMinor, "DEPOSIT");
    }

    public CompletableFuture<Transfer> withdraw(Player player, long amountMinor) {
        return execute(player, amountMinor, "WITHDRAW");
    }

    public void recover() {
        for (TransferJournal.Entry entry : journal.snapshot()) {
            if (entry.phase() == TransferJournal.Phase.ECONOMY_MUTATED) {
                client.confirmEconomy(entry.transferId())
                        .thenCompose(ignored -> removeJournal(entry.transferId()))
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) plugin.getLogger().severe(
                                    "无法恢复已完成经济侧操作的转账 " + entry.transferId() + ": " + rootMessage(failure));
                            else plugin.getLogger().info("已恢复转账 " + entry.transferId());
                        });
            } else {
                plugin.getLogger().warning("转账 " + entry.transferId()
                        + " 在经济操作边界处状态不明确；请使用 /stock admin reconcile " + entry.playerUuid());
            }
        }
    }

    private CompletableFuture<Transfer> execute(Player player, long amountMinor, String direction) {
        if (!available()) return CompletableFuture.failedFuture(
                new IllegalStateException("Vault economy provider is unavailable"));
        String requestId = serverId + ":" + player.getUniqueId() + ":" + UUID.randomUUID();
        var request = new BeginTransferRequest(requestId, player.getUniqueId(), direction, amountMinor);
        return client.beginTransfer(request).thenCompose(transfer -> {
            var entry = new TransferJournal.Entry(transfer.transferId(), requestId, player.getUniqueId(),
                    direction, amountMinor, TransferJournal.Phase.SERVICE_BEGUN, Instant.now().toEpochMilli());
            CompletableFuture<Void> journaled = putJournal(entry).exceptionallyCompose(failure ->
                    client.compensate(transfer.transferId(), "PAPER_JOURNAL_UNAVAILABLE")
                            .thenCompose(ignored -> CompletableFuture.failedFuture(failure)));
            return journaled
                    .thenCompose(ignored -> mutateEconomyOnMain(player, direction, amountMinor))
                    .thenCompose(result -> {
                        if (!result.success()) {
                            return client.compensate(transfer.transferId(), result.message())
                                    .thenCompose(compensated -> removeJournal(transfer.transferId()))
                                    .thenCompose(ignored -> CompletableFuture.failedFuture(
                                            new IllegalStateException(result.message())));
                        }
                        return markJournal(transfer.transferId(), TransferJournal.Phase.ECONOMY_MUTATED)
                                .thenCompose(ignored -> client.confirmEconomy(transfer.transferId()))
                                .thenCompose(completed -> removeJournal(transfer.transferId()).thenApply(value -> completed));
                    });
        });
    }

    private CompletableFuture<EconomyBridge.EconomyResult> mutateEconomyOnMain(
            Player player, String direction, long amountMinor
    ) {
        var future = new CompletableFuture<EconomyBridge.EconomyResult>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                double amount = amountMinor / 100.0d;
                EconomyBridge.EconomyResult result = "DEPOSIT".equals(direction)
                        ? economy.withdraw(player, amount) : economy.deposit(player, amount);
                future.complete(result);
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    private CompletableFuture<Void> putJournal(TransferJournal.Entry entry) {
        return CompletableFuture.runAsync(() -> {
            try {
                journal.put(entry);
            } catch (IOException exception) {
                throw new IllegalStateException("cannot persist transfer journal", exception);
            }
        }, journalExecutor);
    }

    private CompletableFuture<Void> markJournal(String id, TransferJournal.Phase phase) {
        return CompletableFuture.runAsync(() -> {
            try {
                journal.mark(id, phase);
            } catch (IOException exception) {
                throw new IllegalStateException("cannot persist transfer journal", exception);
            }
        }, journalExecutor);
    }

    private CompletableFuture<Void> removeJournal(String id) {
        return CompletableFuture.runAsync(() -> {
            try {
                journal.remove(id);
            } catch (IOException exception) {
                throw new IllegalStateException("cannot persist transfer journal", exception);
            }
        }, journalExecutor);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable value = throwable;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    @Override
    public void close() {
        journalExecutor.shutdown();
        try {
            if (!journalExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                journalExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            journalExecutor.shutdownNow();
        }
    }
}
