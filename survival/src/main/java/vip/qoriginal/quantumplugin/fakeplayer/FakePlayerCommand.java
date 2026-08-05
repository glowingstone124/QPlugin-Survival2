package vip.qoriginal.quantumplugin.fakeplayer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import vip.qoriginal.quantumplugin.CommandMessages;

import java.util.List;
import java.util.Locale;

public final class FakePlayerCommand implements CommandExecutor {
    private final Plugin plugin;
    private final FakePlayerManager manager;

    public FakePlayerCommand(Plugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "spawn" -> spawn(sender, args);
                case "remove" -> remove(sender, args);
                case "list" -> list(sender);
                case "inventory", "inv" -> inventory(sender, args);
                case "attack", "left" -> action(sender, args, FakePlayerActionController.Action.ATTACK);
                case "use", "right" -> action(sender, args, FakePlayerActionController.Action.USE);
                case "look", "turn" -> look(sender, args);
                default -> {
                    CommandMessages.warning(sender, "未知操作: " + args[0]);
                    sendHelp(sender, label);
                    yield true;
                }
            };
        } catch (IllegalArgumentException ex) {
            CommandMessages.error(sender, ex.getMessage());
            return true;
        }
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            CommandMessages.playerOnly(sender);
            return true;
        }
        if (args.length != 2 && args.length != 3) {
            sendUsage(sender, "/fakeplayer spawn <name> [skinPlayer]", "在当前位置生成假人；默认使用同名玩家皮肤。");
            return true;
        }
        Location location = player.getLocation();
        String fakePlayerName = args[1];
        String skinName = args.length == 3 ? args[2] : fakePlayerName;
        CommandMessages.info(sender, "正在获取皮肤: " + skinName);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.mojang.authlib.properties.PropertyMap skinProperties = manager.skinProperties(skinName);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        ServerPlayer fakePlayer = manager.spawn(fakePlayerName, location, skinProperties);
                        CommandMessages.success(sender, "已生成假人 " + fakePlayer.getGameProfile().name() + "，皮肤来源: " + skinName);
                    } catch (IllegalArgumentException ex) {
                        CommandMessages.error(sender, ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> CommandMessages.error(sender, "获取皮肤失败: " + skinName));
            }
        });
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sendUsage(sender, "/fakeplayer remove <name>", "移除指定假人。");
            return true;
        }
        if (manager.remove(args[1])) {
            CommandMessages.success(sender, "已移除假人: " + args[1]);
        } else {
            CommandMessages.warning(sender, "找不到假人: " + args[1]);
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        List<String> names = manager.names();
        if (names.isEmpty()) {
            CommandMessages.info(sender, "当前没有假人。");
        } else {
            sender.sendMessage(CommandMessages.title("当前假人")
                    .appendNewline()
                    .append(Component.text(String.join(", ", names), NamedTextColor.WHITE)));
        }
        return true;
    }

    private boolean inventory(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            CommandMessages.playerOnly(sender);
            return true;
        }
        if (args.length != 2) {
            sendUsage(sender, "/fakeplayer inventory <name>", "打开假人的背包。");
            return true;
        }
        org.bukkit.inventory.PlayerInventory inventory = manager.inventory(args[1]);
        if (inventory == null) {
            CommandMessages.warning(sender, "找不到假人: " + args[1]);
            return true;
        }
        player.openInventory(inventory);
        CommandMessages.success(sender, "已打开 " + args[1] + " 的背包。");
        return true;
    }

    private boolean action(CommandSender sender, String[] args, FakePlayerActionController.Action action) {
        if (args.length < 2 || args.length > 3) {
            String actionName = action == FakePlayerActionController.Action.ATTACK ? "attack" : "use";
            sendUsage(sender, "/fakeplayer " + actionName + " <name> [once|start|stop]", "控制假人的左右键动作。");
            return true;
        }

        FakePlayerActionController.Mode mode = args.length == 2
                ? FakePlayerActionController.Mode.ONCE
                : switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "once" -> FakePlayerActionController.Mode.ONCE;
                    case "start" -> FakePlayerActionController.Mode.START;
                    case "stop" -> FakePlayerActionController.Mode.STOP;
                    default -> throw new IllegalArgumentException("动作模式只能是 once、start 或 stop");
                };
        FakePlayerActionController.Target target = manager.action(args[1], action, mode);
        String actionText = action == FakePlayerActionController.Action.ATTACK ? "攻击" : "使用";
        if (mode == FakePlayerActionController.Mode.STOP) {
            CommandMessages.success(sender, "已停止假人 " + args[1] + " 的" + actionText + "动作");
        } else if (mode == FakePlayerActionController.Mode.START) {
            CommandMessages.success(sender, "假人 " + args[1] + " 已开始持续" + actionText + "，当前目标: " + targetText(target));
        } else {
            CommandMessages.success(sender, "假人 " + args[1] + " 已执行一次" + actionText + "，目标: " + targetText(target));
        }
        return true;
    }

    private String targetText(FakePlayerActionController.Target target) {
        return switch (target) {
            case ENTITY -> "实体";
            case BLOCK -> "方块";
            case AIR -> "空气";
        };
    }

    private boolean look(CommandSender sender, String[] args) {
        if (args.length == 4) {
            double yaw = number(args[2], "yaw");
            double pitch = number(args[3], "pitch");
            FakePlayerManager.Rotation rotation = manager.look(args[1], yaw, pitch);
            CommandMessages.success(sender, "已将假人 " + args[1] + " 转向 yaw="
                    + angle(rotation.yaw()) + "°, pitch=" + angle(rotation.pitch()) + "°");
            return true;
        }
        if (args.length == 6 && args[2].equalsIgnoreCase("at")) {
            double x = number(args[3], "x");
            double y = number(args[4], "y");
            double z = number(args[5], "z");
            FakePlayerManager.Rotation rotation = manager.lookAt(args[1], x, y, z);
            CommandMessages.success(sender, "假人 " + args[1] + " 已看向 (" + args[3] + ", " + args[4] + ", " + args[5]
                    + ")，yaw=" + angle(rotation.yaw()) + "°, pitch=" + angle(rotation.pitch()) + "°");
            return true;
        }

        sendUsage(sender, "/fakeplayer look <name> <yaw> <pitch>", "直接设置假人朝向。");
        sendUsage(sender, "/fakeplayer look <name> at <x> <y> <z>", "让假人看向世界坐标。");
        return true;
    }

    private double number(String raw, String name) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " 必须是有限数字: " + raw);
        }
    }

    private String angle(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void sendHelp(CommandSender sender, String label) {
        String root = "/" + label;
        sender.sendMessage(CommandMessages.title("假人管理")
                .appendNewline()
                .append(commandLine(root + " spawn <name> [skinPlayer]", "生成假人"))
                .appendNewline()
                .append(commandLine(root + " remove <name>", "移除假人"))
                .appendNewline()
                .append(commandLine(root + " list", "查看假人列表"))
                .appendNewline()
                .append(commandLine(root + " inventory <name>", "打开假人背包"))
                .appendNewline()
                .append(commandLine(root + " attack <name> [once|start|stop]", "左键攻击或挖掘"))
                .appendNewline()
                .append(commandLine(root + " use <name> [once|start|stop]", "右键交互或使用物品"))
                .appendNewline()
                .append(commandLine(root + " look <name> <yaw> <pitch>", "设置假人朝向"))
                .appendNewline()
                .append(commandLine(root + " look <name> at <x> <y> <z>", "让假人看向坐标")));
    }

    private void sendUsage(CommandSender sender, String usage, String description) {
        sender.sendMessage(CommandMessages.title("用法")
                .appendNewline()
                .append(CommandMessages.command(usage).clickEvent(ClickEvent.suggestCommand(usage)))
                .append(Component.space())
                .append(CommandMessages.muted("- " + description)));
    }

    private Component commandLine(String command, String description) {
        return CommandMessages.command(command)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.suggestCommand(command))
                .append(Component.space())
                .append(CommandMessages.muted("- " + description));
    }
}
