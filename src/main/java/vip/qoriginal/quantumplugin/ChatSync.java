package vip.qoriginal.quantumplugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatSync implements Listener {
    private static WebMsgGetter webMsgGetter;
    static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    public void init() {
        webMsgGetter = new WebMsgGetter();
        scheduler.scheduleAtFixedRate(webMsgGetter, 0, 3, TimeUnit.SECONDS);
    }
    public static void exit(){
        scheduler.shutdown();
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
    public void sendChatMsg(String message){
        try {
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String currentTime = sdf.format(new Date());
            sb.append("[" + currentTime + "]").append(message);
            String encodedMessage = new String(sb.toString().getBytes("UTF-8"), "ISO-8859-1");
            Request.sendPostRequest("http://localhost:8080/qo/msglist/upload", encodedMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    class WebMsgGetter implements Runnable {
        String buffer = "";
        @Override
        public void run() {
            try {
                String response = Request.sendGetRequest("http://localhost:8080/qo/msglist/download");
                JsonParser parser = new JsonParser();
                JsonElement jsonElement = parser.parse(response);
                if (jsonElement.isJsonObject()) {
                    JsonObject msgObj = jsonElement.getAsJsonObject();
                    if (msgObj.get("code").getAsInt() == 0){
                        String content = msgObj.get("content").getAsString();
                        if (!content.equals(buffer)){
                            Bukkit.getServer().broadcastMessage("[QC]" + content);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
