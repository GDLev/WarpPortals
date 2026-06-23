package dev.gdlev.warpPortals.Commands;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.command.CommandSender;

public class Reload {
    public static void ReloadSubcommand(CommandSender sender, WarpPortals plugin) {
        plugin.reloadPluginFiles();
        sender.sendMessage(plugin.lang("messages.reload"));
    }
}
