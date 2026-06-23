package dev.gdlev.warpPortals.Storage;

import dev.gdlev.warpPortals.Costs.WarpCost;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.Optional;

public record SavedWarp(
        String name,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        WarpCost teleportCost,
        WarpCost portalCost
) {
    public static SavedWarp fromLocation(String name, Location location, WarpCost teleportCost, WarpCost portalCost) {
        return new SavedWarp(
                name,
                Objects.requireNonNull(location.getWorld()).getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                teleportCost,
                portalCost
        );
    }

    public Optional<Location> toLocation() {
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            return Optional.empty();
        }

        return Optional.of(new Location(world, x, y, z, yaw, pitch));
    }
}
