package vip.qoriginal.quantumplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.json.JSONObject;

import java.util.Objects;

public class IPUtils{
    public static void locIsCn(PlayerJoinEvent event, Plugin plugin) throws Exception {
        Logger logger = new Logger();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ChatSync cs = new ChatSync();
                String ip = Objects.requireNonNull(player.getAddress()).getAddress().getHostAddress();
                JSONObject ipLocObj = new JSONObject(Request.sendGetRequest("https://ip.shakaianee.top/" + ip + "?f=json").get());
                System.out.println(ipLocObj);
                logger.log("Player " + player.getName() + " logging in with an ip " + ipLocObj.getString("addr") + " in region " + ipLocObj.getString("country_code"), "IPUtils");
                if (!ipLocObj.getString("country_code").equals("CN")) {
                    player.sendMessage("你正在使用一个非中国大陆ip登录。");
                    cs.sendChatMsg("玩家" + player.getName() + "正在使用一个非中国大陆ip登录. (" + ip + ")");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
