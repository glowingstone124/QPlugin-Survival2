package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.CommandMessages

class FallenShopCommand(private val service: FallenGameService) : CommandExecutor {
	override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
		val player = sender as? Player ?: run {
			CommandMessages.playerOnly(sender)
			return true
		}
		if (args.isEmpty() || args[0].equals("list", ignoreCase = true)) {
			list(player)
			return true
		}
		val id = args[0].toIntOrNull()
		if (id == null) {
			CommandMessages.warning(player, "用法: /shop <id> [amount]")
			return true
		}
		val amount = args.getOrNull(1)?.toIntOrNull() ?: 1
		if (amount !in 1..16) {
			CommandMessages.warning(player, "购买数量必须在 1 到 16 之间。")
			return true
		}
		val team = service.teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (service.isEliminated(team)) {
			CommandMessages.error(player, "你的阵营已经出局，不能购买商城物品。")
			return true
		}
		val item = visibleItems(team).firstOrNull { it.id == id }
		if (item == null) {
			CommandMessages.warning(player, "不可购买或未知商城 ID: $id。输入 /shop 查看当前可买列表。")
			return true
		}
		repeat(amount) { index ->
			if (!item.purchase(service, player)) {
				if (index > 0) {
					CommandMessages.warning(player, "已停止后续购买，成功购买 $index 次。")
				}
				return true
			}
		}
		return true
	}

	private fun list(player: Player) {
		val team = service.teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return
		}
		if (service.isEliminated(team)) {
			CommandMessages.error(player, "你的阵营已经出局，不能购买商城物品。")
			return
		}
		var component = CommandMessages.title("陷落积分商城")
			.appendNewline()
			.append(Component.text("用法: /shop <id> [amount]", NamedTextColor.GRAY))
		for (item in visibleItems(team)) {
			component = component.appendNewline().append(
				Component.text("${item.id}. ${item.displayName} - ${item.cost} 分", NamedTextColor.WHITE)
			)
		}
		player.sendMessage(component)
	}

	private fun visibleItems(team: FallenTeam): List<ShopItem> {
		return ShopItem.entries.filter { item -> item.targetTeam != team }
	}

	private enum class ShopItem(
		val id: Int,
		val displayName: String,
		val cost: Int,
		val targetTeam: FallenTeam? = null,
		val purchase: (FallenGameService, Player) -> Boolean
	) {
		COMPASS_A(1, "密钥指南针 -> A 阵营", 600, FallenTeam.A, { service, player -> service.buyCompass(player, FallenTeam.A) }),
		COMPASS_B(2, "密钥指南针 -> B 阵营", 600, FallenTeam.B, { service, player -> service.buyCompass(player, FallenTeam.B) }),
		COMPASS_C(3, "密钥指南针 -> C 阵营", 600, FallenTeam.C, { service, player -> service.buyCompass(player, FallenTeam.C) }),
		SHORT_SCAN(4, "短距扫描", 300, purchase = { service, player -> service.buyShortScan(player) }),
		JAMMER(5, "区域干扰器", 500, purchase = { service, player -> service.buyShopItem(player, "jammer") }),
		TRACKING(6, "追踪粉尘", 400, purchase = { service, player -> service.buyShopItem(player, "tracking") }),
		SUPPLY(7, "阵营补给包", 300, purchase = { service, player -> service.buyShopItem(player, "supply") }),
		ADVANCED(8, "高级补给包", 800, purchase = { service, player -> service.buyShopItem(player, "advanced") }),
		RESISTANCE(9, "临时抗性", 700, purchase = { service, player -> service.buyShopItem(player, "resistance") }),
		SPEED(10, "临时速度", 400, purchase = { service, player -> service.buyShopItem(player, "speed") }),
		NIGHT_VISION(11, "临时夜视", 150, purchase = { service, player -> service.buyShopItem(player, "nightvision") }),
		BLAST(12, "防爆增益", 900, purchase = { service, player -> service.buyShopItem(player, "blast") }),
		RESPAWN(13, "复活保护", 900, purchase = { service, player -> service.buyShopItem(player, "respawn") }),
		KEY_ALERT(14, "密钥警戒", 700, purchase = { service, player -> service.buyShopItem(player, "keyalert") }),
		BEACON(15, "传送信标", 1200, purchase = { service, player -> service.buyShopItem(player, "beacon") });
	}
}
