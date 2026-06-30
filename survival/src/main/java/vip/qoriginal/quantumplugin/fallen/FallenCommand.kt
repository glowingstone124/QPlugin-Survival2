package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.CommandMessages

class FallenCommand(private val service: FallenGameService) : CommandExecutor {
	override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
		if (args.isEmpty()) {
			help(sender, label)
			return true
		}
		try {
			when (args[0].lowercase()) {
				"help" -> help(sender, label)
				"status" -> status(sender)
				"time" -> time(sender)
				"start" -> start(sender)
				"end" -> end(sender)
				"phase" -> phase(sender, args)
				"team" -> team(sender, args)
				"region" -> region(sender, args)
				"key" -> key(sender, args)
				"score" -> score(sender, args)
				"buy" -> buy(sender, args)
				"beacon" -> beacon(sender)
				"admin" -> admin(sender, args)
				else -> {
					CommandMessages.warning(sender, "未知操作: ${args[0]}")
					help(sender, label)
				}
			}
		} catch (exception: IllegalArgumentException) {
			CommandMessages.error(sender, exception.message ?: "命令参数错误。")
		}
		return true
	}

	private fun help(sender: CommandSender, label: String) {
		val root = "/$label"
		sender.sendMessage(
			CommandMessages.title("陷落活动")
				.appendNewline()
				.append(line("$root status", "查看活动状态"))
				.appendNewline()
				.append(line("$root start", "开始活动并进入 2 小时部署阶段"))
				.appendNewline()
				.append(line("$root phase <idle|deployment|active|overtime|ended>", "切换阶段"))
				.appendNewline()
				.append(line("$root team set <player> <A|B|C>", "分配阵营"))
				.appendNewline()
				.append(line("$root region list", "查看写死的阵营矩形区域"))
				.appendNewline()
				.append(line("$root key give <A|B|C> [player]", "发放物品密钥"))
				.appendNewline()
				.append(line("$root key list", "查看密钥"))
				.appendNewline()
				.append(line("$root buy <compass|scan|jammer|tracking|supply|advanced|resistance|speed|nightvision|blast|respawn|keyalert|beacon> ...", "购买积分物品"))
				.appendNewline()
				.append(line("$root beacon", "使用阵营临时传送信标"))
				.appendNewline()
				.append(line("$root score [add|set] <A|B|C> <amount>", "查看或调整积分"))
				.appendNewline()
				.append(line("$root admin <eliminate|voidkey> ...", "管理员裁定工具"))
		)
	}

	private fun status(sender: CommandSender) {
		val scoreText = service.scoreSnapshot().entries.joinToString("  ") { "${it.key.name}: ${it.value}" }
		val eliminated = service.eliminatedSnapshot().joinToString("、") { it.displayName }.ifBlank { "无" }
		sender.sendMessage(
			CommandMessages.title("陷落状态")
				.appendNewline()
				.append(Component.text("阶段: ${service.phase.name}", NamedTextColor.WHITE))
				.appendNewline()
				.append(Component.text("积分: $scoreText", NamedTextColor.WHITE))
				.appendNewline()
				.append(Component.text("已出局: $eliminated", NamedTextColor.WHITE))
				.appendNewline()
				.append(Component.text("剩余时间: ${formatDuration(service.remainingMillis())}", NamedTextColor.WHITE))
				.appendNewline()
				.append(Component.text("密钥数: ${service.keySnapshot().size}", NamedTextColor.WHITE))
		)
	}

	private fun time(sender: CommandSender) {
		CommandMessages.info(sender, "已进行 ${formatDuration(service.elapsedMillis())}，剩余 ${formatDuration(service.remainingMillis())}。")
	}

	private fun start(sender: CommandSender) {
		requireAdmin(sender)
		service.startGame()
		CommandMessages.success(sender, "活动已开始。")
	}

	private fun end(sender: CommandSender) {
		requireAdmin(sender)
		service.endGame("管理员手动结束活动")
		CommandMessages.success(sender, "活动已结束。")
	}

	private fun phase(sender: CommandSender, args: Array<out String>) {
		requireAdmin(sender)
		require(args.size == 2) { "用法: /fallen phase <idle|deployment|active|overtime|ended>" }
		service.setPhase(FallenPhase.parse(args[1]))
		CommandMessages.success(sender, "阶段已切换为 ${service.phase.name}。")
	}

	private fun team(sender: CommandSender, args: Array<out String>) {
		require(args.size >= 2) { "用法: /fallen team <set|clear|get> ..." }
		when (args[1].lowercase()) {
			"set" -> {
				requireAdmin(sender)
				require(args.size == 4) { "用法: /fallen team set <player> <A|B|C>" }
				val target = Bukkit.getOfflinePlayer(args[2])
				val team = FallenTeam.parse(args[3])
				service.assignTeam(target.uniqueId, team)
				CommandMessages.success(sender, "已将 ${target.name ?: args[2]} 分配到 ${team.displayName}。")
			}
			"clear" -> {
				requireAdmin(sender)
				require(args.size == 3) { "用法: /fallen team clear <player>" }
				val target = Bukkit.getOfflinePlayer(args[2])
				service.clearTeam(target.uniqueId)
				CommandMessages.success(sender, "已清除 ${target.name ?: args[2]} 的阵营。")
			}
			"get" -> {
				val player = if (args.size >= 3) Bukkit.getPlayerExact(args[2]) else sender as? Player
				require(player != null) { "玩家不在线，或控制台需要指定在线玩家。" }
				val team = service.teamOf(player)
				CommandMessages.info(sender, "${player.name} 的阵营: ${team?.displayName ?: "未分配"}")
			}
			else -> throw IllegalArgumentException("用法: /fallen team <set|clear|get> ...")
		}
	}

	private fun region(sender: CommandSender, args: Array<out String>) {
		require(args.size >= 2) { "用法: /fallen region list" }
		when (args[1].lowercase()) {
			"list" -> {
				var component = CommandMessages.title("阵营区域")
				for (team in FallenTeam.entries) {
					val text = service.regionsOf(team).mapIndexed { index, region -> "#$index $region" }.joinToString(" | ").ifBlank { "未配置" }
					component = component.appendNewline().append(
						Component.text("${team.name}: $text", NamedTextColor.WHITE)
					)
				}
				sender.sendMessage(component)
			}
			else -> throw IllegalArgumentException("区域已改为代码内写死，只能使用 /fallen region list")
		}
	}

	private fun key(sender: CommandSender, args: Array<out String>) {
		require(args.size >= 2) { "用法: /fallen key <give|list>" }
		when (args[1].lowercase()) {
			"give" -> {
				requireAdmin(sender)
				require(args.size in 3..4) { "用法: /fallen key give <A|B|C> [player]" }
				val team = FallenTeam.parse(args[2])
				val target = if (args.size == 4) Bukkit.getPlayerExact(args[3]) else sender as? Player
				require(target != null) { "需要指定在线玩家。"}
				target.inventory.addItem(service.createKeyItem(team))
				CommandMessages.success(sender, "已向 ${target.name} 发放 ${team.displayName} 密钥。")
			}
			"list" -> {
				val keys = service.keySnapshot()
				if (keys.isEmpty()) {
					CommandMessages.info(sender, "当前没有密钥。")
					return
				}
				var component = CommandMessages.title("密钥列表")
				for (key in keys.take(20)) {
					component = component.appendNewline().append(
						Component.text("${key.shortId()} ${key.ownerTeam.name}/${key.originalTeam.name} ${key.state.name}", NamedTextColor.WHITE)
					)
				}
				if (keys.size > 20) {
					component = component.appendNewline().append(Component.text("还有 ${keys.size - 20} 个密钥未显示。", NamedTextColor.GRAY))
				}
				sender.sendMessage(component)
			}
			else -> throw IllegalArgumentException("用法: /fallen key <give|list>")
		}
	}

	private fun score(sender: CommandSender, args: Array<out String>) {
		if (args.size == 1) {
			status(sender)
			return
		}
		requireAdmin(sender)
		require(args.size == 4) { "用法: /fallen score <add|set> <A|B|C> <amount>" }
		val team = FallenTeam.parse(args[2])
		val amount = args[3].toIntOrNull() ?: throw IllegalArgumentException("积分必须是整数。")
		when (args[1].lowercase()) {
			"add" -> service.addScore(team, amount)
			"set" -> service.setScore(team, amount)
			else -> throw IllegalArgumentException("用法: /fallen score <add|set> <A|B|C> <amount>")
		}
		service.save()
		CommandMessages.success(sender, "积分已更新。")
	}

	private fun buy(sender: CommandSender, args: Array<out String>) {
		val player = sender as? Player ?: throw IllegalArgumentException("只有玩家可以购买物品。")
		require(args.size >= 2) { "用法: /fallen buy <compass|scan|jammer|tracking|supply|advanced|resistance|speed|nightvision|blast|respawn|keyalert|beacon> ..." }
		when (args[1].lowercase()) {
			"compass" -> {
				require(args.size == 3) { "用法: /fallen buy compass <A|B|C>" }
				service.buyCompass(player, FallenTeam.parse(args[2]))
			}
			"scan" -> service.buyShortScan(player)
			"jammer", "tracking", "supply", "advanced", "resistance", "speed", "nightvision", "blast", "respawn", "keyalert", "beacon" -> service.buyShopItem(player, args[1])
			else -> throw IllegalArgumentException("未知购买项: ${args[1]}")
		}
	}

	private fun beacon(sender: CommandSender) {
		val player = sender as? Player ?: throw IllegalArgumentException("只有玩家可以使用传送信标。")
		service.teleportToBeacon(player)
	}

	private fun admin(sender: CommandSender, args: Array<out String>) {
		requireAdmin(sender)
		require(args.size >= 2) { "用法: /fallen admin <eliminate|voidkey> ..." }
		when (args[1].lowercase()) {
			"eliminate" -> {
				require(args.size >= 3) { "用法: /fallen admin eliminate <A|B|C> [reason]" }
				val team = FallenTeam.parse(args[2])
				val reason = args.drop(3).joinToString(" ").ifBlank { "管理员裁定出局" }
				if (service.forceEliminate(team, reason)) {
					CommandMessages.success(sender, "${team.displayName} 已出局。")
				} else {
					CommandMessages.warning(sender, "${team.displayName} 已经出局。")
				}
			}
			"voidkey" -> {
				require(args.size >= 3) { "用法: /fallen admin voidkey <keyPrefix> [reason]" }
				val reason = args.drop(3).joinToString(" ").ifBlank { "管理员裁定作废" }
				val key = service.voidKey(args[2], reason)
				CommandMessages.success(sender, "密钥 ${key.shortId()} 已作废。")
			}
			else -> throw IllegalArgumentException("用法: /fallen admin <eliminate|voidkey> ...")
		}
	}

	private fun line(command: String, description: String): Component {
		return CommandMessages.command(command)
			.decorate(TextDecoration.UNDERLINED)
			.clickEvent(ClickEvent.suggestCommand(command))
			.append(Component.space())
			.append(CommandMessages.muted("- $description"))
	}

	private fun requireAdmin(sender: CommandSender) {
		require(sender.isOp || sender.hasPermission("quantumplugin.fallen.admin")) { "你没有陷落活动管理权限。" }
	}

	private fun formatDuration(millis: Long): String {
		if (millis <= 0L) return "0m"
		val totalMinutes = millis / 60_000L
		val hours = totalMinutes / 60L
		val minutes = totalMinutes % 60L
		return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
	}
}
