package vip.qoriginal.quantumplugin

import com.google.gson.JsonParser
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.TitlePart
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.scheduler.BukkitRunnable
import vip.qoriginal.quantumplugin.patch.Utils
import java.lang.Runnable
import java.util.concurrent.ConcurrentHashMap


class Login : Listener {

	private val eventHandlers: Map<Class<out Event>, (Player, Cancellable) -> Unit> = mapOf(
		PlayerMoveEvent::class.java to { player, event -> handleGuestOnlyEvent(player, event) },
		BlockBreakEvent::class.java to { player, event -> handleGuestOrVisitorEvent(player, event) },
		BlockPlaceEvent::class.java to { player, event -> handleGuestOrVisitorEvent(player, event) },
		PlayerInteractEvent::class.java to { player, event -> handleGuestOrVisitorEvent(player, event) },
		AsyncChatEvent::class.java to { player, event -> handleGuestOnlyEvent(player, event) },
		PlayerDropItemEvent::class.java to { player, event -> handleGuestOnlyEvent(player, event) }
	)

	companion object {
		val playerLoginMap = ConcurrentHashMap<Player, Int>()
		val visitorPlayedMap = ConcurrentHashMap<Player, Long>()
	}

	val logger = Logger();
	val leaveMessageComponent = LeaveMessageComponent()

	@OptIn(DelicateCoroutinesApi::class)
	fun performLogin(player: Player, password: String) {
		GlobalScope.launch {
			val loginResult = withContext(Dispatchers.IO) {
				JsonParser.parseString(
					Request.sendGetRequest("http://172.19.0.6:8080/qo/game/login?username=${player.name}&password=$password")
						.get()
				).asJsonObject
			}
			if (loginResult.get("result").asBoolean) {
				player.removeScoreboardTag("guest")
				player.sendTitlePart(TitlePart.TITLE, Component.text("登录成功").color(NamedTextColor.GREEN))
				val time = withContext(Dispatchers.IO) {
					JsonParser.parseString(
						Request.sendGetRequest("http://172.19.0.6:8080/qo/download/getgametime?username=${player.name}")
							.get()
					).asJsonObject
				}
				player.sendMessage(
					Component.text("登录成功，您已经游玩 ${time.get("time").asLong}分钟").color(NamedTextColor.GREEN)
				)
				player.removeScoreboardTag("guest")
				logger.log("${player.name} logged in.", "LoginAction")
				ChatSync().sendChatMsg("玩家${player.name}加入了服务器");
				leaveMessageComponent.getMessages(player).forEach {
					player.sendMessage(it)
				}
			} else {
				logger.log("${player.name} kicked due to wrong password.", "LoginAction")
				player.sendMessage(Component.text("登录失败，原因：密码不正确").color(NamedTextColor.RED))
				playerLoginMap[player] = (playerLoginMap[player] ?: 0) + 1
				if (playerLoginMap[player]!! >= 3) {
					performKick(player, Component.text("失败次数过多。"))
				}
			}
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	fun handleJoin(player: Player, visitor: Boolean) {
		if (!visitor) {
			player.addScoreboardTag("guest")
		} else {
			player.addScoreboardTag("visitor")
			GlobalScope.launch {
				val timeObj = withContext(Dispatchers.IO) {
					JsonParser.parseString(
						Request.sendGetRequest("http://172.19.0.6:8080/qo/download/getgametime?username=${player.name}")
							.get()
					).asJsonObject.takeIf { it.has("time") }?.asJsonObject
				}
				val time = if (timeObj == null) {
					0
				} else {
					timeObj.get("time").asLong
				}
				if (time > 180) performKick(player, Component.text("体验时间已经结束，欢迎转正！"))
				visitorPlayedMap[player] = time
				object : BukkitRunnable() {
					override fun run() {
						visitorPlayedMap.forEach { (player, time) ->
							visitorPlayedMap[player]?.let {
								if (it >= 180) {
									performKick(player, Component.text("体验时间已经结束，欢迎转正！"))
								}
							}
							visitorPlayedMap[player] = time + 1
						}
					}
				}.runTaskTimer(QuantumPlugin.getInstance(), 0L, 1200L)
			}
		}
		Bukkit.getScheduler().runTaskTimer(QuantumPlugin.getInstance(), Runnable {
			if (player.scoreboardTags.contains("guest")) {
				player.sendTitlePart(TitlePart.TITLE, Component.text("输入/login <密码> 来登录"))
			}
		}, 0, 20)
	}
	@EventHandler
	fun onPlayerMove(event: PlayerMoveEvent) = handleEvent(event)

	@EventHandler
	fun onBlockBreak(event: BlockBreakEvent) = handleEvent(event)

	@EventHandler
	fun onBlockPlace(event: BlockPlaceEvent) = handleEvent(event)

	@EventHandler
	fun onPlayerInteract(event: PlayerInteractEvent) = handleEvent(event)

	@EventHandler
	fun onPlayerChat(event: AsyncChatEvent) = handleEvent(event)

	@EventHandler
	fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
		val player = event.player
		if (player.scoreboardTags.contains("guest")) {
			val message = event.message.lowercase()
			if (!message.startsWith("/login")) {
				event.isCancelled = true
				player.sendMessage("§c你只能使用 /login 命令！")
			}
		}
	}

	@EventHandler
	fun onPlayerDropItem(event: PlayerDropItemEvent) = handleEvent(event)

	private fun handleEvent(event: Event) {
		val player = when (event) {
			is PlayerEvent -> event.player
			else -> return
		}
		val cancellableEvent = event as? Cancellable ?: return
		eventHandlers[event::class.java]?.invoke(player, cancellableEvent)
	}

	private fun handleGuestOnlyEvent(player: Player, event: Cancellable) {
		if (player.scoreboardTags.contains("guest")) {
			event.isCancelled = true
		}
	}

	private fun handleGuestOrVisitorEvent(player: Player, event: Cancellable) {
		if (player.scoreboardTags.contains("guest") || player.scoreboardTags.contains("visitor")) {
			event.isCancelled = true
		}
	}
	fun performKick(player: Player, reason: Component) {
		Utils.runTaskOnMainThread {
			player.kick(reason)
		}
	}
}