package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import vip.qoriginal.quantumplugin.QuantumPlugin
import kotlin.math.min

class Shop : Listener {
	private val shopInventories = mutableMapOf<Player, Inventory>()
	val instance = QuantumPlugin.getInstance()
	val combatPoints = CombatPoints()
	private val priceMap = mapOf(
		Material.COPPER_INGOT to 16,
	)

	private fun isLeaves(material: Material): Boolean {
		return material.name.endsWith("_LEAVES")
	}

	fun getExchangeRate(material: Material): Int {
		return when {
			isLeaves(material) -> 256
			else -> priceMap[material] ?: 0
		}
	}

	fun openShop(player: Player) {
		val inventory = instance.server.createInventory(null, 54, Component.text("出售物品"))
		shopInventories[player] = inventory

		player.sendMessage(Component.text("=== 商店价格表 ===").color(TextColor.color(255, 215, 0)))
		priceMap.forEach { (material, count) ->
			player.sendMessage(
				Component.text("${material.name}: $count 个 = 1 积分")
					.color(TextColor.color(200, 200, 200))
			)
		}
		player.sendMessage(
			Component.text("所有种类树叶可以混合计算，总数达到 256 个即可兑换 1 积分")
				.color(TextColor.color(200, 200, 200))
		)

		player.openInventory(inventory)
		player.sendMessage(
			Component.text("请将要出售的物品放入背包中，关闭背包时自动出售")
				.color(TextColor.color(0, 255, 0))
		)

	}

	@EventHandler
	fun onInventoryClose(event: InventoryCloseEvent) {
		val player = event.player as? Player ?: return
		val inventory = event.inventory

		if (shopInventories.containsKey(player)) {
			var totalPoints = 0
			val soldItems = mutableMapOf<Material, Int>()
			var totalLeaves = 0
			val leavesTypes = mutableMapOf<Material, Int>()
			val successfulSales = mutableListOf<ItemStack>()

			for (slot in 0 until inventory.size) {
				val item = inventory.getItem(slot) ?: continue

				if (isLeaves(item.type)) {
					totalLeaves += item.amount
					leavesTypes[item.type] = (leavesTypes[item.type] ?: 0) + item.amount
					successfulSales.add(item.clone())
					inventory.setItem(slot, null)
				} else {
					val exchangeRate = priceMap[item.type]
					if (exchangeRate != null) {
						val points = item.amount / exchangeRate
						if (points > 0) {
							totalPoints += points
							soldItems[item.type] = item.amount
							successfulSales.add(item.clone())
							inventory.setItem(slot, null)

							val remainder = item.amount % exchangeRate
							if (remainder > 0) {
								val remainderItem = ItemStack(item.type, remainder)
								player.inventory.addItem(remainderItem)
								player.sendMessage(
									Component.text("退还 $remainder 个 ${item.type.name}")
										.color(TextColor.color(200, 200, 200))
								)
							}
						}
					}
				}
			}

			val leavesPoints = totalLeaves / 256
			if (leavesPoints > 0) {
				totalPoints += leavesPoints
				val remainder = totalLeaves % 256
				if (remainder > 0) {
					var remainingLeaves = remainder
					for ((type, amount) in leavesTypes) {
						val typeRemainder = (amount.toDouble() / totalLeaves * remainder).toInt()
						if (typeRemainder > 0) {
							val remainderItem = ItemStack(type, typeRemainder)
							player.inventory.addItem(remainderItem)
							remainingLeaves -= typeRemainder
							player.sendMessage(
								Component.text("退还 $typeRemainder 个 ${type.name}")
									.color(TextColor.color(200, 200, 200))
							)
						}
					}
					if (remainingLeaves > 0) {
						val firstLeafType = leavesTypes.keys.first()
						val finalRemainder = ItemStack(firstLeafType, remainingLeaves)
						player.inventory.addItem(finalRemainder)
					}
				}
			}

			if (totalPoints > 0) {
				val stats = combatPoints.getStats(player)
				stats.addPoints(totalPoints, CombatPoints.AddReason.SELL)

				displayTransactionResults(player, soldItems, leavesTypes, totalLeaves, leavesPoints, totalPoints)
			} else {
				player.sendMessage(
					Component.text("没有可出售的物品")
						.color(TextColor.color(255, 0, 0))
				)
				inventory.contents.forEach { item ->
					item?.let { handleItemReturn(player, it.type, it.amount) }
				}

			}

			shopInventories.remove(player)
		}
	}
	private fun handleItemReturn(player: Player, type: Material, amount: Int) {
		var remainingAmount = amount
		while (remainingAmount > 0) {
			val stackSize = min(remainingAmount, type.maxStackSize)
			val item = createItemStack(type, stackSize)
			val notAdded = player.inventory.addItem(item).values.sumOf { it.amount }
			if (notAdded > 0) {
				player.world.dropItem(player.location, createItemStack(type, notAdded))
				player.sendMessage(
					Component.text("背包已满，${notAdded}个${type.name}已掉落在地上")
						.color(TextColor.color(255, 165, 0))
				)
			}
			remainingAmount -= (stackSize - notAdded)
		}
	}
	private fun createItemStack(type: Material, amount: Int): ItemStack {
		return ItemStack(type).apply {
			setAmount(min(amount, type.maxStackSize))
		}
	}

	private fun displayTransactionResults(
		player: Player,
		soldItems: Map<Material, Int>,
		leavesTypes: Map<Material, Int>,
		totalLeaves: Int,
		leavesPoints: Int,
		totalPoints: Int
	) {
		player.sendMessage(Component.text("=== 出售成功 ===").color(TextColor.color(0, 255, 0)))

		soldItems.forEach { (material, amount) ->
			val exchangeRate = priceMap[material] ?: 1
			val pointsForType = amount / exchangeRate
			player.sendMessage(
				Component.text("出售 $amount 个 ${material.name}，获得 $pointsForType 积分")
					.color(TextColor.color(200, 200, 200))
			)
		}

		if (leavesTypes.isNotEmpty()) {
			player.sendMessage(Component.text("树叶交易详情:").color(TextColor.color(200, 200, 200)))
			leavesTypes.forEach { (material, amount) ->
				player.sendMessage(
					Component.text("- $amount 个 ${material.name}")
						.color(TextColor.color(200, 200, 200))
				)
			}
			player.sendMessage(
				Component.text("总共 $totalLeaves 个树叶，兑换 $leavesPoints 积分")
					.color(TextColor.color(200, 200, 200))
			)
		}

		player.sendMessage(
			Component.text("总计获得: $totalPoints 积分")
				.color(TextColor.color(255, 215, 0))
		)
	}

	@EventHandler
	fun onInventoryClick(event: InventoryClickEvent) {
		val player = event.whoClicked as? Player ?: return

		if (shopInventories.containsKey(player)) {
			if (event.clickedInventory == player.inventory) {
				val item = event.currentItem
				if (item != null && !isLeaves(item.type) && !priceMap.containsKey(item.type)) {
					event.isCancelled = true
					player.sendMessage(
						Component.text("该物品无法出售！")
							.color(TextColor.color(255, 0, 0))
					)
				}
			} else {
				event.isCancelled = true
			}
		}
	}

}