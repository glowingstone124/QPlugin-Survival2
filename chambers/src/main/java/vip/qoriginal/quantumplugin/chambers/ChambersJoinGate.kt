package vip.qoriginal.quantumplugin.chambers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.Request
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest
import java.util.Optional
import java.util.UUID

class ChambersJoinGate(
    private val plugin: ChambersPlugin,
    private val registrationTest: ChambersRegistrationTest,
) : Listener {
    private val waitingForClaim = mutableSetOf<UUID>()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!registrationTest.isAvailable()) {
            reject(player, "测试服务器尚未配置可用的 chamber。")
            return
        }
        waitingForClaim.add(player.uniqueId)
        isolateWhileWaiting(player)

        val payload = JsonObject().apply {
            addProperty("name", player.name)
        }
        try {
            Request.sendPostRequest(
                Config.API_ENDPOINT + MinecraftRegistrationTest.CLAIM_ENDPOINT,
                payload.toString(),
                Optional.of(mapOf("Token" to Config.API_SECRET)),
            ).whenComplete { body, error ->
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable {
                        handleClaimResponse(player.uniqueId, body, error)
                    },
                )
            }
        } catch (error: Throwable) {
            plugin.logger.warning(
                "Unable to claim Minecraft test session: ${error.message}",
            )
            reject(player, "测试服务器未能读取 API 配置。")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event.player.uniqueId in waitingForClaim) {
            event.to = event.from
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        waitingForClaim.remove(event.player.uniqueId)
        reveal(event.player)
    }

    private fun handleClaimResponse(
        playerId: UUID,
        body: String?,
        error: Throwable?,
    ) {
        val player = plugin.server.getPlayer(playerId)
        if (player == null || !player.isOnline || !waitingForClaim.remove(playerId)) {
            return
        }
        if (error != null) {
            reject(player, "无法连接账户验证服务，请稍后重试。")
            return
        }

        val response = try {
            JsonParser.parseString(body.orEmpty()).asJsonObject
        } catch (exception: RuntimeException) {
            reject(player, "账户验证服务返回了无效响应。")
            return
        }
        val sessionId = response.stringOr("sessionId", "")
        if (sessionId.isBlank() || response.stringOr("state", "") != "claimed") {
            reject(
                player,
                response.stringOr(
                    "message",
                    "没有待处理的 Minecraft 测试请求，请先从注册页面发起测试。",
                ),
            )
            return
        }

        reveal(player)
        player.isInvulnerable = false
        player.gameMode = GameMode.SURVIVAL
        val result = registrationTest.start(
            player,
            MinecraftRegistrationTest.Session(sessionId, player.name),
        )
        if (!result.accepted) reject(player, result.message)
    }

    private fun isolateWhileWaiting(player: Player) {
        player.isInvulnerable = true
        player.gameMode = GameMode.SPECTATOR
        plugin.server.onlinePlayers
            .filter { it.uniqueId != player.uniqueId }
            .forEach { other ->
                player.hidePlayer(plugin, other)
                other.hidePlayer(plugin, player)
            }
        player.sendMessage(
            Component.text("正在确认待处理的测试请求……", NamedTextColor.YELLOW),
        )
    }

    private fun reveal(player: Player) {
        plugin.server.onlinePlayers
            .filter { it.uniqueId != player.uniqueId }
            .forEach { other ->
                player.showPlayer(plugin, other)
                other.showPlayer(plugin, player)
            }
    }

    private fun reject(player: Player, reason: String) {
        waitingForClaim.remove(player.uniqueId)
        reveal(player)
        player.kick(Component.text(reason, NamedTextColor.RED))
    }

    private fun JsonObject.stringOr(key: String, fallback: String): String {
        val value = get(key) ?: return fallback
        if (value.isJsonNull || !value.isJsonPrimitive) return fallback
        return runCatching(value::getAsString).getOrDefault(fallback)
    }
}
