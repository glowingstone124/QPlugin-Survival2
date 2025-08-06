package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import vip.qoriginal.quantumplugin.QuantumPlugin

class ShopCommand : CommandExecutor {

	val instance = QuantumPlugin.getInstance()
	val combatPoints = CombatPoints()

	private val priceMap = mapOf(
		Material.COPPER_INGOT to 16,
	)

	private fun isLeaves(material: Material): Boolean {
		return material.name.endsWith("_LEAVES")
	}

	override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
		if (sender !is Player) {
			sender.sendMessage("此命令只能由玩家执行")
			return true
		}

		val player = sender
		val inventory = player.inventory
		var totalPoints = 0
		val soldItems = mutableMapOf<Material, Int>()
		var totalLeaves = 0
		val leavesTypes = mutableMapOf<Material, Int>()

		for (slot in 0 until inventory.size) {
			val item = inventory.getItem(slot) ?: continue
			val material = item.type
			val amount = item.amount

			if (isLeaves(material)) {
				totalLeaves += amount
				leavesTypes[material] = (leavesTypes[material] ?: 0) + amount
				inventory.setItem(slot, null) // 先移除，稍后统一处理剩余
			} else if (priceMap.containsKey(material)) {
				val rate = priceMap[material]!!
				val points = amount / rate
				val remainder = amount % rate

				if (points > 0) {
					totalPoints += points
					soldItems[material] = amount
					if (remainder > 0) {
						inventory.setItem(slot, ItemStack(material, remainder))
						player.sendMessage(Component.text("退还 $remainder 个 ${material.name}").color(TextColor.color(200, 200, 200)))
					} else {
						inventory.setItem(slot, null)
					}
				}
			}
		}

		val leavesPoints = totalLeaves / 256
		val leavesRemainder = totalLeaves % 256

		if (leavesPoints > 0) {
			totalPoints += leavesPoints

			// 退还叶子余数
			if (leavesRemainder > 0) {
				var remaining = leavesRemainder
				for ((type, amount) in leavesTypes) {
					val ratio = amount.toDouble() / totalLeaves
					val refundCount = (ratio * leavesRemainder).toInt()
					if (refundCount > 0) {
						player.inventory.addItem(ItemStack(type, refundCount))
						remaining -= refundCount
						player.sendMessage(Component.text("退还 $refundCount 个 ${type.name}").color(TextColor.color(200, 200, 200)))
					}
				}
				if (remaining > 0) {
					// 剩余未退完的直接退还任意一种叶子
					val type = leavesTypes.keys.first()
					player.inventory.addItem(ItemStack(type, remaining))
				}
			}
		}

		if (totalPoints > 0) {
			val stats = combatPoints.getStats(player)
			stats.addPoints(totalPoints, CombatPoints.AddReason.SELL)

			// 反馈消息
			player.sendMessage(Component.text("=== 出售成功 ===").color(TextColor.color(0, 255, 0)))
			soldItems.forEach { (material, amount) ->
				val rate = priceMap[material]!!
				val points = amount / rate
				player.sendMessage(Component.text("出售 $amount 个 ${material.name}，获得 $points 积分").color(TextColor.color(200, 200, 200)))
			}
			if (leavesPoints > 0) {
				player.sendMessage(Component.text("出售 $totalLeaves 个树叶，获得 $leavesPoints 积分").color(TextColor.color(200, 200, 200)))
			}
			player.sendMessage(Component.text("总计获得: $totalPoints 积分").color(TextColor.color(255, 215, 0)))
		} else {
			player.sendMessage(Component.text("没有可出售的物品").color(TextColor.color(255, 0, 0)))
		}

		return true
	}
}
