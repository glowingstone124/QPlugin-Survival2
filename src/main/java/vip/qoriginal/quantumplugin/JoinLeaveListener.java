package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kotlinx.coroutines.Dispatchers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Server;
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
    public static final Logger logger = new Logger();

    public static void init() throws IOException {
    }

    @EventHandler
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) throws Exception {
        String playerName = event.getName();

        if (Arrays.asList(blocklist).contains(playerName)) {
            logger.log("Player " + playerName + " was blocked and wanted to join in", "LoginManager");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("[403 Forbidden]")
                            .append(Component.text("this server doesn't allows ServerSeeker.").decorate(TextDecoration.BOLD)));
            return;
        }

        JsonObject relationship = null;
        try {
            relationship = JsonParser.parseString(Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/registry?name=" + playerName).get()).getAsJsonObject();
        } catch (Exception e) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("[500 Internal Server Error]内部验证出现错误。请等待之后再试。。。"));
        }
        if (relationship == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("[500 Internal Server Error]内部验证出现错误。请等待之后再试。。。"));
            return;
        }
        logger.log("Player " + playerName + " didn't register but wanted to join in", "LoginManager");
        if (relationship.has("code") && relationship.get("code").getAsInt() == 1) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("[401 Unauthorized]您还没有注册QO账号，请前往app.qoriginal.vip注册您的账号并加入群946085440来验。")
                            .append(Component.text("你的游戏名：" + playerName).decorate(TextDecoration.BOLD)));
        } else if (relationship.has("frozen ") && relationship.get("frozen").getAsBoolean()) {
            logger.log("Player " + playerName + " was frozen.", "LoginManager");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("[403 Forbidden]验证失败，原因：您的账户已经被冻结！")
                            .append(Component.text("您的游戏名：" + playerName).decorate(TextDecoration.BOLD))
                            .append(Component.text(" 请私聊群主：1294915648了解更多")));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) throws Exception {
        QuantumPlugin quantumPlugin = QuantumPlugin.getInstance();
        Player player = event.getPlayer();
        player.removeScoreboardTag("guest");
        player.removeScoreboardTag("visitor");
        Thread.startVirtualThread(() -> {
            try {
                IPUtils.locIsCn(event, quantumPlugin);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
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

            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/online?name=" + player.getName(), "");
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
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/upload/gametimerecord?name=" + player.getName() + "&time=" + minutesPlayed, "");
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/offline?name=" + player.getName(), "");
            sessionStartTimes.remove(player);
        }
    }
}
