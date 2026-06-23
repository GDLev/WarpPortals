package dev.gdlev.warpPortals.Storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public record StorageSettings(
        StorageType type,
        File ymlFile
) {
    public static StorageSettings from(JavaPlugin plugin) {
        String configuredType = plugin.getConfig().getString("storage.type", "yml");

        if (!"yml".equalsIgnoreCase(configuredType)) {
            plugin.getLogger().warning("Only YML storage is currently supported. Falling back to YML.");
        }

        return new StorageSettings(
                StorageType.fromConfig(configuredType),
                pluginDataFile(plugin, plugin.getConfig().getString("storage.yml.file", "storage.yml"))
        );
    }

    public boolean isYaml() {
        return type == StorageType.YML;
    }

    private static File pluginDataFile(JavaPlugin plugin, String path) {
        File file = new File(path);

        if (file.isAbsolute()) {
            return file;
        }

        return new File(plugin.getDataFolder(), path);
    }
}
