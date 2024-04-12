package vip.qoriginal.quantumplugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.checkerframework.checker.units.qual.A;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class ChatSync implements Listener {
    static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
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
            sb.append("[SERVER][" + currentTime + "]").append("<").append(playerName).append(">: ").append(message);
            String encodedMessage = new String(sb.toString().getBytes("UTF-8"), "ISO-8859-1");
            Request.sendPostRequest("http://qoriginal.vip:8080/qo/msglist/upload?auth=2djg45uifjs034", encodedMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void sendChatMsg(String message){
        try {
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String currentTime = sdf.format(new Date());
            sb.append("[SERVER][" + currentTime + "]").append(message);
            String encodedMessage = new String(sb.toString().getBytes("UTF-8"), "ISO-8859-1");
            Request.sendPostRequest("http://qoriginal.vip:8080/qo/msglist/upload?auth=2djg45uifjs034", encodedMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
