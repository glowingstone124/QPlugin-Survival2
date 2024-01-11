package vip.qoriginal.quantumplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatSync implements Listener {
    public ChatSync() {
        // 可能需要一些初始化操作
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        try {
            String playerName = event.getPlayer().getName();
            String message = event.getMessage();
            StringBuilder sb = new StringBuilder();
            sb.append("<").append(playerName).append(">: ").append(message);
            Request.sendPostRequest("http://localhost:8080/qo/msglist/upload", sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
