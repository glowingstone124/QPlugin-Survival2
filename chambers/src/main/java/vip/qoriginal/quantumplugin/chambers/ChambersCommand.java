package vip.qoriginal.quantumplugin.chambers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class ChambersCommand implements CommandExecutor, TabCompleter {
    private final ChamberManager chamberManager;

    public ChambersCommand(ChamberManager chamberManager) {
        this.chamberManager = chamberManager;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            return sendUsage(sender);
        }
        return switch (args[0].toLowerCase()) {
            case "start" -> start(sender);
            case "leave" -> leave(sender);
            case "status" -> status(sender);
            case "build" -> build(sender);
            case "reload" -> reload(sender);
            default -> sendUsage(sender);
        };
    }

    private boolean start(CommandSender sender) {
        if (!sender.hasPermission("qplugin.chambers.admin")) {
            sender.sendMessage(Component.text("没有权限。", NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("只有玩家可以开始测试。");
            return true;
        }
        if (!chamberManager.startPractice(player)) {
            player.sendMessage(Component.text(
                    chamberManager.isReady() ? "你已在测试流程中。" : "测试室尚未配置。",
                    NamedTextColor.RED
            ));
        }
        return true;
    }

    private boolean leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("只有玩家可以退出测试。");
            return true;
        }
        chamberManager.cancel(player, true);
        return true;
    }

    private boolean status(CommandSender sender) {
        if (sender instanceof Player player && chamberManager.isRunning(player)) {
            sender.sendMessage("你正在测试流程中；当前共配置 " + chamberManager.chamberCount() + " 个测试室。");
        } else {
            sender.sendMessage("当前共配置 " + chamberManager.chamberCount() + " 个测试室。");
        }
        return true;
    }

    private boolean build(CommandSender sender) {
        if (!sender.hasPermission("qplugin.chambers.admin")) {
            sender.sendMessage(Component.text("没有权限。", NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("只有玩家可以进入模板世界。");
            return true;
        }
        chamberManager.enterTemplate(player);
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("qplugin.chambers.admin")) {
            sender.sendMessage(Component.text("没有权限。", NamedTextColor.RED));
            return true;
        }
        try {
            chamberManager.reload();
            sender.sendMessage("已重新加载 " + chamberManager.chamberCount() + " 个测试室。");
        } catch (RuntimeException exception) {
            sender.sendMessage(Component.text("测试室配置无效：" + exception.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private boolean sendUsage(CommandSender sender) {
        sender.sendMessage("/chambers <start|leave|status|build|reload>");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        return List.of("start", "leave", "status", "build", "reload").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase()))
                .toList();
    }
}
