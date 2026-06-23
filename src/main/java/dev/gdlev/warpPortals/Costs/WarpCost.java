package dev.gdlev.warpPortals.Costs;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public record WarpCost(
        boolean enabled,
        CostType type,
        String amount,
        Material item
) {
    public static WarpCost free() {
        return new WarpCost(false, CostType.NONE, "0", Material.AIR);
    }

    public static WarpCost fromConfig(JavaPlugin plugin, String path) {
        return fromSection(plugin.getConfig().getConfigurationSection(path));
    }

    public static WarpCost of(boolean enabled, CostType type, String amount, Material item) {
        Material material = item == null ? Material.DIAMOND : item;
        return new WarpCost(enabled, type, amount, material);
    }

    public static WarpCost fromSection(ConfigurationSection section) {
        if (section == null) {
            return free();
        }

        Material material = Material.matchMaterial(section.getString("item", "DIAMOND"));

        if (material == null) {
            material = Material.DIAMOND;
        }

        return new WarpCost(
                section.getBoolean("enabled", false),
                CostType.fromConfig(section.getString("type", "none")),
                String.valueOf(section.get("amount", "0")),
                material
        );
    }

    public String displayAmount() {
        return switch (type) {
            case XP -> parsedAmount() + " XP";
            case LEVEL -> parsedAmount() + " levels";
            case VAULT -> amount;
            case ITEM -> parsedAmount() + "x " + item.name();
            case NONE -> "free";
        };
    }

    public int parsedAmount() {
        try {
            return Math.max(0, (int) Math.ceil(Double.parseDouble(amount.trim())));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
