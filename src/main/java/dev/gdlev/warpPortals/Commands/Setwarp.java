package dev.gdlev.warpPortals.Commands;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Setwarp {
    public static void SetwarpSubcommand(CommandSender sender, WarpPortals plugin, String warpName) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang("messages.player-only"));
            return;
        }

        try {
            plugin.getWarpStorage().saveWarp(warpName.toLowerCase(), player.getLocation());
            sender.sendMessage(plugin.lang("messages.warp-saved", "{name}", warpName.toLowerCase()));
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not save warp '" + warpName + "': " + exception.getMessage());
            sender.sendMessage(plugin.lang("messages.storage-error"));
        }
    }
}
