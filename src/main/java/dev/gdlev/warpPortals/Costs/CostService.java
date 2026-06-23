package dev.gdlev.warpPortals.Costs;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

public final class CostService {

    private final WarpPortals plugin;

    public CostService(WarpPortals plugin) {
        this.plugin = plugin;
    }

    public boolean charge(Player player, WarpCost cost) {
        if (!cost.enabled() || cost.type() == CostType.NONE || player.hasPermission("warpportals.cost.bypass")) {
            return true;
        }

        boolean charged = switch (cost.type()) {
            case XP -> chargeXp(player, cost);
            case LEVEL -> chargeLevels(player, cost.parsedAmount());
            case ITEM -> chargeItem(player, cost);
            case VAULT -> chargeVault(player, cost);
            case NONE -> true;
        };

        if (!charged) {
            player.sendMessage(plugin.lang("messages.cost-not-enough", "{cost}", cost.displayAmount()));
        }

        return charged;
    }

    private boolean chargeXp(Player player, WarpCost cost) {
        int amount = cost.parsedAmount();
        int totalExperience = getTotalExperience(player);

        if (totalExperience < amount) {
            return false;
        }

        setTotalExperience(player, totalExperience - amount);
        return true;
    }

    private boolean chargeLevels(Player player, int amount) {
        if (player.getLevel() < amount) {
            return false;
        }

        player.setLevel(player.getLevel() - amount);
        return true;
    }

    private boolean chargeItem(Player player, WarpCost cost) {
        int amount = cost.parsedAmount();
        ItemStack required = new ItemStack(cost.item(), amount);

        if (!player.getInventory().containsAtLeast(required, amount)) {
            return false;
        }

        player.getInventory().removeItem(required);
        return true;
    }

    private boolean chargeVault(Player player, WarpCost cost) {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration = plugin.getServer().getServicesManager().getRegistration(economyClass);

            if (registration == null) {
                player.sendMessage(plugin.lang("messages.vault-unavailable"));
                return false;
            }

            Object economy = registration.getProvider();
            double amount = Double.parseDouble(cost.amount());
            Method has = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            Method withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);

            if (!(boolean) has.invoke(economy, player, amount)) {
                return false;
            }

            withdraw.invoke(economy, player, amount);
            return true;
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not charge Vault cost: " + exception.getMessage());
            player.sendMessage(plugin.lang("messages.vault-unavailable"));
            return false;
        }
    }

    private int getTotalExperience(Player player) {
        int level = player.getLevel();
        int experience = Math.round(player.getExp() * getExperienceToNextLevel(level));

        return getExperienceAtLevel(level) + experience;
    }

    private void setTotalExperience(Player player, int experience) {
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0f);

        int level = 0;
        int remainingExperience = Math.max(0, experience);

        while (remainingExperience >= getExperienceToNextLevel(level)) {
            remainingExperience -= getExperienceToNextLevel(level);
            level++;
        }

        player.setLevel(level);
        player.setExp(remainingExperience / (float) getExperienceToNextLevel(level));
        player.setTotalExperience(experience);
    }

    private int getExperienceAtLevel(int level) {
        if (level <= 16) {
            return level * level + 6 * level;
        }

        if (level <= 31) {
            return (int) (2.5 * level * level - 40.5 * level + 360);
        }

        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }

    private int getExperienceToNextLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        }

        if (level <= 30) {
            return 5 * level - 38;
        }

        return 9 * level - 158;
    }
}
