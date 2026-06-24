package dev.gdlev.warpPortals.Commands;

import dev.gdlev.warpPortals.Costs.CostType;
import dev.gdlev.warpPortals.WarpPortals;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class Warp implements CommandExecutor, TabCompleter {

    private final WarpPortals plugin;

    public Warp(WarpPortals plugin) {
        this.plugin = plugin;
    }

    private final List<String> adminSubcommands = Arrays.asList("help", "reload", "version", "setwarp", "delwarp", "cost", "setperm");
    private final List<String> costMethods = Arrays.asList("teleport", "portal");
    private final List<String> costTypes = Arrays.asList("none", "xp", "level", "vault", "item");

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("warp")) {
            return handleWarpCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("portal")) {
            return handlePortalCommand(sender, args);
        }

        return handleWarpPortalsCommand(sender, args);
    }

    private boolean handleWarpPortalsCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!hasPermission(sender, "warpportals.command.help")) return true;
            Help.HelpSubcommand(sender, plugin);
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            if (!hasPermission(sender, "warpportals.command.help")) return true;
            Help.HelpSubcommand(sender, plugin);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!hasPermission(sender, "warpportals.command.reload")) return true;
            Reload.ReloadSubcommand(sender, plugin);
            return true;
        }

        if (args[0].equalsIgnoreCase("version")) {
            if (!hasPermission(sender, "warpportals.command.version")) return true;
            sender.sendMessage(plugin.lang("messages.info", "{version}", plugin.getDescription().getVersion()));
            return true;
        }

        if (args[0].equalsIgnoreCase("setwarp")) {
            if (!hasPermission(sender, "warpportals.command.setwarp")) return true;
            if (args.length < 2) {
                sender.sendMessage(plugin.lang("messages.usage-setwarp"));
                return true;
            }

            Setwarp.SetwarpSubcommand(sender, plugin, args[1]);
            return true;
        }

        if (args[0].equalsIgnoreCase("delwarp")) {
            if (!hasPermission(sender, "warpportals.command.delwarp")) return true;
            if (args.length < 2) {
                sender.sendMessage(plugin.lang("messages.usage-delwarp"));
                return true;
            }

            Delwarp.DelwarpSubcommand(sender, plugin, args[1]);
            return true;
        }

        if (args[0].equalsIgnoreCase("cost")) {
            if (!hasPermission(sender, "warpportals.command.cost")) return true;
            return handleCostCommand(sender, args);
        }

        if (args[0].equalsIgnoreCase("setperm")) {
            if (!hasPermission(sender, "warpportals.command.setperm")) return true;
            return handleSetPermissionCommand(sender, args);
        }

        Help.HelpSubcommand(sender, plugin);
        return true;
    }

    private boolean handleWarpCommand(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.lang("messages.usage-warp"));
            return true;
        }

        if (plugin.getConfig().getBoolean("commands.direct-warp-teleport", true)) {
            if (!hasPermission(sender, "warpportals.command.teleport")) return true;
            Teleport.TeleportSubcommand(sender, plugin, args[0]);
            return true;
        }

        if (!hasPermission(sender, "warpportals.command.portal")) return true;
        Portal.PortalSubcommand(sender, plugin, args[0]);
        return true;
    }

    private boolean handlePortalCommand(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "warpportals.command.portal")) return true;

        if (args.length < 1) {
            sender.sendMessage(plugin.lang("messages.usage-portal"));
            return true;
        }

        Portal.PortalSubcommand(sender, plugin, args[0]);
        return true;
    }

    private boolean handleCostCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.lang("messages.usage-cost"));
            return true;
        }

        String warpName = args[1].toLowerCase();
        String method = args[2].toLowerCase();
        String typeName = args[3].toLowerCase();

        if (!costMethods.contains(method) || !costTypes.contains(typeName)) {
            sender.sendMessage(plugin.lang("messages.usage-cost"));
            return true;
        }

        CostType type = CostType.fromConfig(typeName);
        String amount = type == CostType.NONE ? "0" : args.length >= 5 ? args[4] : null;

        if (amount == null) {
            sender.sendMessage(plugin.lang("messages.usage-cost"));
            return true;
        }

        Material item = Material.DIAMOND;

        if (type == CostType.ITEM) {
            if (args.length < 6) {
                sender.sendMessage(plugin.lang("messages.usage-cost"));
                return true;
            }

            item = Material.matchMaterial(args[5]);

            if (item == null || !item.isItem()) {
                sender.sendMessage(plugin.lang("messages.invalid-item", "{item}", args[5]));
                return true;
            }
        }

        try {
            if (!plugin.getWarpStorage().setWarpCost(warpName, method, type, amount, item)) {
                sender.sendMessage(plugin.lang("messages.warp-not-found", "{name}", warpName));
                return true;
            }

            sender.sendMessage(plugin.lang(
                    "messages.cost-updated",
                    "{name}", warpName,
                    "{method}", method,
                    "{type}", typeName,
                    "{cost}", type == CostType.ITEM ? amount + "x " + item.name() : amount
            ));
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not update cost for warp '" + warpName + "': " + exception.getMessage());
            sender.sendMessage(plugin.lang("messages.storage-error"));
        }

        return true;
    }

    private boolean handleSetPermissionCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.lang("messages.usage-setperm"));
            return true;
        }

        String warpName = args[1].toLowerCase();
        String permission = args[2].equalsIgnoreCase("reset") ? "" : args[2];

        try {
            if (!plugin.getWarpStorage().setWarpPermission(warpName, permission)) {
                sender.sendMessage(plugin.lang("messages.warp-not-found", "{name}", warpName));
                return true;
            }

            if (permission.isBlank()) {
                sender.sendMessage(plugin.lang("messages.permission-reset", "{name}", warpName));
                return true;
            }

            sender.sendMessage(plugin.lang(
                    "messages.permission-updated",
                    "{name}", warpName,
                    "{permission}", permission
            ));
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not update permission for warp '" + warpName + "': " + exception.getMessage());
            sender.sendMessage(plugin.lang("messages.storage-error"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("warp") || command.getName().equalsIgnoreCase("portal")) {
            if (args.length == 1) {
                return complete(args[0], plugin.getWarpStorage().listWarpNames());
            }

            return Collections.emptyList();
        }

        if (args.length == 1) {
            return complete(args[0], adminSubcommands);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("delwarp") || args[0].equalsIgnoreCase("cost") || args[0].equalsIgnoreCase("setperm"))) {
            return complete(args[1], plugin.getWarpStorage().listWarpNames());
        }

        if (args[0].equalsIgnoreCase("setperm") && args.length == 3) {
            return complete(args[2], Collections.singletonList("reset"));
        }

        if (args[0].equalsIgnoreCase("cost")) {
            if (args.length == 3) {
                return complete(args[2], costMethods);
            }

            if (args.length == 4) {
                return complete(args[3], costTypes);
            }

            if (args.length == 6 && args[3].equalsIgnoreCase("item")) {
                return complete(args[5], Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(Material::name)
                        .toList());
            }
        }

        return Collections.emptyList();
    }

    private List<String> complete(String input, List<String> options) {
        List<String> completions = new ArrayList<>();
        StringUtil.copyPartialMatches(input, options, completions);
        Collections.sort(completions);
        return completions;
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;

        sender.sendMessage(plugin.lang("messages.no-permission"));
        return false;
    }
}
