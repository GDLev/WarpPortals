package dev.gdlev.warpPortals.Features;

import dev.gdlev.warpPortals.Costs.WarpCost;
import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CustomCommandManager {

    private final WarpPortals plugin;
    private final List<Command> registeredCommands = new ArrayList<>();
    private final Map<String, Map<UUID, Long>> cooldowns = new ConcurrentHashMap<>();

    public CustomCommandManager(WarpPortals plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        unregisterAll();
        cooldowns.clear();
        registerConfiguredCommands();
        refreshCommandTree();
    }

    public void stop() {
        unregisterAll();
        cooldowns.clear();
    }

    private void registerConfiguredCommands() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("custom-commands");

        if (section == null) {
            return;
        }

        CommandMap commandMap = getCommandMap();

        if (commandMap == null) {
            plugin.getLogger().warning("Could not register custom commands: command map is unavailable.");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection commandSection = section.getConfigurationSection(key);

            if (commandSection == null) {
                continue;
            }

            CustomCommandSettings settings = loadSettings(key, commandSection, commandMap);

            if (settings == null) {
                continue;
            }

            CustomWarpCommand command = new CustomWarpCommand(settings);
            commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command);
            registeredCommands.add(command);
        }
    }

    private CustomCommandSettings loadSettings(String key, ConfigurationSection section, CommandMap commandMap) {
        String commandName = normalizeCommandName(key);

        if (commandName.isBlank()) {
            plugin.getLogger().warning("Skipped custom command '" + key + "' because its name is invalid.");
            return null;
        }

        if (commandMap.getCommand(commandName) != null) {
            plugin.getLogger().warning("Skipped custom command '/" + commandName + "' because another command already uses that name.");
            return null;
        }

        String warpName = section.getString("warp", commandName);
        String mode = section.getString("mode", "direct").toLowerCase(Locale.ROOT);

        if (!mode.equals("direct") && !mode.equals("portal")) {
            plugin.getLogger().warning("Skipped custom command '/" + commandName + "' because mode must be direct or portal.");
            return null;
        }

        List<String> aliases = new ArrayList<>();

        for (String alias : section.getStringList("aliases")) {
            String normalizedAlias = normalizeCommandName(alias);

            if (normalizedAlias.isBlank()) {
                continue;
            }

            if (commandMap.getCommand(normalizedAlias) != null) {
                plugin.getLogger().warning("Skipped alias '/" + normalizedAlias + "' for custom command '/" + commandName + "' because it is already used.");
                continue;
            }

            aliases.add(normalizedAlias);
        }

        WarpCost cost = WarpCost.fromSection(section.getConfigurationSection("cost"));
        double delaySeconds = Math.max(0.0, section.getDouble("delay-seconds", 0.0));
        long cooldownMillis = Math.max(0L, Math.round(section.getDouble("cooldown-seconds", 0.0) * 1000.0));
        String message = section.getString("message", "");

        return new CustomCommandSettings(commandName, aliases, warpName, mode, cost, delaySeconds, cooldownMillis, message);
    }

    private String normalizeCommandName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceFirst("^/+", "").replaceAll("[^a-z0-9_-]", "");
    }

    private boolean execute(CustomCommandSettings settings, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang("messages.player-only"));
            return true;
        }

        String permission = settings.mode().equals("portal") ? "warpportals.command.portal" : "warpportals.command.teleport";

        if (!player.hasPermission(permission)) {
            player.sendMessage(plugin.lang("messages.no-permission"));
            return true;
        }

        if (isOnCooldown(player, settings)) {
            return true;
        }

        try {
            plugin.getWarpStorage().findWarp(settings.warpName()).ifPresentOrElse(warp -> {
                if (!warp.canUse(player)) {
                    player.sendMessage(plugin.lang("messages.warp-no-permission"));
                    return;
                }

                var location = warp.toLocation();

                if (location.isEmpty()) {
                    player.sendMessage(plugin.lang("messages.warp-world-missing", "{name}", settings.warpName()));
                    return;
                }

                if (settings.mode().equals("portal")) {
                    openPortal(player, settings, location.get());
                    return;
                }

                plugin.getTeleportTimer().start(
                        player,
                        settings.warpName(),
                        location.get(),
                        settings.cost(),
                        successMessage(settings),
                        settings.delaySeconds()
                );
                startCooldown(player, settings);
            }, () -> player.sendMessage(plugin.lang("messages.warp-not-found", "{name}", settings.warpName())));
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not use custom command '/" + settings.commandName() + "': " + exception.getMessage());
            player.sendMessage(plugin.lang("messages.storage-error"));
        }

        return true;
    }

    private void openPortal(Player player, CustomCommandSettings settings, Location exitBottom) {
        Location entranceCenter = ShowPortal.centerInFrontOfPlayer(plugin, player);

        if (plugin.getPortalManager().isBelowMinimumDistance(entranceCenter, exitBottom)) {
            player.sendMessage(plugin.lang(
                    "messages.portal-too-close",
                    "{distance}", plugin.getPortalManager().minimumPortalDistanceDisplay()
            ));
            return;
        }

        if (!plugin.getPortalManager().preparePortalCreation(player)) {
            return;
        }

        if (!plugin.getCostService().charge(player, settings.cost())) {
            return;
        }

        float entranceRotation = ShowPortal.rotationToFace(entranceCenter, player.getLocation());
        float exitRotation = ShowPortal.rotationFromYaw(exitBottom.getYaw());
        BlockDisplay entranceDisplay = ShowPortal.openFacing(plugin, entranceCenter, player.getLocation());

        plugin.getPortalManager().registerPair(
                player,
                entranceCenter,
                entranceRotation,
                exitBottom,
                exitRotation,
                entranceDisplay,
                successMessage(settings)
        );
        startCooldown(player, settings);
    }

    private String successMessage(CustomCommandSettings settings) {
        if (settings.message() == null || settings.message().isBlank()) {
            return plugin.lang("messages.warp-teleported", "{name}", settings.warpName());
        }

        return plugin.colorize(settings.message()
                .replace("{name}", settings.warpName())
                .replace("{warp}", settings.warpName())
                .replace("{command}", settings.commandName()));
    }

    private boolean isOnCooldown(Player player, CustomCommandSettings settings) {
        if (settings.cooldownMillis() <= 0L) {
            return false;
        }

        long now = System.currentTimeMillis();
        long expiresAt = cooldowns
                .getOrDefault(settings.commandName(), Collections.emptyMap())
                .getOrDefault(player.getUniqueId(), 0L);

        if (expiresAt <= now) {
            return false;
        }

        double seconds = (expiresAt - now) / 1000.0;
        player.sendMessage(plugin.lang("messages.custom-command-cooldown", "{time}", formatCooldown(seconds)));
        return true;
    }

    private void startCooldown(Player player, CustomCommandSettings settings) {
        if (settings.cooldownMillis() <= 0L) {
            return;
        }

        cooldowns
                .computeIfAbsent(settings.commandName(), ignored -> new ConcurrentHashMap<>())
                .put(player.getUniqueId(), System.currentTimeMillis() + settings.cooldownMillis());
    }

    private String formatCooldown(double seconds) {
        if (seconds == Math.floor(seconds)) {
            return String.valueOf((int) seconds);
        }

        return String.format(Locale.US, "%.1f", seconds);
    }

    private void unregisterAll() {
        CommandMap commandMap = getCommandMap();

        if (commandMap == null) {
            registeredCommands.clear();
            return;
        }

        for (Command command : registeredCommands) {
            command.unregister(commandMap);
            removeKnownCommand(commandMap, command);
        }

        registeredCommands.clear();
    }

    private void refreshCommandTree() {
        try {
            Method method = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            method.setAccessible(true);
            method.invoke(Bukkit.getServer());
        } catch (NoSuchMethodException ignored) {
            // Spigot API versions differ here; player command trees are still refreshed below.
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not sync custom commands: " + exception.getMessage());
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    private CommandMap getCommandMap() {
        try {
            Method method = Bukkit.getServer().getClass().getDeclaredMethod("getCommandMap");
            method.setAccessible(true);
            return (CommandMap) method.invoke(Bukkit.getServer());
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not access Bukkit command map: " + exception.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void removeKnownCommand(CommandMap commandMap, Command command) {
        if (!(commandMap instanceof SimpleCommandMap simpleCommandMap)) {
            return;
        }

        try {
            Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
            field.setAccessible(true);
            Map<String, Command> knownCommands = (Map<String, Command>) field.get(simpleCommandMap);

            for (String key : new ArrayList<>(knownCommands.keySet())) {
                if (knownCommands.get(key) == command) {
                    knownCommands.remove(key);
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not unregister custom command '/" + command.getName() + "': " + exception.getMessage());
        }
    }

    private final class CustomWarpCommand extends Command {

        private final CustomCommandSettings settings;

        private CustomWarpCommand(CustomCommandSettings settings) {
            super(settings.commandName(), "WarpPortals custom command.", "/" + settings.commandName(), settings.aliases());
            this.settings = settings;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
            return CustomCommandManager.this.execute(settings, sender);
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
            return Collections.emptyList();
        }
    }

    private record CustomCommandSettings(
            String commandName,
            List<String> aliases,
            String warpName,
            String mode,
            WarpCost cost,
            double delaySeconds,
            long cooldownMillis,
            String message
    ) {
    }
}
