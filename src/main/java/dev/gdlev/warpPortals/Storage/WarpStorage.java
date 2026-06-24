package dev.gdlev.warpPortals.Storage;

import dev.gdlev.warpPortals.Costs.WarpCost;
import dev.gdlev.warpPortals.Costs.CostType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class WarpStorage {

    private final JavaPlugin plugin;
    private final StorageHelper storage;

    public WarpStorage(JavaPlugin plugin, StorageHelper storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void reload() {
        ensurePermissionKeys();
    }

    public void saveWarp(String name, Location location) throws IOException {
        SavedWarp warp = SavedWarp.fromLocation(
                normalizeName(name),
                location,
                WarpCost.fromConfig(plugin, "warp-defaults.costs.teleport"),
                WarpCost.fromConfig(plugin, "warp-defaults.costs.portal")
        );
        saveWarpToYaml(warp);
    }

    public Optional<SavedWarp> findWarp(String name) {
        String normalizedName = normalizeName(name);
        return findWarpInYaml(normalizedName);
    }

    public boolean deleteWarp(String name) throws IOException {
        String normalizedName = normalizeName(name);
        return deleteWarpFromYaml(normalizedName);
    }

    public boolean setWarpCost(String name, String method, CostType type, String amount, Material item) throws IOException {
        String normalizedName = normalizeName(name);
        String normalizedMethod = method.toLowerCase();

        if (!normalizedMethod.equals("teleport") && !normalizedMethod.equals("portal")) {
            return false;
        }

        FileConfiguration configuration = storage.loadYamlStorage();
        String warpPath = "warps." + normalizedName;

        if (!configuration.contains(warpPath)) {
            return false;
        }

        saveCost(configuration, warpPath + ".costs." + normalizedMethod, WarpCost.of(type != CostType.NONE, type, amount, item));
        storage.saveYamlStorage(configuration);
        return true;
    }

    public boolean setWarpPermission(String name, String permission) throws IOException {
        String normalizedName = normalizeName(name);
        FileConfiguration configuration = storage.loadYamlStorage();
        String warpPath = "warps." + normalizedName;

        if (!configuration.contains(warpPath)) {
            return false;
        }

        configuration.set(warpPath + ".permission", permission == null ? "" : permission);
        storage.saveYamlStorage(configuration);
        return true;
    }

    public List<String> listWarpNames() {
        return listWarpNamesFromYaml();
    }

    private void saveWarpToYaml(SavedWarp warp) throws IOException {
        FileConfiguration configuration = storage.loadYamlStorage();
        String path = "warps." + warp.name();

        configuration.set(path + ".world", warp.worldName());
        configuration.set(path + ".x", warp.x());
        configuration.set(path + ".y", warp.y());
        configuration.set(path + ".z", warp.z());
        configuration.set(path + ".yaw", warp.yaw());
        configuration.set(path + ".pitch", warp.pitch());
        configuration.set(path + ".permission", warp.permission());
        saveCost(configuration, path + ".costs.teleport", warp.teleportCost());
        saveCost(configuration, path + ".costs.portal", warp.portalCost());

        storage.saveYamlStorage(configuration);
    }

    private Optional<SavedWarp> findWarpInYaml(String name) {
        FileConfiguration configuration = storage.loadYamlStorage();
        String path = "warps." + name;

        if (!configuration.contains(path)) {
            return Optional.empty();
        }

        return Optional.of(new SavedWarp(
                name,
                configuration.getString(path + ".world", ""),
                configuration.getDouble(path + ".x"),
                configuration.getDouble(path + ".y"),
                configuration.getDouble(path + ".z"),
                (float) configuration.getDouble(path + ".yaw"),
                (float) configuration.getDouble(path + ".pitch"),
                configuration.getString(path + ".permission", ""),
                loadCost(configuration, path + ".costs.teleport", "warp-defaults.costs.teleport"),
                loadCost(configuration, path + ".costs.portal", "warp-defaults.costs.portal")
        ));
    }

    private boolean deleteWarpFromYaml(String name) throws IOException {
        FileConfiguration configuration = storage.loadYamlStorage();
        String path = "warps." + name;

        if (!configuration.contains(path)) {
            return false;
        }

        configuration.set(path, null);
        storage.saveYamlStorage(configuration);
        return true;
    }

    private List<String> listWarpNamesFromYaml() {
        FileConfiguration configuration = storage.loadYamlStorage();
        ConfigurationSection section = configuration.getConfigurationSection("warps");

        if (section == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(section.getKeys(false));
    }

    private void ensurePermissionKeys() {
        FileConfiguration configuration = storage.loadYamlStorage();
        ConfigurationSection section = configuration.getConfigurationSection("warps");

        if (section == null) {
            return;
        }

        boolean changed = false;

        for (String warpName : section.getKeys(false)) {
            String path = "warps." + warpName + ".permission";

            if (!configuration.isSet(path)) {
                configuration.set(path, "");
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        try {
            storage.saveYamlStorage(configuration);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not update warp permissions in storage: " + exception.getMessage());
        }
    }

    private void saveCost(FileConfiguration configuration, String path, WarpCost cost) {
        configuration.set(path + ".enabled", cost.enabled());
        configuration.set(path + ".type", cost.type().name().toLowerCase());
        configuration.set(path + ".amount", cost.amount());
        configuration.set(path + ".item", cost.item().name());
    }

    private WarpCost loadCost(FileConfiguration configuration, String warpPath, String defaultPath) {
        ConfigurationSection section = configuration.getConfigurationSection(warpPath);

        if (section != null) {
            return WarpCost.fromSection(section);
        }

        return WarpCost.fromConfig(plugin, defaultPath);
    }

    private String normalizeName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_-]", "");
    }
}
