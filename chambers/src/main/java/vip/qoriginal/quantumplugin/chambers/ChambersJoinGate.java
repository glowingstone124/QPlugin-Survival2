package vip.qoriginal.quantumplugin.chambers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.json.JSONObject;
import vip.qoriginal.quantumplugin.Config;
import vip.qoriginal.quantumplugin.Request;
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ChambersJoinGate implements Listener {
    private final ChambersPlugin plugin;
    private final ChambersRegistrationTest registrationTest;
    private final Set<UUID> waitingForClaim = new HashSet<>();

    public ChambersJoinGate(ChambersPlugin plugin, ChambersRegistrationTest registrationTest) {
        this.plugin = plugin;
        this.registrationTest = registrationTest;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!registrationTest.isAvailable()) {
            reject(player, "测试服务器尚未配置可用的 chamber。");
            return;
        }
        waitingForClaim.add(player.getUniqueId());
        isolateWhileWaiting(player);

        JSONObject payload = new JSONObject().put("name", player.getName());
        try {
            Request.sendPostRequest(
                    Config.INSTANCE.getAPI_ENDPOINT() + MinecraftRegistrationTest.CLAIM_ENDPOINT,
                    payload.toString(),
                    Optional.of(Map.of("Token", Config.INSTANCE.getAPI_SECRET()))
            ).whenComplete((body, error) -> plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> handleClaimResponse(player.getUniqueId(), body, error)
            ));
        } catch (Throwable error) {
            plugin.getLogger().warning("Unable to claim Minecraft test session: " + error.getMessage());
            reject(player, "测试服务器未能读取 API 配置。");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (waitingForClaim.contains(event.getPlayer().getUniqueId())) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waitingForClaim.remove(event.getPlayer().getUniqueId());
        reveal(event.getPlayer());
    }

    private void handleClaimResponse(UUID playerId, String body, Throwable error) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline() || !waitingForClaim.remove(playerId)) {
            return;
        }
        if (error != null) {
            reject(player, "无法连接账户验证服务，请稍后重试。");
            return;
        }

        JSONObject response;
        try {
            response = new JSONObject(body);
        } catch (RuntimeException exception) {
            reject(player, "账户验证服务返回了无效响应。");
            return;
        }
        String sessionId = response.optString("sessionId", "");
        if (sessionId.isBlank() || !"claimed".equals(response.optString("state"))) {
            reject(player, response.optString(
                    "message",
                    "没有待处理的 Minecraft 测试请求，请先从注册页面发起测试。"
            ));
            return;
        }

        reveal(player);
        player.setInvulnerable(false);
        player.setGameMode(GameMode.ADVENTURE);
        MinecraftRegistrationTest.StartResult result = registrationTest.start(
                player,
                new MinecraftRegistrationTest.Session(sessionId, player.getName())
        );
        if (!result.accepted()) {
            reject(player, result.message());
        }
    }

    private void isolateWhileWaiting(Player player) {
        player.setInvulnerable(true);
        player.setGameMode(GameMode.SPECTATOR);
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            player.hidePlayer(plugin, other);
            other.hidePlayer(plugin, player);
        }
        player.sendMessage(Component.text("正在确认待处理的测试请求……", NamedTextColor.YELLOW));
    }

    private void reveal(Player player) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            player.showPlayer(plugin, other);
            other.showPlayer(plugin, player);
        }
    }

    private void reject(Player player, String reason) {
        waitingForClaim.remove(player.getUniqueId());
        reveal(player);
        player.kick(Component.text(reason, NamedTextColor.RED));
    }
}
