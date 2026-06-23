package dev.gdlev.warpPortals.Features;

import dev.gdlev.warpPortals.Costs.WarpCost;
import dev.gdlev.warpPortals.WarpPortals;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TeleportTimer implements Listener {

    private final WarpPortals plugin;
    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();

    public TeleportTimer(WarpPortals plugin) {
        this.plugin = plugin;
    }

    public void start(Player player, String warpName, Location destination, WarpCost cost) {
        start(player, warpName, destination, cost, null);
    }

    public void start(Player player, String warpName, Location destination, WarpCost cost, String successMessage) {
        start(player, warpName, destination, cost, successMessage, plugin.getConfig().getDouble("teleport.delay-seconds", 0.0));
    }

    public void start(Player player, String warpName, Location destination, WarpCost cost, String successMessage, double delaySeconds) {
        PendingTeleport pendingTeleport = pendingTeleports.get(player.getUniqueId());

        if (pendingTeleport != null && isSameDestination(pendingTeleport.destination(), destination)) {
            player.sendMessage(plugin.lang("messages.teleport-already-pending"));
            return;
        }

        cancel(player, false);

        long delayTicks = Math.max(0L, Math.round(delaySeconds * 20.0));

        if (delayTicks <= 0L) {
            complete(player, warpName, destination, cost, successMessage);
            return;
        }

        player.sendMessage(plugin.lang(
                "messages.teleport-started",
                "{name}", warpName,
                "{time}", formatTime(delayTicks)
        ));

        BukkitTask task = new BukkitRunnable() {
            private long remainingTicks = delayTicks;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    TeleportTimer.this.cancel(player, false);
                    return;
                }

                if (remainingTicks <= 0L) {
                    pendingTeleports.remove(player.getUniqueId());
                    complete(player, warpName, destination, cost, successMessage);
                    cancel();
                    return;
                }

                sendCountdown(player, remainingTicks);
                remainingTicks -= 20L;
            }
        }.runTaskTimer(plugin, 0L, 20L);

        pendingTeleports.put(player.getUniqueId(), new PendingTeleport(player.getLocation().clone(), destination.clone(), task));
    }

    public void stop() {
        for (PendingTeleport pendingTeleport : pendingTeleports.values()) {
            pendingTeleport.task().cancel();
        }

        pendingTeleports.clear();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("teleport.cancel-on-move", true)) {
            return;
        }

        PendingTeleport pendingTeleport = pendingTeleports.get(event.getPlayer().getUniqueId());

        if (pendingTeleport == null || event.getTo() == null || !hasMoved(pendingTeleport.startLocation(), event.getTo())) {
            return;
        }

        cancel(event.getPlayer(), true);
    }

    private void complete(Player player, String warpName, Location destination, WarpCost cost, String successMessage) {
        if (!plugin.getCostService().charge(player, cost)) {
            return;
        }

        player.teleport(destination.clone());
        playTeleportSound(player);
        player.sendMessage(successMessage == null ? plugin.lang("messages.warp-teleported", "{name}", warpName) : successMessage);
    }

    private void playTeleportSound(Player player) {
        playConfiguredSound(
                player,
                "teleport.sounds.complete",
                "ENTITY_EXPERIENCE_ORB_PICKUP",
                1.0,
                2.0,
                "teleport"
        );
    }

    private void playCancelSound(Player player) {
        playConfiguredSound(
                player,
                "teleport.sounds.cancel",
                "BLOCK_NOTE_BLOCK_BASS",
                1.0,
                0.6,
                "teleport cancel"
        );
    }

    private void playCountdownSound(Player player) {
        playConfiguredSound(
                player,
                "teleport.sounds.countdown",
                "minecraft:ui.button.click",
                1.0,
                2.0,
                "teleport countdown"
        );
    }

    private void playConfiguredSound(
            Player player,
            String sectionPath,
            String defaultSound,
            double defaultVolume,
            double defaultPitch,
            String warningName
    ) {
        String soundName = plugin.getConfig().getString(sectionPath + ".sound", defaultSound);

        if (soundName == null || soundName.equalsIgnoreCase("none")) {
            return;
        }

        float volume = (float) plugin.getConfig().getDouble(sectionPath + ".volume", defaultVolume);
        float pitch = (float) plugin.getConfig().getDouble(sectionPath + ".pitch", defaultPitch);
        String normalizedSoundName = soundName.trim();

        try {
            if (normalizedSoundName.contains(":") || normalizedSoundName.contains(".")) {
                player.playSound(player.getLocation(), normalizedSoundName, volume, pitch);
                return;
            }

            Sound sound = Sound.valueOf(normalizedSoundName.toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid " + warningName + " sound in config.yml: " + soundName);
        }
    }

    private void cancel(Player player, boolean notify) {
        PendingTeleport pendingTeleport = pendingTeleports.remove(player.getUniqueId());

        if (pendingTeleport != null) {
            pendingTeleport.task().cancel();
        }

        if (notify) {
            playCancelSound(player);
            player.sendMessage(plugin.lang("messages.teleport-cancelled-move"));
        }
    }

    private void sendCountdown(Player player, long remainingTicks) {
        String time = formatTime(remainingTicks);

        playCountdownSound(player);

        if (plugin.getConfig().getBoolean("teleport.title", true)) {
            player.sendTitle(
                    plugin.lang("messages.teleport-title"),
                    plugin.lang("messages.teleport-subtitle", "{time}", time),
                    0,
                    25,
                    5
            );
        }

        if (plugin.getConfig().getBoolean("teleport.actionbar", true)) {
            player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(plugin.lang("messages.teleport-actionbar", "{time}", time))
            );
        }
    }

    private boolean hasMoved(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return true;
        }

        return from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
    }

    private boolean isSameDestination(Location first, Location second) {
        if (first.getWorld() == null || second.getWorld() == null || !first.getWorld().equals(second.getWorld())) {
            return false;
        }

        return first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ()
                && first.getYaw() == second.getYaw()
                && first.getPitch() == second.getPitch();
    }

    private String formatTime(long ticks) {
        double seconds = ticks / 20.0;

        if (seconds == Math.floor(seconds)) {
            return String.valueOf((int) seconds);
        }

        return String.format("%.1f", seconds);
    }

    private record PendingTeleport(Location startLocation, Location destination, BukkitTask task) {
    }
}
