package vip.qoriginal.quantumplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class CommandSuggester implements TabCompleter {
    private static final List<String> TOGGLE_OPTIONS = List.of("query", "enable", "disable");
    private static final List<String> FALLEN_ACTIONS = List.of("help", "status", "time", "start", "end", "phase", "team", "region", "key", "score", "buy", "beacon", "admin");
    private static final List<String> FALLEN_PHASES = List.of("idle", "deployment", "active", "overtime", "ended");
    private static final List<String> FALLEN_TEAMS = List.of("A", "B", "C");
    private static final List<String> FALLEN_BUY_OPTIONS = List.of("compass", "scan", "jammer", "tracking", "supply", "advanced", "resistance", "speed", "nightvision", "blast", "respawn", "keyalert", "beacon");
    private static final List<String> SHOP_IDS = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");

    public static void register(JavaPlugin plugin, Collection<String> commandNames) {
        CommandSuggester suggester = new CommandSuggester();
        for (String commandName : commandNames) {
            PluginCommand command = plugin.getCommand(commandName);
            if (command != null) {
                command.setTabCompleter(suggester);
            }
        }
    }

    private CommandSuggester() {
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        List<String> suggestions = switch (commandName) {
            case "shutup", "damageindicator" -> args.length == 1 ? TOGGLE_OPTIONS : List.of();
            case "highlight" -> completeHighlight(sender, args);
            case "querybind", "viewinventory" -> args.length == 1 ? onlinePlayerNames() : List.of();
            case "leavemessage" -> completeLeaveMessage(args);
            case "bind" -> args.length == 1 ? List.of("forumAccount") : List.of();
            case "bindauth", "myloc", "showitem", "suicide", "login", "summontext" -> List.of();
            case "elite" -> completeElite(args);
            case "fallen" -> completeFallen(args);
            case "shop" -> completeShop(args);
            default -> List.of();
        };
        return copyPartialMatches(args, suggestions);
    }

    private List<String> completeHighlight(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length > 3) {
            return List.of();
        }
        Location location = player.getTargetBlockExact(80) != null
                ? player.getTargetBlockExact(80).getLocation()
                : player.getLocation();
        return switch (args.length) {
            case 1 -> List.of(String.valueOf(location.getBlockX()));
            case 2 -> List.of(String.valueOf(location.getBlockY()));
            case 3 -> List.of(String.valueOf(location.getBlockZ()));
            default -> List.of();
        };
    }

    private List<String> completeLeaveMessage(String[] args) {
        if (args.length == 1) {
            return onlinePlayerNames();
        }
        return List.of();
    }

    private List<String> completeElite(String[] args) {
        return switch (args.length) {
            case 1 -> List.of("weapon_name");
            case 2 -> List.of("inscription");
            default -> List.of();
        };
    }

    private List<String> completeFallen(String[] args) {
        if (args.length == 1) {
            return FALLEN_ACTIONS;
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "phase" -> FALLEN_PHASES;
                case "team" -> List.of("set", "clear", "get");
                case "region" -> List.of("list");
                case "key" -> List.of("give", "list");
                case "score" -> List.of("add", "set");
                case "buy" -> FALLEN_BUY_OPTIONS;
                case "admin" -> List.of("eliminate", "voidkey");
                default -> List.of();
            };
        }
        if (args.length == 3 && ("team".equalsIgnoreCase(args[0]) || "key".equalsIgnoreCase(args[0]))) {
            if ("give".equalsIgnoreCase(args[1])) {
                return FALLEN_TEAMS;
            }
            return onlinePlayerNames();
        }
        if (args.length == 3 && "score".equalsIgnoreCase(args[0])) {
            return FALLEN_TEAMS;
        }
        if (args.length == 3 && "buy".equalsIgnoreCase(args[0]) && "compass".equalsIgnoreCase(args[1])) {
            return FALLEN_TEAMS;
        }
        if (args.length == 3 && "admin".equalsIgnoreCase(args[0]) && "eliminate".equalsIgnoreCase(args[1])) {
            return FALLEN_TEAMS;
        }
        if (args.length == 4 && "team".equalsIgnoreCase(args[0]) && "set".equalsIgnoreCase(args[1])) {
            return FALLEN_TEAMS;
        }
        if (args.length == 4 && "key".equalsIgnoreCase(args[0]) && "give".equalsIgnoreCase(args[1])) {
            return onlinePlayerNames();
        }
        return List.of();
    }

    private List<String> completeShop(String[] args) {
        return switch (args.length) {
            case 1 -> SHOP_IDS;
            case 2 -> List.of("1", "2", "3", "4", "5", "8", "16");
            default -> List.of();
        };
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .toList();
    }

    private List<String> copyPartialMatches(String[] args, List<String> suggestions) {
        if (args.length == 0 || suggestions.isEmpty()) {
            return suggestions;
        }
        List<String> matches = new ArrayList<>();
        StringUtil.copyPartialMatches(args[args.length - 1], suggestions, matches);
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        return matches;
    }
}
