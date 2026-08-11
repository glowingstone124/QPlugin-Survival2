package vip.qoriginal.quantumplugin.chambers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.Request
import vip.qoriginal.quantumplugin.chambers.data.ChamberRunResult
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class ChambersRegistrationTest(
    private val plugin: ChambersPlugin,
    private val chamberManager: ChamberManager,
) : MinecraftRegistrationTest {
    private val activeSubmissions = ConcurrentHashMap.newKeySet<String>()
    private val reportedInvalidProgress = ConcurrentHashMap.newKeySet<String>()
    private var resultRecoveryTask: BukkitTask? = null

    @Volatile
    private var shuttingDown = false

    override fun isAvailable(): Boolean = chamberManager.isReady()

    fun startResultRecovery() {
        check(resultRecoveryTask == null) { "result recovery is already running" }
        shuttingDown = false
        resultRecoveryTask = plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable(::recoverPersistedResults),
            RESULT_RECOVERY_INITIAL_DELAY_TICKS,
            RESULT_RECOVERY_INTERVAL_TICKS,
        )
    }

    fun shutdown() {
        shuttingDown = true
        resultRecoveryTask?.cancel()
        resultRecoveryTask = null
        activeSubmissions.clear()
    }

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
        ) { result ->
            queueResult(result)
            scheduleExit(player, result)
        }
        if (!started) {
            return MinecraftRegistrationTest.StartResult(
                false,
                "chambers_start_failed",
                "无法开始或恢复测试，请联系测试服务器管理员。",
            )
        }
        if (!chamberManager.isRunning(player)) {
            return MinecraftRegistrationTest.StartResult(
                false,
                "chambers_result_pending",
                "上次测试已经结束，结果正在同步，请返回注册页面查看状态。",
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

    private fun recoverPersistedResults() {
        val scan = try {
            chamberManager.scanTerminalResults()
        } catch (exception: RuntimeException) {
            plugin.logger.warning(
                "无法扫描待提交的测试室结果: ${exception.message}",
            )
            return
        }
        scan.invalidFiles.forEach { invalid ->
            if (reportedInvalidProgress.add(invalid)) {
                plugin.logger.warning("忽略无效的测试室进度文件: $invalid")
            }
        }
        scan.results.forEach(::queueResult)
    }

    private fun queueResult(result: ChamberRunResult) {
        val session = result.registrationSession ?: return
        if (shuttingDown) return
        if (!activeSubmissions.add(session.sessionId)) return
        submitResult(result, 1)
    }

    private fun scheduleExit(player: Player, result: ChamberRunResult) {
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (!player.isOnline || chamberManager.isRunning(player)) return@Runnable
                val message = if (result.passed) {
                    "测试已通过，请返回注册页面完成账户创建。"
                } else {
                    "测试未通过，请返回注册页面查看结果。"
                }
                player.kick(net.kyori.adventure.text.Component.text(message))
            },
            RESULT_EXIT_DELAY_TICKS,
        )
    }

    private fun submitResult(result: ChamberRunResult, attempt: Int) {
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
                    activeSubmissions.remove(session.sessionId)
                    plugin.logger.fine("测试室结果已提交: $body")
                } else {
                    retryResult(result, attempt, "API response: $body")
                }
            }
        }
    }

    private fun retryResult(result: ChamberRunResult, attempt: Int, reason: String) {
        val sessionId = result.registrationSession?.sessionId ?: return
        if (attempt >= MAX_RESULT_ATTEMPTS || shuttingDown || !plugin.isEnabled) {
            activeSubmissions.remove(sessionId)
            plugin.logger.warning(
                "提交测试室结果失败，进度已保留并将在后台继续重试: $reason",
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
        const val RESULT_RECOVERY_INITIAL_DELAY_TICKS = 20L
        const val RESULT_RECOVERY_INTERVAL_TICKS = 20L * 60L
        const val RESULT_EXIT_DELAY_TICKS = 40L
    }
}
