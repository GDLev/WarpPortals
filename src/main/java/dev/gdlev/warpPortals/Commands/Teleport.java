package dev.gdlev.warpPortals.Commands;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Teleport {
    public static void TeleportSubcommand(CommandSender sender, WarpPortals plugin, String warpName) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang("messages.player-only"));
            return;
        }

        try {
            plugin.getWarpStorage().findWarp(warpName).ifPresentOrElse(warp -> {
                if (!warp.canUse(player)) {
                    player.sendMessage(plugin.lang("messages.warp-no-permission"));
                    return;
                }

                var location = warp.toLocation();

                if (location.isEmpty()) {
                    player.sendMessage(plugin.lang("messages.warp-world-missing", "{name}", warpName));
                    return;
                }

                plugin.getTeleportTimer().start(player, warpName, location.get(), warp.teleportCost());
            }, () -> player.sendMessage(plugin.lang("messages.warp-not-found", "{name}", warpName)));
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not load warp '" + warpName + "': " + exception.getMessage());
            sender.sendMessage(plugin.lang("messages.storage-error"));
        }
    }
}
