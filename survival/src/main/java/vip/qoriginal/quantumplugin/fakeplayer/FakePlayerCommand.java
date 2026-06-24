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
                .append(commandLine(root + " inventory <name>", "打开假人背包")));
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
