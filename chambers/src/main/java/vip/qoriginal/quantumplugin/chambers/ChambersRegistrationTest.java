package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.entity.Player;
import org.json.JSONObject;
import vip.qoriginal.quantumplugin.Config;
import vip.qoriginal.quantumplugin.Request;
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest;

import java.util.Map;
import java.util.Optional;

public final class ChambersRegistrationTest implements MinecraftRegistrationTest {
    private final ChambersPlugin plugin;
    private final ChamberManager chamberManager;

    public ChambersRegistrationTest(ChambersPlugin plugin, ChamberManager chamberManager) {
        this.plugin = plugin;
        this.chamberManager = chamberManager;
    }

    @Override
    public boolean isAvailable() {
        return chamberManager.isReady();
    }

    @Override
    public StartResult start(Player player, Session session) {
        if (!isAvailable()) {
            return new StartResult(false, "chambers_not_configured", "测试室尚未配置。");
        }
        boolean started = chamberManager.startRegistration(player, session, this::submitResult);
        if (!started) {
            return new StartResult(false, "chambers_player_busy", "玩家已在测试流程中。");
        }
        return new StartResult(true, "chambers_started", "已进入第一个测试室。");
    }

    @Override
    public void cancel(Player player) {
        chamberManager.cancel(player, false);
    }

    private void submitResult(ChamberRunResult result) {
        Session session = result.registrationSession();
        if (session == null) {
            return;
        }
        JSONObject payload = new JSONObject()
                .put("sessionId", session.sessionId())
                .put("name", session.username())
                .put("passed", result.passed());
        Request.sendPostRequest(
                Config.INSTANCE.getAPI_ENDPOINT() + RESULT_ENDPOINT,
                payload.toString(),
                Optional.of(Map.of("Token", Config.INSTANCE.getAPI_SECRET()))
        ).whenComplete((body, error) -> {
            if (error != null) {
                plugin.getLogger().warning("提交测试室结果失败: " + error.getMessage());
            } else {
                plugin.getLogger().fine("测试室结果已提交: " + body);
            }
        });
    }
}
