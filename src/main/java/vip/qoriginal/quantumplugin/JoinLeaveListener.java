package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public class JoinLeaveListener implements Listener {
    private final Map<Player, Long> sessionStartTimes = new HashMap<>();
    ChatSync cs = new ChatSync();
    Login login = new Login();
    public static final String prolist_path = "pros.txt";
    public static String[] prolist = {"MineCreeper2086", "Wsiogn82", "glowingstone124"};
    public static final String[] blocklist = {"ServerSeeker.net"};
    public static void init() throws IOException {
        prolist = Files.readString(Path.of(prolist_path)).split("\n");
    }
    @EventHandler
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) throws Exception {
        String playerName = event.getName();

        if (Arrays.asList(blocklist).contains(playerName)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("[403 Forbidden]")
                            .append(Component.text("this server doesn't allows ServerSeeker.").decorate(TextDecoration.BOLD)));
            return;
        }

        if (!Arrays.asList(prolist).contains(playerName)) {
            BindResponse relationship = new Gson().fromJson(Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/registry?name=" + playerName), BindResponse.class);

            if (relationship.code == 1) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("[401 Unauthorized]验证失败，请在群：946085440中下载QCommunity")
                                .append(Component.text("并绑定你的游戏名：" + playerName).decorate(TextDecoration.BOLD))
                                .append(Component.text(" 之后重试！")));
            } else if (relationship.frozen) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("[403 Forbidden]验证失败，原因：您的账户已经被冻结！")
                                .append(Component.text("您的游戏名：" + playerName).decorate(TextDecoration.BOLD))
                                .append(Component.text(" 请私聊群主：1294915648了解更多")));
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) throws Exception {
        QuantumPlugin quantumPlugin = QuantumPlugin.getInstance();
        Player player = event.getPlayer();

        Thread.startVirtualThread(() -> {
            try {
                IPUtils.locIsCn(event, quantumPlugin);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        if (!Arrays.asList(prolist).contains(player.getName())) {
            BindResponse relationship = new Gson().fromJson(Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/registry?name=" + player.getName()), BindResponse.class);
            player.sendMessage(Component.text("验证通过，欢迎回到Quantum Original，输入/login 你的密码来登录")
                    .appendNewline()
                    .append(Component.text("QQ: " + relationship.qq)
                            .color(TextColor.color(114, 114, 114))));
            sessionStartTimes.put(player, System.currentTimeMillis());
            login.handleJoin(event.getPlayer());
        } else {
            player.sendMessage(Component.text(String.format("您好， %s， 您享有免验证权", player.getName())));
            sessionStartTimes.put(player, System.currentTimeMillis());
        }
        cs.sendChatMsg("玩家" + player.getName() + "加入了服务器。");
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
            Request.sendPostRequest("http://qoriginal.vip:8080/qo/upload/gametimerecord?name=" + player.getName() + "&time=" + minutesPlayed, "");
            sessionStartTimes.remove(player);
        }
    }
}
