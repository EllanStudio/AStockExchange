package dev.astock.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerNoticeListener implements Listener {
    @EventHandler
    public void join(PlayerJoinEvent event) {
        event.getPlayer().sendMessage(Component.text(
                "[A股模拟] 仅使用服务器游戏币，不代表真实证券所有权；行情可能延迟，且不构成投资建议。",
                NamedTextColor.YELLOW));
    }
}
