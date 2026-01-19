package vip.qoriginal.quantumplugin.flightUtil

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player


class FlightCommandExecutor : CommandExecutor {

	private val flight: Flight = Flight()

	override fun onCommand(
		sender: CommandSender,
		command: Command,
		label: String,
		args: Array<out String>
	): Boolean {
		if (sender !is Player) {
			sender.sendMessage("只有玩家可以执行此命令")
			return true
		}
		if (args.isEmpty()) {
			sendHelp(sender)
			return true
		}

		val player: Player = sender

		when (args[0].lowercase()) {
			"report" -> {
				if (args.size < 2) {
					player.sendMessage(Component.text("用法: /flight report [on/off]").color(NamedTextColor.YELLOW))
					return true
				}
				when (args[1].lowercase()) {
					"on" -> flight.setPlayerFlightReport(player, true)
					"off" -> flight.setPlayerFlightReport(player, false)
					else -> player.sendMessage(Component.text("请指定 on 或 off").color(NamedTextColor.YELLOW))
				}
			}

			"gui" -> {
				if (args.size < 2) {
					player.sendMessage(Component.text("用法: /flight gui [on/off]").color(NamedTextColor.YELLOW))
					return true
				}
				when (args[1].lowercase()) {
					"on" -> flight.setPlayerFlightGui(player, true)
					"off" -> flight.setPlayerFlightGui(player, false)
					else -> player.sendMessage(Component.text("请指定 on 或 off").color(NamedTextColor.YELLOW))
				}
			}

			"destination", "dest" -> {
				if (args.size < 2) {
					player.sendMessage(Component.text("用法: /flight dest <set/unset/list>").color(NamedTextColor.YELLOW))
					return true
				}
				handleDestination(player, args)
			}

			else -> sendHelp(player)
		}
		return true
	}

	private fun handleDestination(player: Player, args: Array<out String>) {
		when (args[1].lowercase()) {
			"unset" -> {
				flight.setPlayerFlightDestination(player, "NONE")
				player.sendMessage(Component.text("已清除目的地").color(NamedTextColor.GREEN))
			}

			"set" -> {
				if (args.size < 3) {
					player.sendMessage(Component.text("请指定目的地 ID。").color(NamedTextColor.YELLOW))
					return
				}
				if (!flight.setPlayerFlightDestination(player, args[2])) {
					player.sendMessage(
						Component.text("你输入的ID无效。查看列表: /flight dest list")
							.color(NamedTextColor.YELLOW)
					)
				} else {
					player.sendMessage(Component.text("目的地已设置为: ${args[2]}").color(NamedTextColor.GREEN))
				}
			}

			"list" -> {
				var component = Component.text("=== 现有目的地列表 ===").color(NamedTextColor.AQUA)
				FlightDestination.entries
					.filter { it.id != "NONE" }
					.forEach {
						component = component
							.appendNewline()
							.append(flight.formatDestinationAsHumanReadable(it))
					}
				player.sendMessage(component)
			}

			else -> player.sendMessage(Component.text("无效操作。输入 /flight help 查看指南").color(NamedTextColor.YELLOW))
		}
	}

	fun sendHelp(sender: CommandSender) {
		sender.sendMessage(
			Component.text("飞行仪表配置").color(TextColor.color(52, 168, 83))
				.appendNewline()
				.append(Component.text("/flight report [on/off] ", NamedTextColor.WHITE))
				.append(Component.text("- 公开飞行信息", NamedTextColor.GRAY)).appendNewline()
				.append(Component.text("/flight gui [on/off] ", NamedTextColor.WHITE))
				.append(Component.text("- 显示/隐藏仪表盘", NamedTextColor.GRAY)).appendNewline()
				.append(Component.text("/flight dest <set/unset/list> ", NamedTextColor.WHITE))
				.append(Component.text("- 管理目的地", NamedTextColor.GRAY))
		)
	}
}