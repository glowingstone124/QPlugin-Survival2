package vip.qoriginal.quantumplugin.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
            sender.sendMessage("用法: /fakeplayer <spawn|remove|list> ...");
            return true;
        }

        try {
            return switch (args[0].toLowerCase()) {
                case "spawn" -> spawn(sender, args);
                case "remove" -> remove(sender, args);
                case "list" -> list(sender);
                case "inventory", "inv" -> inventory(sender, args);
                default -> {
                    sender.sendMessage("用法: /fakeplayer <spawn|remove|list|inventory> ...");
                    yield true;
                }
            };
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ex.getMessage());
            return true;
        }
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }
        if (args.length != 2 && args.length != 3) {
            sender.sendMessage("用法: /fakeplayer spawn <name> [skinPlayer]");
            return true;
        }
        Location location = player.getLocation();
        String fakePlayerName = args[1];
        String skinName = args.length == 3 ? args[2] : fakePlayerName;
        sender.sendMessage("正在获取皮肤: " + skinName);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.mojang.authlib.properties.PropertyMap skinProperties = manager.skinProperties(skinName);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        ServerPlayer fakePlayer = manager.spawn(fakePlayerName, location, skinProperties);
                        sender.sendMessage("已生成假人: " + fakePlayer.getGameProfile().name() + "，皮肤来源: " + skinName);
                    } catch (IllegalArgumentException ex) {
                        sender.sendMessage(ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("获取皮肤失败: " + skinName));
            }
        });
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("用法: /fakeplayer remove <name>");
            return true;
        }
        if (manager.remove(args[1])) {
            sender.sendMessage("已移除假人: " + args[1]);
        } else {
            sender.sendMessage("找不到假人: " + args[1]);
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        List<String> names = manager.names();
        if (names.isEmpty()) {
            sender.sendMessage("当前没有假人");
        } else {
            sender.sendMessage("假人: " + String.join(", ", names));
        }
        return true;
    }

    private boolean inventory(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("用法: /fakeplayer inventory <name>");
            return true;
        }
        org.bukkit.inventory.PlayerInventory inventory = manager.inventory(args[1]);
        if (inventory == null) {
            sender.sendMessage("找不到假人: " + args[1]);
            return true;
        }
        player.openInventory(inventory);
        return true;
    }
}
