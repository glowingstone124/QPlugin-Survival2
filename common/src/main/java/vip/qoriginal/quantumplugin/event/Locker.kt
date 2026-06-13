package vip.qoriginal.quantumplugin.event

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

class Locker : Listener {
	var lockStat = false

	@EventHandler
	fun handleCommandPreProcessEvent(event: PlayerCommandPreprocessEvent){
		if (event.isCancelled) return
		if (!(event.message.startsWith("/stop") || event.message.startsWith("/reload"))) return
		if (lockStat) {
			event.player.sendMessage("[Locker]当前操作已经被阻止，原因：玩家阻止了服务器关闭。")
			event.isCancelled = true
		}
	}
}
