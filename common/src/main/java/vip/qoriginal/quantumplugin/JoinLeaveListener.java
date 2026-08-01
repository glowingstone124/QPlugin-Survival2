package vip.qoriginal.quantumplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;


public class JoinLeaveListener implements Listener {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(JoinLeaveListener.class);
    private final Map<Player, Long> sessionStartTimes = new HashMap<>();
    ChatSync cs = new ChatSync();
    Login login = new Login();
    public static final String[] blocklist = {"ServerSeeker.net"};
    public static Set<String> ip_whitelist = new HashSet<>();
    public static final Logger logger = LoggerProvider.INSTANCE.getLogger("JoinLeaveListener");

    public static void init() throws IOException {
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) throws Exception {
        Player player = event.getPlayer();
        player.removeScoreboardTag("guest");
        player.removeScoreboardTag("visitor");
        player.removeScoreboardTag("visitor_login");
        DailyLoginAdvertisement.showIfFirstLoginToday(player);
        JsonObject relationship = JsonParser.parseString(Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/registry?name=" + player.getName()).get()).getAsJsonObject();
        if (relationship.has("code")) {
            if (relationship.get("code").getAsInt() == 0) {
                player.sendMessage(Component.text("验证通过，欢迎回到Quantum Original，输入/login 你的密码来登录")
                        .appendNewline()
                        .append(Component.text("QQ: " + relationship.get("qq").getAsLong())
                                .color(TextColor.color(114, 114, 114))));
                login.handleJoin(event.getPlayer(), false);
            }
            sessionStartTimes.put(player, System.currentTimeMillis());

            java.net.InetSocketAddress address = player.getAddress();
            if (address != null) {
                Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/online?name=" + player.getName() + "&ip=" + address.getAddress().getHostAddress(), "",
                        java.util.Optional.of(java.util.Map.of("Token", Config.INSTANCE.getAPI_SECRET())));
            }
        } else if (relationship.get("affiliated").getAsBoolean()) {
            player.sendMessage(Component.text("欢迎回到Quantum Original，输入/login 你的密码来登录")
                    .appendNewline()
                    .append(Component.text("您正在使用附属账户，归属于： " + relationship.get("host").getAsString())
                            .color(TextColor.color(114, 114, 114))));
            login.handleJoin(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) throws Exception {
        Player player = event.getPlayer();
        if (sessionStartTimes.containsKey(player)) {
            long sessionStartTime = sessionStartTimes.get(player);
            long sessionDuration = System.currentTimeMillis() - sessionStartTime;
            long minutesPlayed = sessionDuration / (1000 * 60);
            player.sendMessage("你的本次游玩时长为: " + minutesPlayed + " 分钟");
            cs.sendChatMsg("玩家" + event.getPlayer().getName() + "退出了服务器，本次游玩时间 " + minutesPlayed + "分钟");
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/upload/gametimerecord?name=" + player.getName() + "&time=" + minutesPlayed, "",
                    java.util.Optional.of(java.util.Map.of("Token", Config.INSTANCE.getAPI_SECRET())));
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/offline?name=" + player.getName(), "",
                    java.util.Optional.of(java.util.Map.of("Token", Config.INSTANCE.getAPI_SECRET())));
            sessionStartTimes.remove(player);
        }
    }
}
