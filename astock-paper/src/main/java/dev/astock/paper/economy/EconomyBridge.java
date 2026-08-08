package dev.astock.paper.economy;

import org.bukkit.OfflinePlayer;

public interface EconomyBridge {
    boolean available();

    EconomyResult withdraw(OfflinePlayer player, double amount);

    EconomyResult deposit(OfflinePlayer player, double amount);

    record EconomyResult(boolean success, String message) {
        public static EconomyResult ok() {
            return new EconomyResult(true, "OK");
        }

        public static EconomyResult failure(String message) {
            return new EconomyResult(false, message);
        }
    }
}
