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
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PortalManager {

    private final WarpPortals plugin;
    private final CopyOnWriteArrayList<PortalPair> portalPairs = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<PortalKey, SharedExitPortal> sharedExitPortals = new HashMap<>();
    private final Map<UUID, Map<Long, List<PortalSide>>> portalIndex = new HashMap<>();
    private static final long CLOSE_AFTER_OWNER_USE_DELAY_TICKS = 5L;
    private BukkitTask task;
    private long currentTick;
    private PortalTeleportSettings cachedSettings;

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

        portalPairs.forEach(pair -> {
            if (pair.entranceDisplay() != null && pair.entranceDisplay().isValid()) {
                pair.entranceDisplay().remove();
            }
        });
        portalPairs.clear();
        sharedExitPortals.values().forEach(portal -> {
            if (portal.display().isValid()) {
                portal.display().remove();
            }
        });
        sharedExitPortals.clear();
        portalIndex.clear();
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
        PortalTeleportSettings settings = settings();
        long removeAtTick = currentTick() + settings.portalLifetimeTicks();
        SharedExitPortal exitPortal = getOrCreateExitPortal(exitBottom, exitRotation, removeAtTick);

        PortalPair pair = new PortalPair(
                owner.getUniqueId(),
                entranceCenter.clone(),
                entranceRotation,
                PortalArea.create(entranceCenter, entranceRotation),
                exitPortal,
                entranceCenter.clone().subtract(0, settings.height() / 2.0, 0),
                removeAtTick,
                settings.onlyOwner(),
                entranceDisplay,
                teleportMessage
        );
        portalPairs.add(pair);
        indexPair(pair);
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

    public double minimumPortalDistance() {
        return Math.max(0.0, plugin.getConfig().getDouble("portal.min-distance", 10.0));
    }

    public String minimumPortalDistanceDisplay() {
        double distance = minimumPortalDistance();

        if (distance == Math.floor(distance)) {
            return String.valueOf((int) distance);
        }

        return String.format(Locale.US, "%.1f", distance);
    }

    public boolean isBelowMinimumDistance(Location entranceCenter, Location exitBottom) {
        double minimumDistance = minimumPortalDistance();

        if (minimumDistance <= 0.0 || entranceCenter.getWorld() == null || exitBottom.getWorld() == null) {
            return false;
        }

        if (!entranceCenter.getWorld().equals(exitBottom.getWorld())) {
            return false;
        }

        return entranceCenter.distanceSquared(exitBottom) < minimumDistance * minimumDistance;
    }

    private void start() {
        cachedSettings = PortalTeleportSettings.from(plugin);
        PortalTeleportSettings settings = settings();
        currentTick = 0L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, settings.checkIntervalTicks());
    }

    private void tick() {
        PortalTeleportSettings settings = settings();
        currentTick += settings.checkIntervalTicks();
        long now = currentTick();
        cleanupExpiredPortals(now);

        for (Player player : Bukkit.getOnlinePlayers()) {
            Long cooldownUntil = cooldowns.get(player.getUniqueId());

            if (cooldownUntil != null && cooldownUntil > now) {
                continue;
            }

            Location playerLocation = player.getLocation();
            PortalHit hit = plugin.getOptimizationSettings().portalSpatialIndex()
                    ? findIndexedPortal(player, playerLocation, settings)
                    : findPortal(player, playerLocation, portalPairs, settings);

            if (hit == null) {
                continue;
            }

            player.teleport(hit.target().clone());
            if (hit.pair().teleportMessage() != null) {
                player.sendMessage(hit.pair().teleportMessage());
            }
            cooldowns.put(player.getUniqueId(), now + settings.cooldownTicks());

            if (settings.closeAfterOwnerUse() && player.getUniqueId().equals(hit.pair().ownerId())) {
                closePair(hit.pair());
            }
        }
    }

    private void cleanupExpiredPortals(long now) {
        for (PortalPair pair : portalPairs) {
            if (pair.removeAtTick() <= now) {
                removePair(pair);
            }
        }

        cleanupSharedExitPortals(now);
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private PortalHit findIndexedPortal(Player player, Location playerLocation, PortalTeleportSettings settings) {
        World world = playerLocation.getWorld();

        if (world == null) {
            return null;
        }

        int playerChunkX = playerLocation.getBlockX() >> 4;
        int playerChunkZ = playerLocation.getBlockZ() >> 4;
        int chunkRadius = Math.max(1, (int) Math.ceil((settings.width() / 2.0 + settings.activationDepth()) / 16.0));
        Map<Long, List<PortalSide>> worldIndex = portalIndex.get(world.getUID());

        if (worldIndex == null) {
            return null;
        }

        for (int chunkX = playerChunkX - chunkRadius; chunkX <= playerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = playerChunkZ - chunkRadius; chunkZ <= playerChunkZ + chunkRadius; chunkZ++) {
                List<PortalSide> sides = worldIndex.get(chunkKey(chunkX, chunkZ));

                if (sides == null) {
                    continue;
                }

                for (PortalSide side : sides) {
                    PortalPair pair = side.pair();

                    if (!pair.canUse(player) || (!side.entrance() && settings.oneWay())) {
                        continue;
                    }

                    PortalArea area = side.entrance() ? pair.entranceArea() : pair.exitPortal().area();

                    if (isInsidePortal(playerLocation, area, settings)) {
                        return new PortalHit(pair, side.entrance() ? pair.exitPortal().bottom() : pair.entranceBottom());
                    }
                }
            }
        }

        return null;
    }

    private PortalHit findPortal(Player player, Location playerLocation, Iterable<PortalPair> pairs, PortalTeleportSettings settings) {
        for (PortalPair pair : pairs) {
            if (!pair.canUse(player)) {
                continue;
            }

            if (isInsidePortal(playerLocation, pair.entranceArea(), settings)) {
                return new PortalHit(pair, pair.exitPortal().bottom());
            }

            if (!settings.oneWay() && isInsidePortal(playerLocation, pair.exitPortal().area(), settings)) {
                return new PortalHit(pair, pair.entranceBottom());
            }
        }

        return null;
    }

    private boolean isInsidePortal(Location playerLocation, PortalArea area, PortalTeleportSettings settings) {
        if (plugin.getOptimizationSettings().cachePortalCalculations()) {
            return area.contains(playerLocation, settings.width(), settings.height(), settings.activationDepth());
        }

        Location portalCenter = area.center();
        float rotationY = area.rotation();
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
        removePair(pair);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ShowPortal.close(plugin, pair.entranceDisplay(), pair.entranceRotation());
            closeSharedExitPortalIfUnused(pair.exitPortal(), 0L);
        }, CLOSE_AFTER_OWNER_USE_DELAY_TICKS);
    }

    private void closePairNow(PortalPair pair) {
        removePair(pair);
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
        Location center = ShowPortal.centerFromBottom(plugin, exitBottom);
        SharedExitPortal created = new SharedExitPortal(
                key,
                center,
                exitBottom.clone(),
                exitRotation,
                PortalArea.create(center, exitRotation),
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

    private void indexPair(PortalPair pair) {
        if (!plugin.getOptimizationSettings().portalSpatialIndex()) {
            return;
        }

        addToIndex(pair.entranceArea().center(), new PortalSide(pair, true));
        addToIndex(pair.exitPortal().area().center(), new PortalSide(pair, false));
    }

    private void addToIndex(Location location, PortalSide side) {
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        portalIndex
                .computeIfAbsent(world.getUID(), ignored -> new HashMap<>())
                .computeIfAbsent(chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4), ignored -> new ArrayList<>())
                .add(side);
    }

    private void removePair(PortalPair pair) {
        if (!portalPairs.remove(pair)) {
            return;
        }

        removeFromIndex(pair.entranceArea().center(), pair);
        removeFromIndex(pair.exitPortal().area().center(), pair);
    }

    private void removeFromIndex(Location location, PortalPair pair) {
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        Map<Long, List<PortalSide>> worldIndex = portalIndex.get(world.getUID());

        if (worldIndex == null) {
            return;
        }

        long key = chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        List<PortalSide> sides = worldIndex.get(key);

        if (sides == null) {
            return;
        }

        sides.removeIf(side -> side.pair() == pair);
        if (sides.isEmpty()) {
            worldIndex.remove(key);
        }
        if (worldIndex.isEmpty()) {
            portalIndex.remove(world.getUID());
        }
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private PortalTeleportSettings settings() {
        return plugin.getOptimizationSettings().cacheSettings()
                ? cachedSettings
                : PortalTeleportSettings.from(plugin);
    }

    private record PortalPair(
            UUID ownerId,
            Location entranceCenter,
            float entranceRotation,
            PortalArea entranceArea,
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
        private final PortalArea area;
        private final BlockDisplay display;
        private long removeAtTick;

        private SharedExitPortal(PortalKey key, Location center, Location bottom, float rotation, PortalArea area, BlockDisplay display, long removeAtTick) {
            this.key = key;
            this.center = center;
            this.bottom = bottom;
            this.rotation = rotation;
            this.area = area;
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

        private PortalArea area() {
            return area;
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

    private record PortalArea(
            Location center,
            float rotation,
            UUID worldId,
            double cos,
            double sin
    ) {
        private static PortalArea create(Location center, float rotation) {
            World world = center.getWorld();
            return new PortalArea(
                    center.clone(),
                    rotation,
                    world == null ? new UUID(0L, 0L) : world.getUID(),
                    Math.cos(rotation),
                    Math.sin(rotation)
            );
        }

        private boolean contains(Location location, double width, double height, double activationDepth) {
            World world = location.getWorld();

            if (world == null || !world.getUID().equals(worldId)) {
                return false;
            }

            double offsetX = location.getX() - center.getX();
            double offsetY = location.getY() - center.getY();
            double offsetZ = location.getZ() - center.getZ();
            double localX = offsetX * cos - offsetZ * sin;
            double localZ = offsetX * sin + offsetZ * cos;

            return Math.abs(localX) <= width / 2.0
                    && Math.abs(offsetY) <= height / 2.0
                    && Math.abs(localZ) <= activationDepth;
        }
    }

    private record PortalSide(PortalPair pair, boolean entrance) {
    }

    private record PortalHit(PortalPair pair, Location target) {
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
            boolean animationEnabled = plugin.getConfig().getBoolean("portal.animation-enabled", true);
            int openingDelayTicks = plugin.getConfig().getBoolean("portal.use-display-transitions", true) ? 2 : 0;
            int portalLifetimeTicks = animationEnabled
                    ? openingDelayTicks + animationTicks + holdTicks + animationTicks
                    : holdTicks;

            return new PortalTeleportSettings(
                    plugin.getConfig().getDouble("portal.width", 1.25),
                    plugin.getConfig().getDouble("portal.height", 2.5),
                    portalLifetimeTicks,
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
