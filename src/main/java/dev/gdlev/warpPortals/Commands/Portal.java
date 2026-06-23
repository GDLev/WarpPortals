package dev.gdlev.warpPortals.Commands;

import dev.gdlev.warpPortals.Features.ShowPortal;
import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Portal {
    public static void PortalSubcommand(CommandSender sender, WarpPortals plugin, String warpName) {
        if (sender instanceof Player player) {
            try {
                plugin.getWarpStorage().findWarp(warpName).ifPresentOrElse(warp -> {
                    if (warp.toLocation().isEmpty()) {
                        player.sendMessage(plugin.lang("messages.warp-world-missing", "{name}", warpName));
                        return;
                    }

                    var entranceCenter = ShowPortal.centerInFrontOfPlayer(plugin, player);
                    float entranceRotation = ShowPortal.rotationToFace(entranceCenter, player.getLocation());
                    var exitBottom = warp.toLocation().get();
                    float exitRotation = ShowPortal.rotationFromYaw(exitBottom.getYaw());

                    if (!plugin.getPortalManager().preparePortalCreation(player)) {
                        return;
                    }

                    if (!plugin.getCostService().charge(player, warp.portalCost())) {
                        return;
                    }

                    var entranceDisplay = ShowPortal.openFacing(plugin, entranceCenter, player.getLocation());
                    plugin.getPortalManager().registerPair(player, entranceCenter, entranceRotation, exitBottom, exitRotation, entranceDisplay);
                }, () -> player.sendMessage(plugin.lang("messages.warp-not-found", "{name}", warpName)));
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not load warp '" + warpName + "': " + exception.getMessage());
                sender.sendMessage(plugin.lang("messages.storage-error"));
            }
        } else {
            sender.sendMessage(plugin.lang("messages.player-only"));
        }
    }
}
