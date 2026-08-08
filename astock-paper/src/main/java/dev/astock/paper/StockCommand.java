package dev.astock.paper;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.astock.paper.client.AStockServiceClient;
import dev.astock.paper.client.ApiModels.Account;
import dev.astock.paper.client.ApiModels.Order;
import dev.astock.paper.client.ApiModels.PlaceOrderRequest;
import dev.astock.paper.client.ApiModels.Position;
import dev.astock.paper.client.ApiModels.Quote;
import dev.astock.paper.client.ApiModels.Transfer;
import dev.astock.paper.client.QuoteStream;
import dev.astock.paper.economy.TransferCoordinator;
import dev.astock.paper.gui.MarketGuiManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class StockCommand implements CommandExecutor, TabCompleter {
    private final AStockPaperPlugin plugin;
    private final AStockServiceClient client;
    private final QuoteStream stream;
    private final TransferCoordinator transfers;
    private final MarketGuiManager gui;
    private final String serverId;

    public StockCommand(AStockPaperPlugin plugin, AStockServiceClient client, QuoteStream stream,
                        TransferCoordinator transfers, MarketGuiManager gui, String serverId) {
        this.plugin = plugin;
        this.client = client;
        this.stream = stream;
        this.transfers = transfers;
        this.gui = gui;
        this.serverId = serverId;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("astock.use")) {
            sender.sendMessage(error("你没有使用股票模拟系统的权限。"));
            return true;
        }
        String sub = args.length == 0 ? "market" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "market", "gui" -> market(sender);
                case "help" -> help(sender);
                case "disclaimer", "声明" -> disclaimer(sender);
                case "quote", "行情" -> quote(sender, args);
                case "balance", "余额" -> balance(sender);
                case "positions", "持仓" -> positions(sender);
                case "orders", "订单" -> orders(sender);
                case "buy", "买" -> order(sender, args, "BUY");
                case "sell", "卖" -> order(sender, args, "SELL");
                case "cancel", "撤单" -> cancel(sender, args);
                case "deposit", "存入" -> transfer(sender, args, true);
                case "withdraw", "提取" -> transfer(sender, args, false);
                case "admin" -> admin(sender, args);
                default -> sender.sendMessage(error("未知子命令。使用 /stock help"));
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(error(exception.getMessage()));
        }
        return true;
    }

    private void market(CommandSender sender) {
        Player player = requirePlayer(sender);
        gui.open(player);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("A 股镜像模拟交易所", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/stock market | quote <代码> | balance | positions | orders", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/stock buy|sell <代码> <股数> [限价元] | cancel <订单ID>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/stock deposit|withdraw <游戏币> | disclaimer", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("市价单在下一次有效行情序列成交；买入股份执行 T+1。", NamedTextColor.GRAY));
    }

    private void disclaimer(CommandSender sender) {
        sender.sendMessage(Component.text("本系统为 Minecraft 服务器内的虚拟模拟交易玩法。", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("所有交易仅使用服务器游戏币，不代表真实证券所有权，不构成投资建议。", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("行情可能延迟、错误或中断；游戏币及持仓不得兑换为现实财产。", NamedTextColor.YELLOW));
    }

    private void quote(CommandSender sender, String[] args) {
        requireArgs(args, 2, "/stock quote <股票代码>");
        String symbol = Formatters.symbol(args[1]);
        respond(sender, client.quote(symbol, true), quote -> Component.text(
                quote.symbol() + " 最新 ¥" + Formatters.price(quote.lastPrice())
                        + " 买一 ¥" + Formatters.price(quote.bid1Price())
                        + " 卖一 ¥" + Formatters.price(quote.ask1Price())
                        + " [" + quote.quality() + "/" + quote.source() + "]",
                quote.executable() ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
    }

    private void balance(CommandSender sender) {
        Player player = requirePlayer(sender);
        respond(sender, client.account(player.getUniqueId()), account -> Component.text(
                "股票账户：可用 " + Formatters.cash(account.cashAvailable())
                        + "，冻结 " + Formatters.cash(account.cashFrozen()) + " 游戏币",
                NamedTextColor.GOLD));
    }

    private void positions(CommandSender sender) {
        Player player = requirePlayer(sender);
        respondMany(sender, client.positions(player.getUniqueId()), positions -> {
            if (positions.isEmpty()) return List.of(Component.text("当前没有持仓。", NamedTextColor.GRAY));
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text("持仓（总数 / T+1 可卖 / 冻结）", NamedTextColor.GOLD));
            for (Position position : positions) {
                lines.add(Component.text(position.symbol() + " " + position.name() + "："
                        + position.quantityTotal() + " / " + position.quantityAvailable() + " / "
                        + position.quantityFrozen() + "，成本 ¥" + Formatters.price(position.averageCost()),
                        NamedTextColor.AQUA));
            }
            return lines;
        });
    }

    private void orders(CommandSender sender) {
        Player player = requirePlayer(sender);
        respondMany(sender, client.orders(player.getUniqueId()), orders -> {
            if (orders.isEmpty()) return List.of(Component.text("当前没有订单。", NamedTextColor.GRAY));
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text("最近订单", NamedTextColor.GOLD));
            for (Order order : orders) {
                lines.add(Component.text(order.orderId() + " " + order.side() + " " + order.symbol()
                        + " " + (order.quantity() - order.remainingQuantity()) + "/" + order.quantity()
                        + " " + order.status()
                        + (order.rejectReason() == null ? "" : " [" + order.rejectReason() + "]"),
                        NamedTextColor.AQUA));
            }
            return lines;
        });
    }

    private void order(CommandSender sender, String[] args, String side) {
        Player player = requirePlayer(sender);
        requireArgs(args, 3, "/stock " + side.toLowerCase(Locale.ROOT) + " <代码> <股数> [限价元]");
        String symbol = Formatters.symbol(args[1]);
        long quantity = Long.parseLong(args[2]);
        if (quantity <= 0) throw new IllegalArgumentException("股数必须大于零。首版买卖单位为 100 股。");
        Long limit = args.length >= 4 ? Formatters.priceUnits(args[3]) : null;
        String type = limit == null ? "MARKET" : "LIMIT";
        String requestId = serverId + ":order:" + player.getUniqueId() + ":" + UUID.randomUUID();
        var request = new PlaceOrderRequest(requestId, player.getUniqueId(), symbol, side, type, quantity, limit);
        respond(sender, client.place(request), placed -> Component.text(
                "订单已接收：" + placed.orderId() + "，状态 " + placed.status()
                        + "，接单行情序列 " + placed.acceptedQuoteSequence()
                        + "。市价单将等待下一次有效行情。", NamedTextColor.GREEN));
    }

    private void cancel(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        requireArgs(args, 2, "/stock cancel <订单ID>");
        respond(sender, client.cancel(args[1], player.getUniqueId()), order ->
                Component.text("订单 " + order.orderId() + " 状态：" + order.status(), NamedTextColor.GREEN));
    }

    private void transfer(CommandSender sender, String[] args, boolean deposit) {
        Player player = requirePlayer(sender);
        requireArgs(args, 2, "/stock " + (deposit ? "deposit" : "withdraw") + " <游戏币>");
        long amount = Formatters.cashMinor(args[1]);
        if (amount <= 0) throw new IllegalArgumentException("金额必须大于零。" );
        if (!transfers.available()) throw new IllegalArgumentException("Vault 经济服务不可用，无法转账。" );
        sender.sendMessage(Component.text("正在执行持久化转账 Saga，请勿重复点击……", NamedTextColor.YELLOW));
        CompletableFuture<Transfer> future = deposit ? transfers.deposit(player, amount) : transfers.withdraw(player, amount);
        respond(sender, future, transfer -> Component.text(
                (deposit ? "存入" : "提取") + "完成：" + Formatters.cash(transfer.amount())
                        + " 游戏币，转账号 " + transfer.transferId(), NamedTextColor.GREEN));
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("astock.admin")) throw new IllegalArgumentException("你没有管理权限。" );
        requireArgs(args, 2, "/stock admin <status|source|freeze|unfreeze|reconcile|audit|treasury>");
        String action = args[1].toLowerCase(Locale.ROOT);
        CompletableFuture<JsonElement> future;
        switch (action) {
            case "status", "source", "treasury" -> future = client.adminGet(action);
            case "freeze" -> {
                requireArgs(args, 3, "/stock admin freeze <代码> [原因]");
                String symbol = Formatters.symbol(args[2]);
                String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "PAPER_ADMIN";
                future = client.adminPost("securities/" + symbol + "/freeze", Map.of("reason", reason));
            }
            case "unfreeze" -> {
                requireArgs(args, 3, "/stock admin unfreeze <代码>");
                future = client.adminPost("securities/" + Formatters.symbol(args[2]) + "/unfreeze", Map.of());
            }
            case "reconcile" -> {
                requireArgs(args, 3, "/stock admin reconcile <玩家名或 UUID>");
                future = client.adminGet("reconcile/" + resolvePlayer(args[2]));
            }
            case "audit" -> {
                requireArgs(args, 3, "/stock admin audit <订单ID>");
                future = client.adminGet("audit/" + args[2]);
            }
            default -> throw new IllegalArgumentException("未知管理命令。" );
        }
        respondMany(sender, future, json -> {
            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(json);
            return Arrays.stream(pretty.split("\\R")).limit(30)
                    .<Component>map(line -> Component.text(line, NamedTextColor.GRAY)).toList();
        });
    }

    private UUID resolvePlayer(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            Player online = Bukkit.getPlayerExact(value);
            if (online != null) return online.getUniqueId();
            @SuppressWarnings("deprecation") OfflinePlayer offline = Bukkit.getOfflinePlayer(value);
            return offline.getUniqueId();
        }
    }

    private <T> void respond(CommandSender sender, CompletableFuture<T> future, Function<T, Component> formatter) {
        future.whenComplete((value, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (failure != null) sender.sendMessage(error(Formatters.rootMessage(failure)));
            else sender.sendMessage(formatter.apply(value));
        }));
    }

    private <T> void respondMany(CommandSender sender, CompletableFuture<T> future,
                                 Function<T, List<Component>> formatter) {
        future.whenComplete((value, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (failure != null) sender.sendMessage(error(Formatters.rootMessage(failure)));
            else formatter.apply(value).forEach(sender::sendMessage);
        }));
    }

    private static Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("该命令只能由玩家执行。" );
        return player;
    }

    private static void requireArgs(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("用法：" + usage);
    }

    private static Component error(String message) {
        return Component.text("[A股模拟] " + message, NamedTextColor.RED);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("market", "quote", "balance", "positions", "orders",
                    "buy", "sell", "cancel", "deposit", "withdraw", "disclaimer", "help"));
            if (sender.hasPermission("astock.admin")) values.add("admin");
            return filter(values, args[0]);
        }
        if (args.length == 2 && List.of("quote", "buy", "sell").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(stream.snapshot().stream().map(Quote::symbol).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(List.of("status", "source", "freeze", "unfreeze", "reconcile", "audit", "treasury"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
