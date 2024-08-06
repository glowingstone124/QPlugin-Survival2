package vip.qoriginal.quantumplugin

import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerChatEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent


class Login : Listener {
    companion object {
        val playerLoginMap = hashMapOf<Player, Int>()
    }
    fun performLogin(player: Player, password:String) {
        val result = JsonParser.parseString(Request.sendGetRequest("http://localhost:8080/qo/game/login?username=${player.name}&password=$password")).asJsonObject
        if (result.get("result").asBoolean) {
            player.removeScoreboardTag("guest")
            player.sendMessage(Component.text("登录成功").color(NamedTextColor.GREEN))
        } else {
            player.sendMessage(Component.text("登录失败，原因：密码不正确").color(NamedTextColor.GREEN))
            if (playerLoginMap.containsKey(player) && playerLoginMap[player]!! <= 3) {
                playerLoginMap[player] = playerLoginMap[player]!! + 1
            } else if(playerLoginMap.containsKey(player) && playerLoginMap[player]!! >= 3) {
                player.kick(Component.text("失败次数过多。"))
            } else {
                playerLoginMap[player] = 1
            }
        }
    }
    fun handleJoin(player: Player){
        player.addScoreboardTag("guest")
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
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val player: Player = event.player
        if (player.getScoreboardTags().contains("guest")) {
            event.isCancelled = true
        }
    }
}