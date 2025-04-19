package vip.qoriginal.quantumplugin.event

import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

class Locker : Listener {
	var lockStat = false;
	val defaultMsg = "[Locker]使用该功能来提醒服务器不要关闭。<status/lock/unlock>"
	fun onCommand(from: CommandSender, args: Array<String>) {
		if (args.isEmpty()) from.sendMessage(defaultMsg)
		if (args.size > 1) {
			from.sendMessage(defaultMsg)
		}
		when (args[0]) {
			"unlock" -> {
				lockStat = false
				from.sendMessage("[Locker]当前未阻止服务器关闭。")
			}
			"lock" -> {
				lockStat = true
				from.sendMessage("[Locker]当前正在阻止服务器关闭。")
			}
			"status" -> {
				if (lockStat) {
					from.sendMessage("[Locker]当前已阻止服务器关闭。")
				} else {
					from.sendMessage("[Locker]当前未阻止服务器关闭。")
				}
			}
		}
	}
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