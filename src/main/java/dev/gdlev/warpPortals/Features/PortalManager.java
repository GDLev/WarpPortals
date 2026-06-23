package dev.gdlev.warpPortals.Features;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PortalManager {

    private final WarpPortals plugin;
    private final CopyOnWriteArrayList<PortalPair> portalPairs = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<PortalKey, SharedExitPortal> sharedExitPortals = new HashMap<>();
    private static final long CLOSE_AFTER_OWNER_USE_DELAY_TICKS = 5L;
    private BukkitTask task;
    private long currentTick;

    public PortalManager(WarpPortals plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        stop();
        start();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        portalPairs.clear();
        sharedExitPortals.values().forEach(portal -> {
            if (portal.display().isValid()) {
                portal.display().remove();
            }
        });
        sharedExitPortals.clear();
        cooldowns.clear();
    }

    public void registerPair(
            Player owner,
            Location entranceCenter,
            float entranceRotation,
            Location exitBottom,
            float exitRotation,
            BlockDisplay entranceDisplay
    ) {
        registerPair(owner, entranceCenter, entranceRotation, exitBottom, exitRotation, entranceDisplay, null);
    }

    public void registerPair(
            Player owner,
            Location entranceCenter,
            float entranceRotation,
            Location exitBottom,
            float exitRotation,
            BlockDisplay entranceDisplay,
            String teleportMessage
    ) {
        PortalTeleportSettings settings = PortalTeleportSettings.from(plugin);
        long removeAtTick = currentTick() + settings.portalLifetimeTicks();
        SharedExitPortal exitPortal = getOrCreateExitPortal(exitBottom, exitRotation, removeAtTick);

        portalPairs.add(new PortalPair(
                owner.getUniqueId(),
                entranceCenter.clone(),
                entranceRotation,
                exitPortal,
                entranceCenter.clone().subtract(0, settings.height() / 2.0, 0),
                removeAtTick,
                settings.onlyOwner(),
                entranceDisplay,
                teleportMessage
        ));
    }

    public boolean preparePortalCreation(Player owner) {
        if (!hasActivePortal(owner.getUniqueId())) {
            return true;
        }

        String mode = plugin.getConfig().getString("portal.protection-mode", "block").toLowerCase(Locale.ROOT);

        if (mode.equals("replace")) {
            closeOwnerPortals(owner.getUniqueId());
            return true;
        }

        owner.sendMessage(plugin.lang("messages.portal-already-active"));
        return false;
    }

    private void start() {
        PortalTeleportSettings settings = PortalTeleportSettings.from(plugin);
        currentTick = 0L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, settings.checkIntervalTicks());
    }

    private void tick() {
        PortalTeleportSettings settings = PortalTeleportSettings.from(plugin);
        currentTick += settings.checkIntervalTicks();
        long now = currentTick();
        cleanupExpiredPortals(now);

        for (Player player : Bukkit.getOnlinePlayers()) {
            Long cooldownUntil = cooldowns.get(player.getUniqueId());

            if (cooldownUntil != null && cooldownUntil > now) {
                continue;
            }

            for (PortalPair pair : portalPairs) {
                if (!pair.canUse(player)) {
                    continue;
                }

                Location target = null;

                if (isInsidePortal(player.getLocation(), pair.entranceCenter(), pair.entranceRotation(), settings)) {
                    target = pair.exitPortal().bottom();
                } else if (!settings.oneWay() && isInsidePortal(player.getLocation(), pair.exitPortal().center(), pair.exitPortal().rotation(), settings)) {
                    target = pair.entranceBottom();
                }

                if (target == null) {
                    continue;
                }

                player.teleport(target.clone());
                if (pair.teleportMessage() != null) {
                    player.sendMessage(pair.teleportMessage());
                }
                cooldowns.put(player.getUniqueId(), now + settings.cooldownTicks());

                if (settings.closeAfterOwnerUse() && player.getUniqueId().equals(pair.ownerId())) {
                    closePair(pair);
                }

                break;
            }
        }
    }

    private void cleanupExpiredPortals(long now) {
        Iterator<PortalPair> iterator = portalPairs.iterator();

        while (iterator.hasNext()) {
            PortalPair pair = iterator.next();

            if (pair.removeAtTick() <= now) {
                portalPairs.remove(pair);
            }
        }

        cleanupSharedExitPortals(now);
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean isInsidePortal(Location playerLocation, Location portalCenter, float rotationY, PortalTeleportSettings settings) {
        World world = portalCenter.getWorld();

        if (world == null || playerLocation.getWorld() == null || !world.equals(playerLocation.getWorld())) {
            return false;
        }

        Vector offset = playerLocation.toVector().subtract(portalCenter.toVector());
        Vector local = rotateOffset(offset, -rotationY);

        return Math.abs(local.getX()) <= settings.width() / 2.0
                && Math.abs(local.getY()) <= settings.height() / 2.0
                && Math.abs(local.getZ()) <= settings.activationDepth();
    }

    private Vector rotateOffset(Vector offset, float rotationY) {
        double cos = Math.cos(rotationY);
        double sin = Math.sin(rotationY);
        double x = offset.getX();
        double z = offset.getZ();

        return new Vector(
                x * cos + z * sin,
                offset.getY(),
                -x * sin + z * cos
        );
    }

    private long currentTick() {
        return currentTick;
    }

    private void closePair(PortalPair pair) {
        portalPairs.remove(pair);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ShowPortal.close(plugin, pair.entranceDisplay(), pair.entranceRotation());
            closeSharedExitPortalIfUnused(pair.exitPortal(), 0L);
        }, CLOSE_AFTER_OWNER_USE_DELAY_TICKS);
    }

    private void closePairNow(PortalPair pair) {
        portalPairs.remove(pair);
        ShowPortal.close(plugin, pair.entranceDisplay(), pair.entranceRotation());
        closeSharedExitPortalIfUnused(pair.exitPortal(), 0L);
    }

    private boolean hasActivePortal(UUID ownerId) {
        for (PortalPair pair : portalPairs) {
            if (pair.ownerId().equals(ownerId)) {
                return true;
            }
        }

        return false;
    }

    private void closeOwnerPortals(UUID ownerId) {
        for (PortalPair pair : new ArrayList<>(portalPairs)) {
            if (pair.ownerId().equals(ownerId)) {
                closePairNow(pair);
            }
        }
    }

    private SharedExitPortal getOrCreateExitPortal(Location exitBottom, float exitRotation, long removeAtTick) {
        PortalKey key = PortalKey.from(exitBottom);
        SharedExitPortal existing = sharedExitPortals.get(key);

        if (existing != null && existing.display().isValid()) {
            existing.extendTo(removeAtTick);
            return existing;
        }

        BlockDisplay display = ShowPortal.openPersistentAtFromBottom(plugin, exitBottom, exitBottom.getYaw());
        SharedExitPortal created = new SharedExitPortal(
                key,
                ShowPortal.centerFromBottom(plugin, exitBottom),
                exitBottom.clone(),
                exitRotation,
                display,
                removeAtTick
        );
        sharedExitPortals.put(key, created);
        return created;
    }

    private void cleanupSharedExitPortals(long now) {
        for (SharedExitPortal portal : new ArrayList<>(sharedExitPortals.values())) {
            if (portal.removeAtTick() <= now) {
                closeSharedExitPortalIfUnused(portal, 0L);
            }
        }
    }

    private void closeSharedExitPortalIfUnused(SharedExitPortal portal, long delayTicks) {
        if (hasActivePairUsing(portal)) {
            return;
        }

        if (!sharedExitPortals.remove(portal.key(), portal)) {
            return;
        }

        if (delayTicks <= 0L) {
            ShowPortal.close(plugin, portal.display(), portal.rotation());
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> ShowPortal.close(plugin, portal.display(), portal.rotation()), delayTicks);
    }

    private boolean hasActivePairUsing(SharedExitPortal portal) {
        for (PortalPair pair : portalPairs) {
            if (pair.exitPortal() == portal) {
                return true;
            }
        }

        return false;
    }

    private record PortalPair(
            UUID ownerId,
            Location entranceCenter,
            float entranceRotation,
            SharedExitPortal exitPortal,
            Location entranceBottom,
            long removeAtTick,
            boolean onlyOwner,
            BlockDisplay entranceDisplay,
            String teleportMessage
    ) {
        private boolean canUse(Player player) {
            return !onlyOwner || player.getUniqueId().equals(ownerId);
        }
    }

    private static final class SharedExitPortal {
        private final PortalKey key;
        private final Location center;
        private final Location bottom;
        private final float rotation;
        private final BlockDisplay display;
        private long removeAtTick;

        private SharedExitPortal(PortalKey key, Location center, Location bottom, float rotation, BlockDisplay display, long removeAtTick) {
            this.key = key;
            this.center = center;
            this.bottom = bottom;
            this.rotation = rotation;
            this.display = display;
            this.removeAtTick = removeAtTick;
        }

        private PortalKey key() {
            return key;
        }

        private Location center() {
            return center;
        }

        private Location bottom() {
            return bottom;
        }

        private float rotation() {
            return rotation;
        }

        private BlockDisplay display() {
            return display;
        }

        private long removeAtTick() {
            return removeAtTick;
        }

        private void extendTo(long tick) {
            removeAtTick = Math.max(removeAtTick, tick);
        }
    }

    private record PortalKey(
            UUID worldId,
            long x,
            long y,
            long z,
            int yaw
    ) {
        private static PortalKey from(Location location) {
            World world = location.getWorld();

            return new PortalKey(
                    world == null ? new UUID(0L, 0L) : world.getUID(),
                    Double.doubleToLongBits(location.getX()),
                    Double.doubleToLongBits(location.getY()),
                    Double.doubleToLongBits(location.getZ()),
                    Float.floatToIntBits(location.getYaw())
            );
        }
    }

    private record PortalTeleportSettings(
            double width,
            double height,
            int portalLifetimeTicks,
            long checkIntervalTicks,
            long cooldownTicks,
            double activationDepth,
            boolean onlyOwner,
            boolean oneWay,
            boolean closeAfterOwnerUse
    ) {
        private static PortalTeleportSettings from(WarpPortals plugin) {
            int animationTicks = Math.max(1, plugin.getConfig().getInt("portal.animation-ticks", 10));
            int holdTicks = Math.max(1, plugin.getConfig().getInt("portal.hold-ticks", 60));

            return new PortalTeleportSettings(
                    plugin.getConfig().getDouble("portal.width", 1.25),
                    plugin.getConfig().getDouble("portal.height", 2.5),
                    1 + animationTicks + holdTicks + animationTicks,
                    Math.max(1, plugin.getConfig().getLong("portal.teleport.check-interval-ticks", 2)),
                    Math.max(1, plugin.getConfig().getLong("portal.teleport.cooldown-ticks", 40)),
                    plugin.getConfig().getDouble("portal.teleport.activation-depth", 0.7),
                    plugin.getConfig().getBoolean("portal.teleport.only-owner", true),
                    plugin.getConfig().getBoolean("portal.teleport.one-way", false),
                    plugin.getConfig().getBoolean("portal.teleport.close-after-owner-use", false)
            );
        }
    }
}
