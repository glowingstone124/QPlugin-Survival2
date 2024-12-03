package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.json.JSONObject;
import vip.qoriginal.quantumplugin.patch.Utils;

import java.util.Objects;

public class IPUtils {
    public static void locIsCn(PlayerJoinEvent event, Plugin plugin) {
        Logger logger = new Logger();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ChatSync cs = new ChatSync();
                String ip = Objects.requireNonNull(player.getAddress()).getAddress().getHostAddress();
                JSONObject ipLocObj = fetchIpLocationWithRetries(ip, 3);
                if (ipLocObj == null) {
                    logger.log("无法获取IP地址信息", "IPUtils");
                    Utils.INSTANCE.runTaskOnMainThread(() ->
                    {
                        player.kick(Component.text("无法获取IP属地信息，看上去似乎API失效了，请联系glowingstone124."));
                    });
                    return;
                }

                logger.log("Player " + player.getName() + " logging in with an IP " + ipLocObj.getString("addr") + " in region " + ipLocObj.getString("country_code"), "IPUtils");
                if (!ipLocObj.getString("country_code").equals("CN")) {
                    player.sendMessage("你正在使用一个非中国大陆IP登录。");
                    cs.sendChatMsg("玩家 " + player.getName() + " 正在使用一个非中国大陆IP登录");
                    if (!JoinLeaveListener.ip_whitelist.contains(ip)) {
                        player.sendMessage("你正在使用一个非中国大陆IP登录，请联系glowingstone124过白您的IP。您的IP：" + ip);
                        Utils.INSTANCE.runTaskOnMainThread(() -> {
                            player.kick(Component.text("你正在使用一个非中国大陆IP登录，请联系glowingstone124过白您的IP。您的IP：" + ip));
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static JSONObject fetchIpLocationWithRetries(String ip, int retries) {
        int attempts = 0;
        while (attempts < retries) {
            try {
                String response = Request.sendGetRequest("https://ip.shakaianee.top/" + ip + "?f=json").get();
                return new JSONObject(response);
            } catch (Exception e) {
                attempts++;
                System.err.println("尝试获取IP地址信息失败，第 " + attempts + " 次重试...");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

}
