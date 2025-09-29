package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class RankingCmd: CommandExecutor {
	override fun onCommand(
		sender: CommandSender,
		command: Command,
		label: String,
		args: Array<out String>
	): Boolean {
		val result = CombatPoint.playerStats
			.entries
			.sortedByDescending { it.value.points }
			.take(10)
			.map { it.toPair() }

		if (result.isEmpty()) {
			sender.sendMessage(
				Component.text("暂无排行榜数据")
					.color(NamedTextColor.RED)
			)
			return true
		}

		val header = Component.text("=== 积分排行榜 ===").color(NamedTextColor.AQUA)
		sender.sendMessage(header)

		result.forEachIndexed { index, (uuid, stats) ->
			val name = Bukkit.getOfflinePlayer(uuid).name ?: "未知玩家"
			val line = Component.text("第${index + 1}名: $name 积分: ${stats.points}")
				.color(NamedTextColor.GOLD)
			sender.sendMessage(line)
		}

		return true
	}
}