package dev.gdlev.warpPortals.Commands;

import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.command.CommandSender;

public class Help {
    public static void HelpSubcommand(CommandSender sender, WarpPortals plugin) {
        for (String line : plugin.getLangConfig().getStringList("help.lines")) {
            sender.sendMessage(plugin.colorize(line));
        }
    }
}
