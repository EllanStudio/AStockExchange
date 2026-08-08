package dev.astock.paper.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class VaultEconomyBridge implements EconomyBridge {
    private final Object provider;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public VaultEconomyBridge() {
        Object resolved = null;
        try {
            Class economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyClass);
            if (registration != null) resolved = registration.getProvider();
        } catch (ClassNotFoundException ignored) {
            // Vault is optional; transfer commands will report it as unavailable.
        }
        this.provider = resolved;
    }

    @Override
    public boolean available() {
        return provider != null;
    }

    @Override
    public EconomyResult withdraw(OfflinePlayer player, double amount) {
        return invoke("withdrawPlayer", player, amount);
    }

    @Override
    public EconomyResult deposit(OfflinePlayer player, double amount) {
        return invoke("depositPlayer", player, amount);
    }

    private EconomyResult invoke(String name, OfflinePlayer player, double amount) {
        if (provider == null) return EconomyResult.failure("Vault economy provider is unavailable");
        try {
            Method method = findMethod(name, player);
            Object response = method.invoke(provider, player, amount);
            Method success = response.getClass().getMethod("transactionSuccess");
            if (Boolean.TRUE.equals(success.invoke(response))) return EconomyResult.ok();
            return EconomyResult.failure(readError(response));
        } catch (ReflectiveOperationException exception) {
            return EconomyResult.failure("Vault API call failed: " + exception.getMessage());
        }
    }

    private Method findMethod(String name, OfflinePlayer player) throws NoSuchMethodException {
        for (Method method : provider.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 2
                    && parameters[0].isAssignableFrom(player.getClass())
                    && (parameters[1] == double.class || parameters[1] == Double.class)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name + "(OfflinePlayer,double)");
    }

    private static String readError(Object response) {
        try {
            Field field = response.getClass().getField("errorMessage");
            Object value = field.get(response);
            return value == null ? "economy operation failed" : value.toString();
        } catch (ReflectiveOperationException ignored) {
            return "economy operation failed";
        }
    }
}
