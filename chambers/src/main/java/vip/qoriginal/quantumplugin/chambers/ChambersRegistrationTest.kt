package vip.qoriginal.quantumplugin.chambers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.Request
import vip.qoriginal.quantumplugin.chambers.data.ChamberRunResult
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest
import java.util.Optional

class ChambersRegistrationTest(
    private val plugin: ChambersPlugin,
    private val chamberManager: ChamberManager,
) : MinecraftRegistrationTest {
    override fun isAvailable(): Boolean = chamberManager.isReady()

    override fun start(
        player: Player,
        session: MinecraftRegistrationTest.Session,
    ): MinecraftRegistrationTest.StartResult {
        if (!isAvailable()) {
            return MinecraftRegistrationTest.StartResult(
                false,
                "chambers_not_configured",
                "测试室尚未配置。",
            )
        }
        val started = chamberManager.startRegistration(
            player,
            session,
            ::submitResult,
        )
        if (!started) {
            return MinecraftRegistrationTest.StartResult(
                false,
                "chambers_start_failed",
                "无法开始或恢复测试，请联系测试服务器管理员。",
            )
        }
        return MinecraftRegistrationTest.StartResult(
            true,
            "chambers_started",
            "已进入第一个测试室。",
        )
    }

    override fun cancel(player: Player) {
        chamberManager.cancel(player, false)
    }

    private fun submitResult(result: ChamberRunResult, attempt: Int = 1) {
        val session = result.registrationSession ?: return
        val payload = JsonObject().apply {
            addProperty("sessionId", session.sessionId)
            addProperty("name", session.username)
            addProperty("passed", result.passed)
        }
        Request.sendPostRequest(
            Config.API_ENDPOINT + MinecraftRegistrationTest.RESULT_ENDPOINT,
            payload.toString(),
            Optional.of(mapOf("Token" to Config.API_SECRET)),
        ).whenComplete { body, error ->
            if (error != null) {
                retryResult(result, attempt, error.message ?: "network error")
            } else {
                val completed = runCatching {
                    JsonParser.parseString(body).asJsonObject
                        .get("state")?.asString == "completed"
                }.getOrDefault(false)
                if (completed) {
                    chamberManager.clearProgress(session.sessionId)
                    plugin.logger.fine("测试室结果已提交: $body")
                } else {
                    retryResult(result, attempt, "API response: $body")
                }
            }
        }
    }

    private fun retryResult(result: ChamberRunResult, attempt: Int, reason: String) {
        if (attempt >= MAX_RESULT_ATTEMPTS || !plugin.isEnabled) {
            plugin.logger.warning(
                "提交测试室结果失败，进度已保留以便玩家重连后重试: $reason",
            )
            return
        }
        val delayTicks = 40L shl (attempt - 1)
        plugin.logger.warning(
            "提交测试室结果失败，将在 ${delayTicks / 20} 秒后重试 " +
                "(${attempt + 1}/$MAX_RESULT_ATTEMPTS): $reason",
        )
        plugin.server.scheduler.runTaskLaterAsynchronously(
            plugin,
            Runnable { submitResult(result, attempt + 1) },
            delayTicks,
        )
    }

    private companion object {
        const val MAX_RESULT_ATTEMPTS = 5
    }
}
