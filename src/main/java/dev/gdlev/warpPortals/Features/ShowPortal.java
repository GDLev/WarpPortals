package dev.gdlev.warpPortals.Features;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class ShowPortal {

    private static final float FINAL_WIDTH = 1.25f;
    private static final float FINAL_HEIGHT = 2.5f;
    private static final float DEPTH = 3.0f / 16.0f;
    private static final float DISTANCE_FROM_PLAYER = 1.5f;
    private static final int ANIMATION_TICKS = 10;
    private static final int HOLD_TICKS = 60;
    private static final long DISPLAY_TRANSITION_START_DELAY_TICKS = 2L;
    private static final float START_HEIGHT = 0.01f;
    private static final String PORTAL_ENTITY_TAG = "warp_portals_display";
    private static final String PORTAL_ENTITY_KEY = "portal_display";
    private static final Map<UUID, ParticleEffect> PARTICLE_EFFECTS = new HashMap<>();
    private static BukkitTask particleTask;

    public static void stopParticleEffects() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }

        PARTICLE_EFFECTS.clear();
    }

    public static int removeLeftoverDisplays(JavaPlugin plugin) {
        int removed = 0;

        for (World world : Bukkit.getWorlds()) {
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
                if (!isPortalDisplay(plugin, display)) {
                    continue;
                }

                display.remove();
                removed++;
            }
        }

        return removed;
    }

    public static BlockDisplay open(JavaPlugin plugin, Player player) {
        PortalSettings settings = PortalSettings.from(plugin);
        Location loc = centerInFrontOfPlayer(player, settings.distanceFromPlayer(), settings.height());
        float portalRotation = getYRotationToFace(loc, player.getLocation());

        return openWithRotation(plugin, loc, portalRotation, settings);
    }

    public static Location centerInFrontOfPlayer(JavaPlugin plugin, Player player) {
        PortalSettings settings = PortalSettings.from(plugin);
        return centerInFrontOfPlayer(player, settings.distanceFromPlayer(), settings.height());
    }

    public static Location centerFromBottom(JavaPlugin plugin, Location bottomCenter) {
        PortalSettings settings = PortalSettings.from(plugin);
        return bottomCenter.clone().add(0, settings.height() / 2.0, 0);
    }

    public static float rotationToFace(Location source, Location target) {
        return getYRotationToFace(source, target);
    }

    public static float rotationFromYaw(float yawDegrees) {
        return toDisplayRotation(yawDegrees);
    }

    // Opens a portal with its center at the given location. Yaw uses Bukkit degrees.
    public static BlockDisplay openAt(JavaPlugin plugin, Location center, float yawDegrees) {
        return openWithRotation(plugin, center, toDisplayRotation(yawDegrees), PortalSettings.from(plugin));
    }

    // Opens a portal from the given bottom-center location. Yaw uses Bukkit degrees.
    public static BlockDisplay openAtFromBottom(JavaPlugin plugin, Location bottomCenter, float yawDegrees) {
        PortalSettings settings = PortalSettings.from(plugin);
        Location center = bottomCenter.clone().add(0, settings.height() / 2.0, 0);

        return openWithRotation(plugin, center, toDisplayRotation(yawDegrees), settings, true);
    }

    // Opens a portal from the given bottom-center location without scheduling its close animation.
    public static BlockDisplay openPersistentAtFromBottom(JavaPlugin plugin, Location bottomCenter, float yawDegrees) {
        PortalSettings settings = PortalSettings.from(plugin);
        Location center = bottomCenter.clone().add(0, settings.height() / 2.0, 0);

        return openWithRotation(plugin, center, toDisplayRotation(yawDegrees), settings, false);
    }

    // Opens a portal with its center at the given location, rotated to face the target on X/Z.
    public static BlockDisplay openFacing(JavaPlugin plugin, Location center, Location target) {
        return openWithRotation(plugin, center, getYRotationToFace(center, target), PortalSettings.from(plugin), true);
    }

    public static void close(JavaPlugin plugin, BlockDisplay display, float portalRotation) {
        if (display == null || !display.isValid()) {
            return;
        }

        PortalSettings settings = PortalSettings.from(plugin);
        Location loc = display.getLocation();

        if (!settings.animationEnabled()) {
            playConfiguredSound(plugin, loc, "portal.sounds.close", "BLOCK_PORTAL_TRAVEL", 0.4, 1.8, "portal close");
            display.remove();
            return;
        }

        animateHeight(plugin, display, settings.height(), settings.startHeight(), portalRotation, settings, 0L);

        playConfiguredSound(plugin, loc, "portal.sounds.close", "BLOCK_PORTAL_TRAVEL", 0.4, 1.8, "portal close");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (display.isValid()) {
                display.remove();
            }
        }, settings.animationTicks());
    }

    private static BlockDisplay openWithRotation(JavaPlugin plugin, Location center, float portalRotation, PortalSettings settings) {
        return openWithRotation(plugin, center, portalRotation, settings, true);
    }

    private static BlockDisplay openWithRotation(JavaPlugin plugin, Location center, float portalRotation, PortalSettings settings, boolean autoClose) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        Location loc = center.clone();
        loc.setYaw(0);
        loc.setPitch(0);

        BlockDisplay display = world.spawn(loc, BlockDisplay.class, blockDisplay -> {
            blockDisplay.setBlock(settings.material().createBlockData());
            blockDisplay.setGravity(false);
            blockDisplay.setPersistent(false);
            markPortalDisplay(plugin, blockDisplay);

            blockDisplay.setInterpolationDelay(0);
            blockDisplay.setInterpolationDuration(settings.animationEnabled() && settings.useDisplayTransitions() ? 0 : 1);

            float initialHeight = settings.animationEnabled() ? settings.startHeight() : settings.height();
            blockDisplay.setTransformationMatrix(createTransform(settings.width(), initialHeight, settings.depth(), portalRotation));
        });

        playConfiguredSound(plugin, loc, "portal.sounds.open", "BLOCK_PORTAL_TRIGGER", 0.7, 1.6, "portal open");
        spawnPortalParticles(plugin, loc, portalRotation, settings.depth());
        startGatewayParticles(plugin, display, loc, portalRotation, settings.depth());

        long openingDelayTicks = settings.useDisplayTransitions() ? DISPLAY_TRANSITION_START_DELAY_TICKS : 0L;
        if (settings.animationEnabled()) {
            animateHeight(plugin, display, settings.startHeight(), settings.height(), portalRotation, settings, openingDelayTicks);
        }

        if (autoClose) {
            long openingDurationTicks = settings.animationEnabled()
                    ? openingDelayTicks + settings.animationTicks()
                    : 0L;
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> close(plugin, display, portalRotation),
                    openingDurationTicks + settings.holdTicks()
            );
        }

        return display;
    }

    private static void markPortalDisplay(JavaPlugin plugin, BlockDisplay display) {
        display.addScoreboardTag(PORTAL_ENTITY_TAG);
        display.getPersistentDataContainer().set(pluginKey(plugin), PersistentDataType.BYTE, (byte) 1);
    }

    private static boolean isPortalDisplay(JavaPlugin plugin, BlockDisplay display) {
        return display.getScoreboardTags().contains(PORTAL_ENTITY_TAG)
                || display.getPersistentDataContainer().has(pluginKey(plugin), PersistentDataType.BYTE);
    }

    private static NamespacedKey pluginKey(JavaPlugin plugin) {
        return new NamespacedKey(plugin, PORTAL_ENTITY_KEY);
    }

    private static void playConfiguredSound(
            JavaPlugin plugin,
            Location location,
            String sectionPath,
            String defaultSound,
            double defaultVolume,
            double defaultPitch,
            String warningName
    ) {
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        String soundName = plugin.getConfig().getString(sectionPath + ".sound", defaultSound);

        if (soundName == null || soundName.equalsIgnoreCase("none")) {
            return;
        }

        float volume = (float) plugin.getConfig().getDouble(sectionPath + ".volume", defaultVolume);
        float pitch = (float) plugin.getConfig().getDouble(sectionPath + ".pitch", defaultPitch);
        String normalizedSoundName = soundName.trim();

        try {
            if (normalizedSoundName.contains(":") || normalizedSoundName.contains(".")) {
                world.playSound(location, normalizedSoundName, volume, pitch);
                return;
            }

            Sound sound = Sound.valueOf(normalizedSoundName.toUpperCase(Locale.ROOT));
            world.playSound(location, sound, volume, pitch);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid " + warningName + " sound in config.yml: " + soundName);
        }
    }

    private static void animateHeight(
            JavaPlugin plugin,
            BlockDisplay display,
            float fromHeight,
            float toHeight,
            float rotationY,
            PortalSettings settings,
            long displayTransitionDelay
    ) {
        if (settings.useDisplayTransitions()) {
            Runnable applyTargetTransform = () -> {
                if (!display.isValid()) {
                    return;
                }

                display.setInterpolationDelay(0);
                display.setInterpolationDuration(settings.animationTicks());
                display.setTransformationMatrix(createTransform(settings.width(), toHeight, settings.depth(), rotationY));
            };

            if (displayTransitionDelay > 0L) {
                Bukkit.getScheduler().runTaskLater(plugin, applyTargetTransform, displayTransitionDelay);
            } else {
                applyTargetTransform.run();
            }
            return;
        }

        new BukkitRunnable() {
            private int tick = 0;

            @Override
            public void run() {
                if (!display.isValid()) {
                    cancel();
                    return;
                }

                float progress = (float) tick / settings.animationTicks();
                float easedProgress = ease(progress);
                float height = fromHeight + (toHeight - fromHeight) * easedProgress;

                display.setTransformationMatrix(createTransform(settings.width(), height, settings.depth(), rotationY));

                if (tick >= settings.animationTicks()) {
                    cancel();
                    return;
                }

                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static float ease(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float getYRotationToFace(Location source, Location target) {
        Vector direction = target.toVector().subtract(source.toVector());
        direction.setY(0);

        if (direction.lengthSquared() == 0) {
            return 0.0f;
        }

        Location rotation = source.clone();
        rotation.setDirection(direction);

        return (float) Math.toRadians(-rotation.getYaw());
    }

    private static Location centerInFrontOfPlayer(Player player, float distanceFromPlayer, float height) {
        Vector forward = player.getLocation().getDirection();
        forward.setY(0);

        if (forward.lengthSquared() == 0) {
            forward = player.getEyeLocation().getDirection();
            forward.setY(0);
        }

        forward.normalize();

        Location loc = player.getLocation().clone()
                .add(forward.clone().multiply(distanceFromPlayer));
        loc.setY(player.getLocation().getBlockY());
        loc.add(0, height / 2.0, 0);

        return loc;
    }

    private static float toDisplayRotation(float yawDegrees) {
        return (float) Math.toRadians(-yawDegrees);
    }

    private static void startGatewayParticles(JavaPlugin plugin, BlockDisplay display, Location loc, float rotationY, float depth) {
        if (useSharedParticleScheduler(plugin)) {
            PARTICLE_EFFECTS.put(display.getUniqueId(), ParticleEffect.create(display, loc, rotationY, depth));
            startSharedParticleTask(plugin);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!display.isValid()) {
                    cancel();
                    return;
                }

                Location center = loc.clone();
                World world = center.getWorld();
                if (world == null) return;

                spawnOrientedParticles(world, center, rotationY, Particle.PORTAL, 28, 0.85, 0.85, depth, 0.06);
                spawnOrientedParticles(world, center, rotationY, Particle.REVERSE_PORTAL, 10, 0.55, 0.75, depth, 0.03);
                spawnOrientedParticles(world, center, rotationY, Particle.END_ROD, 2, 0.7, 0.75, depth, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    private static void startSharedParticleTask(JavaPlugin plugin) {
        if (particleTask != null) {
            return;
        }

        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Iterator<ParticleEffect> iterator = PARTICLE_EFFECTS.values().iterator();

            while (iterator.hasNext()) {
                ParticleEffect effect = iterator.next();

                if (!effect.display().isValid()) {
                    iterator.remove();
                    continue;
                }

                effect.spawn();
            }

            if (PARTICLE_EFFECTS.isEmpty() && particleTask != null) {
                particleTask.cancel();
                particleTask = null;
            }
        }, 0L, 3L);
    }

    private static void spawnPortalParticles(JavaPlugin plugin, Location loc, float rotationY, float depth) {
        World world = loc.getWorld();
        if (world == null) return;

        if (useSharedParticleScheduler(plugin)) {
            double cos = Math.cos(rotationY);
            double sin = Math.sin(rotationY);
            spawnOrientedParticlesOptimized(world, loc, cos, sin, Particle.PORTAL, 80, 1.0, 1.0, depth, 0.15);
            return;
        }

        spawnOrientedParticles(world, loc, rotationY, Particle.PORTAL, 80, 1.0, 1.0, depth, 0.15);
    }

    private static boolean useSharedParticleScheduler(JavaPlugin plugin) {
        if (plugin instanceof WarpPortals warpPortals) {
            return warpPortals.getOptimizationSettings().sharedParticleScheduler();
        }

        return plugin.getConfig().getBoolean("optimizations.shared-particle-scheduler", true);
    }

    private static void spawnOrientedParticles(
            World world,
            Location center,
            float rotationY,
            Particle particle,
            int count,
            double halfWidth,
            double halfHeight,
            double depth,
            double speed
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            double localX = random.nextDouble(-halfWidth, halfWidth);
            double localY = random.nextDouble(-halfHeight, halfHeight);
            double localZ = random.nextDouble(-depth / 2.0, depth / 2.0);
            Vector offset = rotateOffset(localX, localY, localZ, rotationY);

            world.spawnParticle(particle, center.clone().add(offset), 1, 0.0, 0.0, 0.0, speed);
        }
    }

    private static Vector rotateOffset(double x, double y, double z, float rotationY) {
        double cos = Math.cos(rotationY);
        double sin = Math.sin(rotationY);

        return new Vector(
                x * cos + z * sin,
                y,
                -x * sin + z * cos
        );
    }

    private static void spawnOrientedParticlesOptimized(
            World world,
            Location center,
            double cos,
            double sin,
            Particle particle,
            int count,
            double halfWidth,
            double halfHeight,
            double depth,
            double speed
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location particleLocation = center.clone();

        for (int i = 0; i < count; i++) {
            double localX = random.nextDouble(-halfWidth, halfWidth);
            double localY = random.nextDouble(-halfHeight, halfHeight);
            double localZ = random.nextDouble(-depth / 2.0, depth / 2.0);

            particleLocation.setX(center.getX() + localX * cos + localZ * sin);
            particleLocation.setY(center.getY() + localY);
            particleLocation.setZ(center.getZ() - localX * sin + localZ * cos);
            world.spawnParticle(particle, particleLocation, 1, 0.0, 0.0, 0.0, speed);
        }
    }

    private static Matrix4f createTransform(float width, float height, float depth, float rotationY) {
        return new Matrix4f()
                .rotateY(rotationY)
                .scale(width, height, depth)
                .translate(-0.5f, -0.5f, -0.5f);
    }

    private record PortalSettings(
            float width,
            float height,
            float depth,
            float distanceFromPlayer,
            float startHeight,
            int animationTicks,
            int holdTicks,
            boolean animationEnabled,
            boolean useDisplayTransitions,
            Material material
    ) {
        private static PortalSettings from(JavaPlugin plugin) {
            Material material = Material.matchMaterial(plugin.getConfig().getString("portal.material", "TINTED_GLASS"));

            if (material == null || !material.isBlock()) {
                material = Material.TINTED_GLASS;
            }

            return new PortalSettings(
                    (float) plugin.getConfig().getDouble("portal.width", FINAL_WIDTH),
                    (float) plugin.getConfig().getDouble("portal.height", FINAL_HEIGHT),
                    (float) plugin.getConfig().getDouble("portal.depth", DEPTH),
                    (float) plugin.getConfig().getDouble("portal.distance-from-player", DISTANCE_FROM_PLAYER),
                    (float) plugin.getConfig().getDouble("portal.start-height", START_HEIGHT),
                    Math.max(1, plugin.getConfig().getInt("portal.animation-ticks", ANIMATION_TICKS)),
                    Math.max(1, plugin.getConfig().getInt("portal.hold-ticks", HOLD_TICKS)),
                    plugin.getConfig().getBoolean("portal.animation-enabled", true),
                    plugin.getConfig().getBoolean("portal.use-display-transitions", true),
                    material
            );
        }
    }

    private record ParticleEffect(
            BlockDisplay display,
            Location center,
            double cos,
            double sin,
            double depth
    ) {
        private static ParticleEffect create(BlockDisplay display, Location center, float rotationY, double depth) {
            return new ParticleEffect(
                    display,
                    center.clone(),
                    Math.cos(rotationY),
                    Math.sin(rotationY),
                    depth
            );
        }

        private void spawn() {
            World world = center.getWorld();

            if (world == null) {
                return;
            }

            spawnOrientedParticlesOptimized(world, center, cos, sin, Particle.PORTAL, 28, 0.85, 0.85, depth, 0.06);
            spawnOrientedParticlesOptimized(world, center, cos, sin, Particle.REVERSE_PORTAL, 10, 0.55, 0.75, depth, 0.03);
            spawnOrientedParticlesOptimized(world, center, cos, sin, Particle.END_ROD, 2, 0.7, 0.75, depth, 0.01);
        }
    }
}
