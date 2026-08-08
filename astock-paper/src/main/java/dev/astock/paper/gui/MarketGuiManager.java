package dev.astock.paper.gui;

import dev.astock.paper.AStockPaperPlugin;
import dev.astock.paper.Formatters;
import dev.astock.paper.client.ApiModels.Quote;
import dev.astock.paper.client.QuoteStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class MarketGuiManager implements Listener, AutoCloseable {
    private static final int STOCK_SLOTS = 18;
    private final AStockPaperPlugin plugin;
    private final QuoteStream stream;
    private final int maxDirtyUpdates;
    private final Map<UUID, View> views = new HashMap<>();
    private final BukkitTask refreshTask;

    public MarketGuiManager(AStockPaperPlugin plugin, QuoteStream stream, int refreshTicks, int maxDirtyUpdates) {
        this.plugin = plugin;
        this.stream = stream;
        this.maxDirtyUpdates = Math.max(1, maxDirtyUpdates);
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh,
                Math.max(1, refreshTicks), Math.max(1, refreshTicks));
    }

    public void open(Player player) {
        List<String> symbols = selectedSymbols();
        MarketHolder holder = new MarketHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("A/H/美股镜像模拟交易所", NamedTextColor.GOLD));
        holder.inventory = inventory;
        View view = new View(holder, symbols, new long[STOCK_SLOTS], new boolean[] {!stream.connected()});
        for (int slot = 0; slot < STOCK_SLOTS; slot++) {
            if (slot < symbols.size()) updateSlot(view, slot, stream.get(symbols.get(slot)));
            else inventory.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE,
                    Component.text("等待行情", NamedTextColor.GRAY), List.of()));
        }
        updateConnection(view);
        inventory.setItem(26, item(Material.WRITABLE_BOOK,
                Component.text("模拟交易声明", NamedTextColor.YELLOW), List.of(
                        Component.text("仅使用服务器游戏币", NamedTextColor.GRAY),
                        Component.text("不代表真实证券所有权", NamedTextColor.GRAY),
                        Component.text("不构成投资建议", NamedTextColor.GRAY)
                )));
        views.put(player.getUniqueId(), view);
        player.openInventory(inventory);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MarketHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        View view = views.get(player.getUniqueId());
        if (view == null || event.getRawSlot() < 0 || event.getRawSlot() >= view.symbols().size()) return;
        String symbol = view.symbols().get(event.getRawSlot());
        Quote quote = stream.get(symbol);
        if (quote != null) {
            player.sendMessage(Component.text(symbol + " 最新价 " + Formatters.price(symbol, quote.lastPrice())
                    + "；使用 /stock buy|sell " + symbol + " <数量> [限价]", NamedTextColor.AQUA));
        }
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof MarketHolder) {
            views.remove(event.getPlayer().getUniqueId());
        }
    }

    private void refresh() {
        int updates = 0;
        for (View view : List.copyOf(views.values())) {
            if (view.symbols().isEmpty()) {
                view.symbols().addAll(selectedSymbols());
            }
            if (view.connection()[0] != stream.connected() && updates < maxDirtyUpdates) {
                updateConnection(view);
                updates++;
            }
            for (int slot = 0; slot < view.symbols().size() && updates < maxDirtyUpdates; slot++) {
                Quote quote = stream.get(view.symbols().get(slot));
                if (quote != null && quote.sequence() != view.sequences()[slot]) {
                    updateSlot(view, slot, quote);
                    updates++;
                }
            }
            if (updates >= maxDirtyUpdates) break;
        }
    }

    private void updateConnection(View view) {
        boolean connected = stream.connected();
        view.holder().inventory.setItem(22, item(connected ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text(connected ? "行情连接正常" : "只读：行情连接中断",
                        connected ? NamedTextColor.GREEN : NamedTextColor.RED),
                List.of(Component.text("断线时保留最后行情，但禁止依赖旧价成交", NamedTextColor.GRAY))));
        view.connection()[0] = connected;
    }

    private void updateSlot(View view, int slot, Quote quote) {
        if (quote == null) return;
        long age = Math.max(0, System.currentTimeMillis() - quote.sourceTimestamp());
        boolean stale = age > Duration.ofSeconds(15).toMillis();
        boolean rising = quote.previousClose() > 0 && quote.lastPrice() >= quote.previousClose();
        Material material = stale ? Material.YELLOW_STAINED_GLASS_PANE
                : rising ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
        NamedTextColor color = stale ? NamedTextColor.YELLOW : rising ? NamedTextColor.RED : NamedTextColor.GREEN;
        double change = quote.previousClose() == 0 ? 0
                : (quote.lastPrice() - quote.previousClose()) * 100.0d / quote.previousClose();
        var lore = new ArrayList<Component>();
        lore.add(Component.text("最新 " + Formatters.price(quote.symbol(), quote.lastPrice()), color));
        lore.add(Component.text(String.format(java.util.Locale.ROOT, "涨跌 %+.2f%%", change), color));
        lore.add(Component.text("买一/卖一 " + Formatters.price(quote.symbol(), quote.bid1Price())
                + " / " + Formatters.price(quote.symbol(), quote.ask1Price()), NamedTextColor.GRAY));
        lore.add(Component.text("序列 " + quote.sequence() + " · " + quote.source(), NamedTextColor.DARK_GRAY));
        if (stale) lore.add(Component.text("行情延迟，仅供查看", NamedTextColor.YELLOW));
        view.holder().inventory.setItem(slot, item(material, Component.text(quote.symbol(), color), lore));
        view.sequences()[slot] = quote.sequence();
    }

    private List<String> selectedSymbols() {
        List<Quote> snapshot = stream.snapshot();
        List<String> selected = Stream.of("SH.", "SZ.", "HK.", "US.")
                .flatMap(prefix -> snapshot.stream()
                        .filter(quote -> quote.symbol().startsWith(prefix))
                        .limit(prefix.equals("SH.") || prefix.equals("SZ.") ? 4 : 5))
                .limit(STOCK_SLOTS)
                .map(Quote::symbol)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (Quote quote : snapshot) {
            if (selected.size() >= STOCK_SLOTS) break;
            if (!selected.contains(quote.symbol())) selected.add(quote.symbol());
        }
        return selected;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    @Override
    public void close() {
        refreshTask.cancel();
        views.clear();
    }

    private record View(MarketHolder holder, List<String> symbols, long[] sequences, boolean[] connection) {
    }

    private static final class MarketHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
