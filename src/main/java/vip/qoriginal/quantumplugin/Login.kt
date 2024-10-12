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
import java.lang.Runnable


class Login : Listener {
    companion object {
        val playerLoginMap = hashMapOf<Player, Int>()
    }
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
                ChatSync().sendChatMsg("玩家${player.name}加入了服务器");

            } else {
                player.sendMessage(Component.text("登录失败，原因：密码不正确").color(NamedTextColor.RED))
                playerLoginMap[player] = (playerLoginMap[player] ?: 0) + 1
                if (playerLoginMap[player]!! >= 3) {
                    player.kick(Component.text("失败次数过多。"))
                }
            }
        }
    }

    fun handleJoin(player: Player){
        player.addScoreboardTag("guest")
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

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player: Player = event.player
        if (player.getScoreboardTags().contains("guest")) {
            event.isCancelled = true
        }
    }
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player: Player = event.player
        if (player.getScoreboardTags().contains("guest")) {
            event.isCancelled = true
        }
    }
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player: Player = event.player
        if (player.getScoreboardTags().contains("guest")) {
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