package vip.qoriginal.quantumplugin

import com.google.gson.JsonParser
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.TitlePart
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.scheduler.BukkitRunnable
import java.lang.Runnable
import java.util.concurrent.ConcurrentHashMap


class Login : Listener {
    companion object {
        val playerLoginMap = ConcurrentHashMap<Player, Int>()
        val visitorPlayedMap = ConcurrentHashMap<Player, Long>()
    }
    val logger = Logger();
    @OptIn(DelicateCoroutinesApi::class)
    fun performLogin(player: Player, password: String)  {
        GlobalScope.launch {
            val loginResult = withContext(Dispatchers.IO) {
                JsonParser.parseString(Request.sendGetRequest("http://localhost:8080/qo/game/login?username=${player.name}&password=$password").get()).asJsonObject
            }
            if (loginResult.get("result").asBoolean) {
                player.removeScoreboardTag("guest")
                player.sendTitlePart(TitlePart.TITLE, Component.text("登录成功").color(NamedTextColor.GREEN))
                val time = withContext(Dispatchers.IO) {
                    JsonParser.parseString(Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/getgametime?username=${player.name}").get()).asJsonObject
                }
                player.sendMessage(
                    Component.text("登录成功，您已经游玩 ${time.get("time").asLong}分钟").color(NamedTextColor.GREEN)
                )
                logger.log("${player.name} logged in.", "LoginAction")
                ChatSync().sendChatMsg("玩家${player.name}加入了服务器");
            } else {
                logger.log("${player.name} kicked due to wrong password.", "LoginAction")
                player.sendMessage(Component.text("登录失败，原因：密码不正确").color(NamedTextColor.RED))
                playerLoginMap[player] = (playerLoginMap[player] ?: 0) + 1
                if (playerLoginMap[player]!! >= 3) {
                    player.kick(Component.text("失败次数过多。"))
                }
            }
        }
    }
    @OptIn(DelicateCoroutinesApi::class)
    fun handleJoin(player: Player, visitor: Boolean){
        if (visitor){
            player.addScoreboardTag("guest")
        } else {
            player.addScoreboardTag("visitor")
            GlobalScope.launch {
                val timeObj = withContext(Dispatchers.IO) {
                    JsonParser.parseString(
                        Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/getgametime?username=${player.name}")
                            .get()
                    ).asJsonObject.takeIf { it.has("time") }?.asJsonObject
                }
                val time = if (timeObj == null) { 0 } else { timeObj.get("time").asLong }
                if (time > 180) player.kick(Component.text("体验时间已经结束，欢迎转正！"))
                visitorPlayedMap[player] = time
                object : BukkitRunnable() {
                    override fun run() {
                        visitorPlayedMap.forEach { (player, time) ->
                            visitorPlayedMap[player]?.let { if (it >= 180) {player.kick(Component.text("体验时间已经结束，欢迎转正！"))} }
                            visitorPlayedMap[player] = time + 1
                        }
                    }
                }.runTaskTimer(QuantumPlugin.getInstance(), 0L, 1200L)
            }
        }
        Bukkit.getScheduler().runTaskTimer(QuantumPlugin.getInstance(), Runnable {
            if (player.scoreboardTags.contains("guest")){
                    player.sendTitlePart(TitlePart.TITLE, Component.text("输入/login <密码> 来登录"))
            }
        }, 0, 20)
    }
    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player: Player = event.player
        if (player.getScoreboardTags().contains("guest")) {
            event.isCancelled = true
        }
    }

    fun isGuestOrVisitor(player: Player): Boolean {
        return player.scoreboardTags.contains("guest") || player.scoreboardTags.contains("visitor")
    }
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player: Player = event.player
        if (isGuestOrVisitor(player)) {
            event.isCancelled = true
        }
    }
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player: Player = event.player
        if (isGuestOrVisitor(player)) {
            event.isCancelled = true
        }
    }
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player: Player = event.player
        if (isGuestOrVisitor(player)) {
            event.isCancelled = true
        }
    }
    @EventHandler
    fun onPlayerChat(event: AsyncChatEvent) {
        val player: Player = event.player
        if (player.getScoreboardTags().contains("guest")) {
            event.isCancelled = true
        }
    }
}