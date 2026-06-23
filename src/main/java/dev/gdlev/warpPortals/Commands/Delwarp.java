package dev.gdlev.warpPortals.Commands;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.command.CommandSender;

public class Delwarp {
    public static void DelwarpSubcommand(CommandSender sender, WarpPortals plugin, String warpName) {
        try {
            if (plugin.getWarpStorage().deleteWarp(warpName)) {
                sender.sendMessage(plugin.lang("messages.warp-deleted", "{name}", warpName));
            } else {
                sender.sendMessage(plugin.lang("messages.warp-not-found", "{name}", warpName));
            }
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not delete warp '" + warpName + "': " + exception.getMessage());
            sender.sendMessage(plugin.lang("messages.storage-error"));
        }
    }
}
