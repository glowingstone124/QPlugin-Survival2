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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.ExecutionException;

public class IPUtils {
    private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor();
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
                fetchIpLocationWithRetriesAsync(ip, 3, result -> {
                    if (result == null) {
                        logger.log("无法获取IP地址信息");
                        Utils.INSTANCE.runTaskOnMainThread(() ->
                        {
                            player.kick(Component.text("无法获取IP属地信息，看上去似乎API失效了，请联系glowingstone124."));
                        });
                        return;
                    }

                    logger.log("Player " + player.getName() + " logging in with an IP " + ip );
                    if (!result) {
                        player.sendMessage("你正在使用一个非中国大陆IP登录。");
                        cs.sendChatMsg("玩家 " + player.getName() + " 正在使用一个非中国大陆IP登录");
                        try {
                            if (!fetchIpIsWhitelisted(ip)) {
                                player.sendMessage("你正在使用一个非中国大陆IP登录，请联系glowingstone124或者在app.qoriginal.vip过白您的ip。您的IP：" + ip);
                                Utils.INSTANCE.runTaskOnMainThread(() -> {
                                    player.kick(Component.text("你正在使用一个非中国大陆IP登录，请联系glowingstone124过白您的IP。您的IP：" + ip));
                                });
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void fetchIpLocationWithRetriesAsync(String ip, int retries, Consumer<Boolean> callback) {
        fetchIpLocationWithRetriesAsync(ip, retries, 0, callback);
    }

    private static void fetchIpLocationWithRetriesAsync(String ip, int retries, int attempt, Consumer<Boolean> callback) {
        CompletableFuture<String> request = Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/ip?ip=" + ip);
        request.handle((response, err) -> {
            if (err == null) {
                callback.accept(Objects.equals("true", response));
                return null;
            }
            int nextAttempt = attempt + 1;
            if (nextAttempt >= retries) {
                callback.accept(null);
                return null;
            }
            System.err.println("尝试获取IP地址信息失败，第 " + nextAttempt + " 次重试...");
            RETRY_SCHEDULER.schedule(() -> fetchIpLocationWithRetriesAsync(ip, retries, nextAttempt, callback), 400, TimeUnit.MILLISECONDS);
            return null;
        });
    }
    private static boolean fetchIpIsWhitelisted(String ip) throws ExecutionException, InterruptedException {
        JsonObject response = (JsonObject) JsonParser.parseString(Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/ip/whitelisted?ip=" + ip).get());
        return response.get("whitelisted").getAsBoolean();
    }

}
