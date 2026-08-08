package dev.astock.paper;

import dev.astock.paper.client.AStockServiceClient;
import dev.astock.paper.client.QuoteStream;
import dev.astock.paper.economy.EconomyBridge;
import dev.astock.paper.economy.TransferCoordinator;
import dev.astock.paper.economy.TransferJournal;
import dev.astock.paper.economy.VaultEconomyBridge;
import dev.astock.paper.gui.MarketGuiManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Duration;

public final class AStockPaperPlugin extends JavaPlugin {
    private AStockServiceClient client;
    private QuoteStream quoteStream;
    private TransferCoordinator transfers;
    private MarketGuiManager gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String baseUrl = getConfig().getString("service.base-url", "http://127.0.0.1:8787");
        String apiKey = getConfig().getString("service.api-key", "change-this-api-key");
        int timeout = getConfig().getInt("service.request-timeout-seconds", 8);
        if (apiKey.startsWith("change-")) {
            getLogger().warning("service.api-key 仍为示例值；投产前必须同时修改插件与 AStockService 配置。");
        }

        this.client = new AStockServiceClient(baseUrl, apiKey, Duration.ofSeconds(Math.max(1, timeout)));
        this.quoteStream = new QuoteStream(client,
                getConfig().getInt("service.reconnect-min-seconds", 2),
                getConfig().getInt("service.reconnect-max-seconds", 30),
                message -> getLogger().warning(message));

        EconomyBridge economy = getConfig().getBoolean("economy.enabled", true)
                ? new VaultEconomyBridge() : disabledEconomy();
        try {
            TransferJournal journal = new TransferJournal(getDataFolder().toPath().resolve("transfer-journal.json"));
            this.transfers = new TransferCoordinator(this, client, economy, journal,
                    getConfig().getString("paper.server-id", "paper-1"));
        } catch (IOException exception) {
            getLogger().severe("无法打开持久化转账日志，插件将禁用以避免经济复制漏洞: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.gui = new MarketGuiManager(this, quoteStream,
                getConfig().getInt("paper.gui-refresh-ticks", 10),
                getConfig().getInt("paper.max-dirty-slot-updates-per-cycle", 500));
        getServer().getPluginManager().registerEvents(gui, this);
        if (getConfig().getBoolean("disclaimer.show-on-join", true)) {
            getServer().getPluginManager().registerEvents(new PlayerNoticeListener(), this);
        }

        StockCommand stock = new StockCommand(this, client, quoteStream, transfers, gui,
                getConfig().getString("paper.server-id", "paper-1"));
        PluginCommand command = getCommand("stock");
        if (command == null) throw new IllegalStateException("stock command is absent from plugin.yml");
        command.setExecutor(stock);
        command.setTabCompleter(stock);

        quoteStream.start();
        transfers.recover();
        getLogger().info("AStockPaper 已启动；Paper 主线程只承担命令、GUI 与经济插件调用。" );
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.close();
        if (quoteStream != null) quoteStream.close();
        if (transfers != null) transfers.close();
        if (client != null) client.close();
    }

    private static EconomyBridge disabledEconomy() {
        return new EconomyBridge() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public EconomyResult withdraw(org.bukkit.OfflinePlayer player, double amount) {
                return EconomyResult.failure("economy bridge disabled");
            }

            @Override
            public EconomyResult deposit(org.bukkit.OfflinePlayer player, double amount) {
                return EconomyResult.failure("economy bridge disabled");
            }
        };
    }
}
