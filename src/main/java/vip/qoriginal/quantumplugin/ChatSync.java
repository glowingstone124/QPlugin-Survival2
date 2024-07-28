package vip.qoriginal.quantumplugin;

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
                    Request.sendPostRequest("http://qoriginal.vip:8080/qo/msglist/upload?auth=2djg45uifjs034", encodedMessage);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }
    public void sendChatMsg(String message){
        Thread.startVirtualThread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(message);
                String encodedMessage = new String(sb.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
                Request.sendPostRequest("http://qoriginal.vip:8080/qo/msglist/upload?auth=2djg45uifjs034", encodedMessage);
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
                    if (msgObj.get("code").getAsInt() == 0){
                        String content = parseCQ(msgObj.get("content").getAsString());
                        if (!content.equals(buffer)){
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
    public String parseCQ(String msg) throws Exception {
        msg = msg.replaceAll("\\[CQ:reply,id=\\d+\\]", "[回复]");

        Pattern atPattern = Pattern.compile("\\[CQ:at,qq=(\\d+)\\]");
        Matcher atMatcher = atPattern.matcher(msg);
        StringBuffer result = new StringBuffer();
        while (atMatcher.find()) {
            String qq = atMatcher.group(1);
            String playername = getPlayername(qq);
            atMatcher.appendReplacement(result, "@" + playername);
        }
        atMatcher.appendTail(result);
        msg = result.toString();

        msg = msg.replaceAll("\\[CQ:image,file=[^\\]]+\\]", "[图片]");
        return msg;
    }

    private String getPlayername(String input) throws Exception {
        JsonObject playerObj = JsonParser.parseString(Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/name?qq=" + input)).getAsJsonObject();
        if (playerObj.get("code").getAsInt() == 0) return playerObj.get("username").getAsString();
        return input;
    }
}
