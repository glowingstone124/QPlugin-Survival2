package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.checkerframework.checker.units.qual.A;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public class JoinLeaveListener implements Listener {
    private Map<Player, Long> sessionStartTimes = new HashMap<>();
    public static final String[] prolist = {"MineCreeper2086", "Wsiogn82", "glowingstone124"};
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) throws Exception {
        Player player = event.getPlayer();
        if (!Arrays.asList(prolist).contains(player.getName())) {
            event.getPlayer().sendMessage(Component.text("请稍等，我们需要对您的身份进行验证"));
            BindResponse relationship = new Gson().fromJson(Request.sendGetRequest("http://127.0.0.1:8080/qo/download/registry?name=" + event.getPlayer().getName()), BindResponse.class);
            if (relationship.code == 1) {
                event.getPlayer().kick(Component.text("验证失败，请在QQ群:946085440下载QCommunity并且进入 ").append(Component.text("bind界面绑定你的游戏名:" + event.getPlayer().getName()).decorate(TextDecoration.BOLD)).append(Component.text(" 并重试！")));
            } else if (relationship.frozen) {
                event.getPlayer().kick(Component.text("验证失败，原因：您的账户已经被冻结！ ").append(Component.text("您的游戏名：" + event.getPlayer().getName()).decorate(TextDecoration.BOLD)).append(Component.text(" 请私聊群主：1294915648了解更多")));
            } else {
                event.getPlayer().sendMessage(Component.text("验证通过，欢迎回到Quantum Original！").appendNewline().append(Component.text("QQ: " + relationship.qq).color(TextColor.color(114, 114, 114))));
                sessionStartTimes.put(player, System.currentTimeMillis());
            }
        } else {
            event.getPlayer().sendMessage(Component.text(String.format("您好， %s， 您享有免验证权", player.getName())));
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
            Request.sendPostRequest("http://qoriginal.vip:8080/qo/upload/gametimerecord?name=" + player.getName() + "&time=" + minutesPlayed, "");
            sessionStartTimes.remove(player);
        }
    }
}
