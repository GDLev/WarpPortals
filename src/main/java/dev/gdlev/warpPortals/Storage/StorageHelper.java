package dev.gdlev.warpPortals.Storage;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class StorageHelper {

    private final JavaPlugin plugin;
    private StorageSettings settings;

    public StorageHelper(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        settings = StorageSettings.from(plugin);
        ensureStorageDirectories();
    }

    public StorageSettings settings() {
        return settings;
    }

    public StorageType type() {
        return settings.type();
    }

    public boolean isYaml() {
        return true;
    }

    public File ymlFile() {
        return settings.ymlFile();
    }

    public FileConfiguration loadYamlStorage() {
        ensureFile(settings.ymlFile());
        return YamlConfiguration.loadConfiguration(settings.ymlFile());
    }

    public void saveYamlStorage(FileConfiguration configuration) throws IOException {
        ensureParentDirectory(settings.ymlFile());
        configuration.save(settings.ymlFile());
    }

    private void ensureStorageDirectories() {
        ensureFile(settings.ymlFile());
    }

    private void ensureParentDirectory(File file) {
        File parent = file.getParentFile();

        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private void ensureFile(File file) {
        ensureParentDirectory(file);

        if (file.exists()) {
            return;
        }

        try {
            file.createNewFile();
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not create storage file " + file.getName() + ": " + exception.getMessage());
        }
    }
}
