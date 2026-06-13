package vip.qoriginal.quantumplugin

import org.bukkit.GameMode
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class CustomGamemodeCmd : CommandExecutor {
	override fun onCommand(p0: CommandSender, p1: Command, p2: String, p3: Array<out String>): Boolean {
		if (p0 !is Player) {
			p0.sendMessage("This command can only be executed by the player!")
			return true
		}
		val sender = p0 as Player
		if (p3.isEmpty()) {
			p0.sendMessage("用法: /gm <s/sc>")
			return true
		}

		val gameMode = when (p3[0].lowercase()) {
			"s" -> GameMode.SURVIVAL
			"sc"-> GameMode.SPECTATOR
			else -> {
				p0.sendMessage("只有s(生存)/sc(旁观)被允许。")
				return true
			}
		}
		if (sender.gameMode != gameMode) sender.gameMode = gameMode

		sender.sendMessage("你的游戏模式已切换为 $gameMode")

		return true
	}
}