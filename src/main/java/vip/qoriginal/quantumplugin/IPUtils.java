package vip.qoriginal.quantumplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.json.JSONObject;
import vip.qoriginal.quantumplugin.patch.Utils;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

public class IPUtils {
    public static void locIsCn(PlayerJoinEvent event, Plugin plugin) {
        Logger logger = LoggerProvider.INSTANCE.getLogger("IPUtils");
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ChatSync cs = new ChatSync();
                String ip = Objects.requireNonNull(player.getAddress()).getAddress().getHostAddress();
                if (ip.startsWith("127.0.0.1")) {
                    return;
                }
                //JSONObject ipLocObj = fetchIpLocationWithRetries(ip, 3); Old ways
                Boolean ipIsInCn = fetchIpLocationWithRetries(ip, 3);
                if (ipIsInCn == null) {
                    logger.log("无法获取IP地址信息");
                    Utils.INSTANCE.runTaskOnMainThread(() ->
                    {
                        player.kick(Component.text("无法获取IP属地信息，看上去似乎API失效了，请联系glowingstone124."));
                    });
                    return;
                }

                logger.log("Player " + player.getName() + " logging in with an IP " + ip );
                if (!ipIsInCn) {
                    player.sendMessage("你正在使用一个非中国大陆IP登录。");
                    cs.sendChatMsg("玩家 " + player.getName() + " 正在使用一个非中国大陆IP登录");
                    if (!fetchIpIsWhitelisted(ip)) {
                        player.sendMessage("你正在使用一个非中国大陆IP登录，请联系glowingstone124或者在app.qoriginal.vip过白您的ip。您的IP：" + ip);
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

    private static Boolean fetchIpLocationWithRetries(String ip, int retries) {
        int attempts = 0;
        while (attempts < retries) {
            try {
                String response = Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/ip?ip=" + ip).get();
                return Objects.equals("true", response);
            } catch (Exception e) {
                attempts++;
                System.err.println("尝试获取IP地址信息失败，第 " + attempts + " 次重试...");
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }
    private static boolean fetchIpIsWhitelisted(String ip) throws ExecutionException, InterruptedException {
        JsonObject response = (JsonObject) JsonParser.parseString(Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/ip/whitelisted?ip=" + ip).get());
        return response.get("whitelisted").getAsBoolean();
    }

}
