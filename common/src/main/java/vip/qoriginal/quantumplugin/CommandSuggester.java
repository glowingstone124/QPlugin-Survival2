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
    private static final List<String> GAME_MODE_OPTIONS = List.of("s", "sc");
    private static final List<String> FLIGHT_CATEGORIES = List.of("report", "gui", "dest", "destination");
    private static final List<String> FLIGHT_TOGGLES = List.of("on", "off");
    private static final List<String> FLIGHT_DEST_ACTIONS = List.of("set", "unset", "list");
    private static final List<String> FLIGHT_DESTINATIONS = List.of("XCA", "ZCA", "FDA", "NONE");
    private static final List<String> FIREWORK_ACTIONS = List.of("get", "launch");
    private static final List<String> FIREWORK_TYPES = List.of("1", "2", "3", "4");
    private static final String FAKE_PLAYER_TAG = "quantum_fake_player";
    private static final List<String> FAKE_PLAYER_ACTIONS = List.of("spawn", "remove", "list", "inventory", "inv");

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
            case "gm" -> args.length == 1 ? GAME_MODE_OPTIONS : List.of();
            case "highlight" -> completeHighlight(sender, args);
            case "querybind", "viewinventory" -> args.length == 1 ? onlinePlayerNames() : List.of();
            case "leavemessage" -> completeLeaveMessage(args);
            case "bind" -> args.length == 1 ? List.of("forumAccount") : List.of();
            case "bindauth", "myloc", "showitem", "suicide", "login", "summontext", "newyeartnt", "newyeardumplings" -> List.of();
            case "elite" -> completeElite(args);
            case "firework" -> completeFirework(args);
            case "flight" -> completeFlight(args);
            case "fakeplayer" -> completeFakePlayer(args);
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

    private List<String> completeFirework(String[] args) {
        if (args.length == 1) {
            return FIREWORK_ACTIONS;
        }
        if (args.length == 2 && "get".equalsIgnoreCase(args[0])) {
            return FIREWORK_TYPES;
        }
        return List.of();
    }

    private List<String> completeFlight(String[] args) {
        if (args.length == 1) {
            return FLIGHT_CATEGORIES;
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "report", "gui" -> FLIGHT_TOGGLES;
                case "dest", "destination" -> FLIGHT_DEST_ACTIONS;
                default -> List.of();
            };
        }
        if (args.length == 3
                && ("dest".equalsIgnoreCase(args[0]) || "destination".equalsIgnoreCase(args[0]))
                && "set".equalsIgnoreCase(args[1])) {
            return FLIGHT_DESTINATIONS;
        }
        return List.of();
    }

    private List<String> completeFakePlayer(String[] args) {
        if (args.length == 1) {
            return FAKE_PLAYER_ACTIONS;
        }
        if (args.length == 3 && "spawn".equalsIgnoreCase(args[0])) {
            return onlinePlayerNames();
        }
        if (args.length == 2 && ("remove".equalsIgnoreCase(args[0])
                || "inventory".equalsIgnoreCase(args[0])
                || "inv".equalsIgnoreCase(args[0]))) {
            return onlinePlayerNames().stream()
                    .filter(this::isFakePlayer)
                    .toList();
        }
        return List.of();
    }

    private boolean isFakePlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player != null && player.getScoreboardTags().contains(FAKE_PLAYER_TAG);
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
