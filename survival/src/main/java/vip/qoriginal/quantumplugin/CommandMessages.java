package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

public final class CommandMessages {
    private static final Component PREFIX = Component.text("[QO] ", NamedTextColor.DARK_AQUA, TextDecoration.BOLD);

    private CommandMessages() {
    }

    public static void info(CommandSender sender, String message) {
        sender.sendMessage(prefixed(message, NamedTextColor.AQUA));
    }

    public static void success(CommandSender sender, String message) {
        sender.sendMessage(prefixed(message, NamedTextColor.GREEN));
    }

    public static void warning(CommandSender sender, String message) {
        sender.sendMessage(prefixed(message, NamedTextColor.YELLOW));
    }

    public static void error(CommandSender sender, String message) {
        sender.sendMessage(prefixed(message, NamedTextColor.RED));
    }

    public static void playerOnly(CommandSender sender) {
        error(sender, "只有玩家可以执行这个命令。");
    }

    public static Component command(String command) {
        return Component.text(command, NamedTextColor.WHITE);
    }

    public static Component muted(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    public static Component title(String title) {
        return PREFIX.append(Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    private static Component prefixed(String message, NamedTextColor color) {
        return PREFIX.append(Component.text(message, color).decoration(TextDecoration.BOLD, false));
    }
}
