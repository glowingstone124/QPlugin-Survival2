package vip.qoriginal.quantumplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.text.SimpleDateFormat;
import java.util.Date;

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
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String currentTime = sdf.format(new Date());
            sb.append("[" + currentTime + "]").append("<").append(playerName).append(">: ").append(message);
            String encodedMessage = new String(sb.toString().getBytes("UTF-8"), "ISO-8859-1");
            Request.sendPostRequest("http://localhost:8080/qo/msglist/upload", encodedMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
