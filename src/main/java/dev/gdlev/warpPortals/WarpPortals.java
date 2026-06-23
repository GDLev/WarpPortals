package dev.gdlev.warpPortals;

import dev.gdlev.warpPortals.Commands.Warp;
import dev.gdlev.warpPortals.Costs.CostService;
import dev.gdlev.warpPortals.Features.CustomCommandManager;
import dev.gdlev.warpPortals.Features.PortalManager;
import dev.gdlev.warpPortals.Features.ShowPortal;
import dev.gdlev.warpPortals.Features.TeleportTimer;
import dev.gdlev.warpPortals.Storage.StorageHelper;
import dev.gdlev.warpPortals.Storage.WarpStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bstats.bukkit.Metrics;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Objects;

public final class WarpPortals extends JavaPlugin {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private File langFile;
    private FileConfiguration langConfig;
    private StorageHelper storage;
    private WarpStorage warpStorage;
    private PortalManager portalManager;
    private TeleportTimer teleportTimer;
    private CostService costService;
    private CustomCommandManager customCommandManager;

    @Override
    public void onEnable() {
        reloadDefaultConfig();
        setupMetrics();
        reloadLang();
        storage = new StorageHelper(this);
        storage.reload();
        warpStorage = new WarpStorage(this, storage);
        warpStorage.reload();
        int removedDisplays = ShowPortal.removeLeftoverDisplays(this);
        if (removedDisplays > 0) {
            getLogger().info("Removed " + removedDisplays + " leftover portal display entities.");
        }
        portalManager = new PortalManager(this);
        portalManager.reload();
        teleportTimer = new TeleportTimer(this);
        costService = new CostService(this);
        customCommandManager = new CustomCommandManager(this);
        getServer().getPluginManager().registerEvents(teleportTimer, this);

        Warp testCommand = new Warp(this);

        Objects.requireNonNull(getCommand("warpportals")).setExecutor(testCommand);
        Objects.requireNonNull(getCommand("warpportals")).setTabCompleter(testCommand);
        Objects.requireNonNull(getCommand("warp")).setExecutor(testCommand);
        Objects.requireNonNull(getCommand("warp")).setTabCompleter(testCommand);
        Objects.requireNonNull(getCommand("portal")).setExecutor(testCommand);
        Objects.requireNonNull(getCommand("portal")).setTabCompleter(testCommand);
        customCommandManager.reload();
    }

    @Override
    public void onDisable() {
        if (portalManager != null) {
            portalManager.stop();
        }

        if (teleportTimer != null) {
            teleportTimer.stop();
        }

        if (customCommandManager != null) {
            customCommandManager.stop();
        }
    }

    public void reloadPluginFiles() {
        reloadDefaultConfig();
        reloadLang();
        storage.reload();
        warpStorage.reload();
        portalManager.reload();
        teleportTimer.stop();
        customCommandManager.reload();
    }

    public FileConfiguration getLangConfig() {
        return langConfig;
    }

    public StorageHelper getStorage() {
        return storage;
    }

    public WarpStorage getWarpStorage() {
        return warpStorage;
    }

    public PortalManager getPortalManager() {
        return portalManager;
    }

    public CostService getCostService() {
        return costService;
    }

    public TeleportTimer getTeleportTimer() {
        return teleportTimer;
    }

    public String lang(String path) {
        return lang(path, path);
    }

    public String lang(String path, String fallback) {
        String message = langConfig.getString(path, fallback);
        return miniMessage(message);
    }

    public String lang(String path, String placeholder, String value) {
        String message = langConfig.getString(path, path).replace(placeholder, value);
        return miniMessage(message);
    }

    public String lang(String path, String... replacements) {
        String message = langConfig.getString(path, path);

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }

        return miniMessage(message);
    }

    public String colorize(String message) {
        return miniMessage(message);
    }

    public String miniMessage(String message) {
        Component component = MINI_MESSAGE.deserialize(message);
        return LEGACY_SERIALIZER.serialize(component);
    }

    private void reloadDefaultConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }

        reloadConfig();

        FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(Objects.requireNonNull(getResource("config.yml")))
        );

        if (copyMissingKeys(getConfig(), defaults)) {
            saveConfig();
            reloadConfig();
        }
    }

    private void reloadLang() {
        ensureBundledMessageFile("en_us");
        ensureBundledMessageFile("pl_pl");

        String language = getConfig().getString("language", "en_us").toLowerCase();
        File messagesFolder = new File(getDataFolder(), "messages");
        langFile = new File(messagesFolder, language + ".yml");

        if (!langFile.exists()) {
            copyMessageDefaults(langFile);
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);
        FileConfiguration englishDefaults = YamlConfiguration.loadConfiguration(new File(messagesFolder, "en_us.yml"));

        if (copyMissingKeys(langConfig, englishDefaults)) {
            saveYaml(langConfig, langFile);
        }

        langConfig.setDefaults(englishDefaults);
    }

    private void ensureBundledMessageFile(String language) {
        File file = new File(getDataFolder(), "messages/" + language + ".yml");

        if (!file.exists()) {
            saveResource("messages/" + language + ".yml", false);
            return;
        }

        FileConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(Objects.requireNonNull(getResource("messages/" + language + ".yml")))
        );

        if (copyMissingKeys(configuration, defaults)) {
            saveYaml(configuration, file);
        }
    }

    private void copyMessageDefaults(File targetFile) {
        FileConfiguration configuration = new YamlConfiguration();
        FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(Objects.requireNonNull(getResource("messages/en_us.yml")))
        );

        copyMissingKeys(configuration, defaults);
        saveYaml(configuration, targetFile);
    }

    private boolean copyMissingKeys(FileConfiguration configuration, FileConfiguration defaults) {
        boolean changed = false;

        for (String key : defaults.getKeys(true)) {
            if (!configuration.isSet(key)) {
                if (defaults.isConfigurationSection(key)) {
                    configuration.createSection(key);
                } else {
                    configuration.set(key, defaults.get(key));
                }
                changed = true;
            }
        }

        return changed;
    }

    private void saveYaml(FileConfiguration configuration, File file) {
        try {
            File parent = file.getParentFile();

            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            configuration.save(file);
        } catch (IOException exception) {
            getLogger().severe("Could not save " + file.getName() + ": " + exception.getMessage());
        }
    }

    private void setupMetrics() {
        if (!getConfig().getBoolean("bstats", true)) {
            getLogger().info("bStats metrics disabled in config.");
            return;
        }

        try {
            int pluginId = 32158;
            new Metrics(this, pluginId);
            getLogger().info("bStats metrics enabled.");
        } catch (Throwable throwable) {
            getLogger().warning("Could not enable bStats metrics: " + throwable.getMessage());
        }
    }
}
