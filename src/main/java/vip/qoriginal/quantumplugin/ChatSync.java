package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.*;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static vip.qoriginal.quantumplugin.QuantumPlugin.isShutup;

public class ChatSync implements Listener {
    private final static int QO_CODE = 1;
    private static Gson gson = new Gson();
    private static WebMsgGetter webMsgGetter;
    static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    public void init() {
        webMsgGetter = new WebMsgGetter();
        scheduler.scheduleAtFixedRate(webMsgGetter, 0, 500, TimeUnit.MILLISECONDS);
    }
    public static void exit(){
        scheduler.shutdown();
    }
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!isShutup(event.getPlayer())) {
            Thread.startVirtualThread(() -> {
                try {
                    String playerName = event.getPlayer().getName();
                    String message = event.getMessage();
                    StringBuilder sb = new StringBuilder();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String currentTime = sdf.format(new Date());
                    sb.append("[").append(currentTime).append("]").append("<").append(playerName).append(">: ").append(message);
                    String encodedMessage = new String(sb.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
                    Request.sendPostRequest("http://qoriginal.vip:8080/qo/msglist/upload", generateCredential(encodedMessage));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }
    public void sendChatMsg(String message){
        Thread.startVirtualThread(() -> {
            try {
                String encodedMessage = new String(message.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
                Request.sendPostRequest("http://qoriginal.vip:8080/qo/msglist/upload",  generateCredential(encodedMessage));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    class WebMsgGetter implements Runnable {
        String buffer = "";

        @Override
        public void run() {
            try {
                String response = Request.sendGetRequest("http://qoriginal.vip:8080/qo/msglist/download");
                JsonParser parser = new JsonParser();
                JsonElement jsonElement = parser.parse(response);
                if (jsonElement.isJsonObject()) {
                    JsonObject msgObj = jsonElement.getAsJsonObject();
                    if (msgObj.get("code").getAsInt() == 0) {
                        String content = parseCQ(msgObj.get("content").getAsString());
                        if (!content.equals(buffer)) {
                            Component msgComponent = Component.text(content).color(TextColor.color(113, 159, 165));
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                p.sendMessage(msgComponent);
                            }

                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public String parseCQ(String content) {
        content = content.replaceAll("\\[CQ:face,id=.*?\\]", "[表情]");
        content = content.replaceAll("\\[CQ:image,file=.*?\\]", "[图片]");
        content = content.replaceAll("\\[CQ:record,file=.*?\\]", "[语音]");
        content = content.replaceAll("\\[CQ:share,file=.*?\\]", "[链接]");
        content = content.replaceAll("\\[CQ:mface,.*?\\]", "[表情]");
        content = content.replace("CQ:at,qq=", "@");
        return content;
    }
    public static String generateCredential(String message) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("message", message);
        jsonObject.addProperty("from", QO_CODE);
        jsonObject.addProperty("token", "aad3r32in213ndvv11@");
        return jsonObject.toString();
    }
}
