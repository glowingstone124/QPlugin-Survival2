package vip.qoriginal.quantumplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.json.JSONObject;

public class IPUtils{
    public static void locIsCn(PlayerJoinEvent event, Plugin plugin) throws Exception {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ChatSync cs = new ChatSync();
                String ip = player.getAddress().getHostString();
                JSONObject ipLocObj = new JSONObject(Request.sendGetRequest("https://api.ip.sb/geoip/" + ip));
                if (!ipLocObj.getString("country").equals("China")) {
                    player.sendMessage("你正在使用一个非中国大陆ip登录。");
                    cs.sendChatMsg("玩家" + player.getName() + "正在使用一个非中国大陆ip登录. (" + ip + ")");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
