package vip.qoriginal.quantumplugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ChatCommandListener implements Listener {
    @EventHandler
    public void onChat(AsyncChatEvent event) throws Exception {
        if ((event.message().toString().contains("怎么") || event.message().toString().contains("如何")) && event.message().toString().length() > 4) {
            event.getPlayer().sendMessage(Component.text("【友情提醒】本服务器已和 ")
                    .append(Component.text("G").color(TextColor.color(66, 133, 244)))
                    .append(Component.text("o").color(TextColor.color(233, 66, 53)))
                    .append(Component.text("o").color(TextColor.color(250, 187, 5)))
                    .append(Component.text("g").color(TextColor.color(66, 133, 244)))
                    .append(Component.text("l").color(TextColor.color(52, 168, 83)))
                    .append(Component.text("e").color(TextColor.color(233, 66, 53)))
                    .append(Component.text(" 达成合作，有不懂的可以直接查！")));
        }

        if (event.message().toString().startsWith("./")) {
            String command = "/" + event.message().toString().substring(2).trim();
            Component chatShareCommandComponent = Component.text("玩家 <")
                    .append(Component.text(event.getPlayer().getName()))
                    .append(Component.text("> 分享了了命令: [").clickEvent(ClickEvent.copyToClipboard(command)))
                    .append(Component.text(command).color(TextColor.color(246,190,0)))
                    .append(Component.text("]\n")
                            .append(Component.text("点击复制到剪贴板，请谨慎使用指令。")));
            broadcast(chatShareCommandComponent);
        }
    }

    public static void broadcast(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }
}
