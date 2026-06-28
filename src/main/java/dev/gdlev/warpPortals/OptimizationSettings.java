package dev.gdlev.warpPortals;

import org.bukkit.plugin.java.JavaPlugin;

public record OptimizationSettings(
        boolean cacheWarps,
        boolean sharedParticleScheduler,
        boolean portalSpatialIndex,
        boolean cachePortalCalculations,
        boolean cacheSettings,
        boolean cacheVaultProvider
) {
    public static OptimizationSettings from(JavaPlugin plugin) {
        return new OptimizationSettings(
                plugin.getConfig().getBoolean("optimizations.cache-warps", true),
                plugin.getConfig().getBoolean("optimizations.shared-particle-scheduler", true),
                plugin.getConfig().getBoolean("optimizations.portal-spatial-index", true),
                plugin.getConfig().getBoolean("optimizations.cache-portal-calculations", true),
                plugin.getConfig().getBoolean("optimizations.cache-settings", true),
                plugin.getConfig().getBoolean("optimizations.cache-vault-provider", true)
        );
    }
}
