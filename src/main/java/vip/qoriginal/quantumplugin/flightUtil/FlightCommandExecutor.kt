package vip.qoriginal.quantumplugin.flightUtil

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player


class FlightCommandExecutor : CommandExecutor {
	val flight: Flight = Flight()
	override fun onCommand(
		p0: CommandSender,
		p1: Command,
		p2: String,
		p3: Array<out String>
	): Boolean {
		if (p0 !is Player) {
			return true
		}
		val player: Player = p0
		if (p3.size < 3) {
			sendHelp(p0)
			return true
		}
		when (p3[0]) {
			"report" -> {
				if (p3[1] == "on") {
					flight.setPlayerFlightReport(player, true)
				} else if (p3[1] == "off") {
					flight.setPlayerFlightReport(player, false)
				} else {
					player.sendMessage(Component.text("请指定on/off。").color(NamedTextColor.YELLOW))
				}
				return true
			}

			"gui" -> {
				if (p3[1] == "on") {
					flight.setPlayerFlightGui(player, true)
				} else if (p3[1] == "off") {
					flight.setPlayerFlightGui(player, false)
				} else {
					player.sendMessage(Component.text("请指定on/off。").color(NamedTextColor.YELLOW))
				}
				return true
			}

			"destination" -> {
				when (p3[1]) {
					"unset" -> {
						flight.setPlayerFlightDestination(player, "NONE")
					}

					"set" -> {
						if (!flight.setPlayerFlightDestination(player, p3[2])) {
							player.sendMessage(
								Component.text("你输入的ID无效。如果你不确定具体的名称，请输入/flight dest list查看。")
									.color(NamedTextColor.YELLOW)
							)
							return true
						}
					}
					"list" -> {
						var component = Component.text("===现有目的地列表：===")

						FlightDestination.entries
							.filter { it.id != "NONE" }
							.forEach {
								component = component
									.appendNewline()
									.append(flight.formatDestinationAsHumanReadable(it))
							}

						player.sendMessage(component)

						return true
					}
					else -> {
						player.sendMessage(
							Component.text("你的操作无效。输入/flight help来查看使用指南。")
							.color(NamedTextColor.YELLOW))
						return true
					}
				}
			}

			else -> {
				sendHelp(p0)
				return true
			}
		}
		return true
	}

	fun sendHelp(p0: CommandSender) {
		p0.sendMessage(
			Component.text("飞行仪表配置").decoration(TextDecoration.BOLD, true).appendNewline()
				.append(
					Component.text(
						"""
						/flight report [on/off] 选择是否向QOAPP公开你的飞行信息
						/flight gui [on/of] 选择是否启用GUI
						/flight dest:
							set <ID> 设置目的地ID
							unset 清除当前目的地
							list 显示目前已存在的可用目的地
					""".trimMargin()
					)
				)
		)
	}


}