package vip.qoriginal.quantumplugin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.security.SecureRandom
import java.util.Base64
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A hello reports capability; only QAPI's one-use proof can authenticate a player. */
object QModBridge : Listener, PluginMessageListener {
    const val HELLO_CHANNEL = "qmod:hello"
    const val REPLY_CHANNEL = "qmod:reply"
    private val gson = Gson()
    private val random = SecureRandom()
    private lateinit var plugin: JavaPlugin
    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val detected = ConcurrentHashMap<UUID, Detected>()
    private data class Detected(val player: Player, val version: String)
    private data class Pending(val player: Player, val nonce: String, val expires: Long,
        val exchange: QModLoginExchange = QModLoginExchange())
    private fun now() = System.nanoTime() / 1_000_000

    @JvmStatic
    fun register(owner: JavaPlugin) {
        plugin = owner
        Bukkit.getMessenger().registerOutgoingPluginChannel(owner, HELLO_CHANNEL)
        Bukkit.getMessenger().registerIncomingPluginChannel(owner, REPLY_CHANNEL, this)
        Bukkit.getPluginManager().registerEvents(this, owner)
        owner.getCommand("qmodstatus")?.apply {
            setExecutor { sender, _, _, args ->
                val player = args.firstOrNull()?.let { Bukkit.getPlayerExact(it) } ?: (sender as? Player).takeIf { args.isEmpty() }
                if (player == null) sender.sendMessage("用法：/qmodstatus <在线玩家>")
                else sender.sendMessage("${player.name}: " + (modVersion(player)?.let { "QMod $it 已响应" }
                    ?: if (pending[player.uniqueId]?.player === player) "等待 QMod 响应" else "未收到 QMod 响应（可能未安装或链路未通）"))
                true
            }
            setTabCompleter { _, _, _, args -> if (args.size == 1) Bukkit.getOnlinePlayers()
                .map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) } else emptyList() }
        }
        Bukkit.getOnlinePlayers().forEach { begin(it) }
    }

    @JvmStatic
    fun hasQMod(player: Player): Boolean = modVersion(player) != null

    @JvmStatic
    fun modVersion(player: Player): String? = detected[player.uniqueId]?.takeIf { it.player === player }?.version

    @JvmStatic
    fun begin(player: Player) {
        onMain {
            if (pending[player.uniqueId]?.player === player || detected[player.uniqueId]?.player === player) return@onMain
            detected.remove(player.uniqueId)
            val nonce = ByteArray(32).also { random.nextBytes(it) }
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val state = Pending(player, nonce, now() + 60_000)
            pending[player.uniqueId] = state
            // The first play tick is sufficient; retries cover late channel registration.
            for (delay in listOf(1L, 20L, 60L)) Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (current(state) && state.exchange.stage == QModLoginExchange.Stage.PROBE) probe(state)
            }, delay)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (pending[player.uniqueId] === state) finish(state, "password_required", "timeout")
            }, 1200L)
        }
    }

    private fun current(state: Pending) = plugin.isEnabled && state.player.isOnline &&
        pending[state.player.uniqueId] === state && now() < state.expires

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != REPLY_CHANNEL) return
        val json = QModWire.decode(message) ?: return
        val state = pending[player.uniqueId] ?: return
        if (state.player !== player || !current(state) || QModWire.string(json, "nonce") != state.nonce) return
        when (QModWire.string(json, "op")) {
            "hello" -> hello(state, json)
            "proof" -> proof(state, json)
            "decline" -> if (state.exchange.stage in setOf(QModLoginExchange.Stage.LOADING, QModLoginExchange.Stage.PROOF))
                finish(state, "password_required", "approval_failed")
        }
    }

    private fun hello(state: Pending, json: JsonObject) {
        val player = state.player
        detected[player.uniqueId] = Detected(player, QModWire.string(json, "modVersion")!!)
        if (!player.scoreboardTags.contains("guest")) {
            if (player.scoreboardTags.contains("visitor")) finish(state, "password_required", "unsupported_account")
            else finish(state, "authenticated")
            return
        }
        val reason = when {
            !Config.qmodPasswordlessLoginEnabled() -> "server_disabled"
            !json.get("autoLogin").asBoolean -> "client_disabled"
            else -> ""
        }
        if (reason.isNotEmpty()) {
            if (state.exchange.stage == QModLoginExchange.Stage.PROBE) status(state, "password_required", reason)
            else finish(state, "password_required", reason)
            return
        }
        if (!state.exchange.start()) return
        status(state, "authenticating")
        post("challenge", body(state)).whenComplete { response, error -> onMain {
            if (!current(state)) return@onMain
            if (!Config.qmodPasswordlessLoginEnabled() || !player.scoreboardTags.contains("guest")) {
                finish(state, "password_required", "verification_failed")
                return@onMain
            }
            val result = if (error == null && response?.status == 200) parse(response.body) else null
            val challenge = result?.let { QModWire.string(it, "challenge") }
            val nodeId = result?.get("nodeId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.toString()?.toIntOrNull()
            if (result?.get("ok")?.toString() != "true" || challenge == null || nodeId == null ||
                !state.exchange.challenge(challenge, nodeId)) {
                finish(state, "password_required", "verification_failed")
                return@onMain
            }
            send(player, body(state) + mapOf("op" to "challenge"))
        } }
    }

    private fun proof(state: Pending, json: JsonObject) {
        if (!Config.qmodPasswordlessLoginEnabled() || !state.player.scoreboardTags.contains("guest")) return
        if (!state.exchange.verify(QModWire.string(json, "challenge")!!)) return
        post("redeem", body(state) + mapOf("ticket" to QModWire.string(json, "ticket")!!)).whenComplete { response, error -> onMain {
            if (!current(state)) return@onMain
            val result = if (error == null && response?.status == 200) parse(response.body) else null
            if (Config.qmodPasswordlessLoginEnabled() && result?.get("ok")?.toString() == "true" &&
                QModWire.string(result, "username").equals(state.player.name, true) && Login().completeQModLogin(state.player)) {
                // completeGuestLogin publishes the result for all login methods and invalidates this attempt.
                return@onMain
            }
            finish(state, "password_required", "verification_failed")
        } }
    }

    /** Also called for password and IP login, so an in-flight proof cannot win twice. */
    @JvmStatic
    fun loginCompleted(player: Player) {
        onMain { pending[player.uniqueId]?.takeIf { it.player === player && hasQMod(player) }
            ?.let { finish(it, "authenticated") } }
    }

    private fun finish(state: Pending, result: String, reason: String = "") {
        if (pending[state.player.uniqueId] !== state) return
        if (state.player.isOnline && hasQMod(state.player)) status(state, result, reason)
        state.exchange.finish()
        pending.remove(state.player.uniqueId, state)
    }

    private fun status(state: Pending, result: String, reason: String = "") = send(state.player,
        mapOf("op" to "status", "nonce" to state.nonce, "status" to result, "reason" to reason))

    private fun probe(state: Pending) = send(state.player, mapOf("op" to "probe", "nonce" to state.nonce))

    private fun body(state: Pending): Map<String, Any> = mapOf("username" to state.player.name,
        "playerUuid" to state.player.uniqueId.toString(), "nonce" to state.nonce,
        "challenge" to state.exchange.challenge, "nodeId" to state.exchange.nodeId)

    private fun post(route: String, body: Map<String, Any>) = Request.sendPostRequestWithStatus(
        Config.API_ENDPOINT + "/qo/auth/game/$route", gson.toJson(body), Optional.of(mapOf("Token" to Config.API_SECRET)))

    private fun parse(text: String) = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

    private fun send(player: Player, body: Map<String, Any>) {
        val bytes = gson.toJson(body + mapOf("version" to 1)).toByteArray(Charsets.UTF_8)
        if (bytes.size <= QModWire.MAX_BYTES) player.sendPluginMessage(plugin, HELLO_CHANNEL, bytes)
    }

    private fun onMain(action: () -> Unit) {
        if (!::plugin.isInitialized || !plugin.isEnabled) return
        if (Bukkit.isPrimaryThread()) action() else Bukkit.getScheduler().runTask(plugin, Runnable { action() })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun join(event: PlayerJoinEvent) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (event.player.isOnline) begin(event.player) }, 1L)
    }

    @EventHandler
    fun channelRegistered(event: PlayerRegisterChannelEvent) {
        if (event.channel == REPLY_CHANNEL) pending[event.player.uniqueId]?.let {
            if (it.player === event.player && current(it) && it.exchange.stage == QModLoginExchange.Stage.PROBE) probe(it)
        }
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        pending.computeIfPresent(event.player.uniqueId) { _, state -> state.takeUnless { it.player === event.player } }
        detected.computeIfPresent(event.player.uniqueId) { _, state -> state.takeUnless { it.player === event.player } }
    }

    @EventHandler
    fun disable(event: PluginDisableEvent) {
        if (event.plugin === plugin) { pending.clear(); detected.clear() }
    }
}
