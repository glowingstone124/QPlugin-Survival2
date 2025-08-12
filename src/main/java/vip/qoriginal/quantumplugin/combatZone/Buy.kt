package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType

class Buy : CommandExecutor {


	data class Merchandise(
		val itemStack: ItemStack,
		val price: Int,
	)

	val combatPoints = CombatPoints()

	val merchandiseList = listOf(
		Merchandise(ItemStack(Material.EXPERIENCE_BOTTLE, 1), 5),
		Merchandise(ItemStack(Material.GOLDEN_APPLE, 2), 5),
		Merchandise(ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1), 15),
		Merchandise(ItemStack(Material.GOLDEN_CARROT, 1), 1),
		Merchandise(ItemStack(Material.LAVA_BUCKET, 1), 10),
		Merchandise(ItemStack(Material.END_CRYSTAL, 2), 5),
		Merchandise(ItemStack(Material.SPECTRAL_ARROW, 4), 5),
		Merchandise(givePotion(0), 15),
		Merchandise(givePotion(1), 30),
		Merchandise(givePotion(2), 25),
		Merchandise(givePotion(3), 30),
	)
	fun givePotion(id: Int) : ItemStack {
		val potion = ItemStack(Material.POTION)
		val meta = potion.itemMeta as PotionMeta

		meta.basePotionType = when(id) {
			0 -> PotionType.STRENGTH
			1 -> PotionType.STRONG_STRENGTH
			2 -> PotionType.HEALING
			3 -> PotionType.INVISIBILITY
			else -> PotionType.WATER
		}

		potion.itemMeta = meta

		return potion

	}

	override fun onCommand(player: CommandSender, command: Command, p2: String, args: Array<out String>): Boolean {
		if (player !is Player) {
			player.sendMessage("Only players can use this command.")
			return true
		}
		if (args.size !in 1..3) {
			player.sendMessage("/buy <id> <数量>，输入/buy list查看可购买物品列表")
			return true
		}

		if (args[0].equals("list", ignoreCase = true)) {
			player.sendMessage("商品列表：")
			merchandiseList.forEachIndexed { index, merchandise ->
				player.sendMessage("$index: ${merchandise.itemStack.type} - 价格: ${merchandise.price}")
			}
			return true
		}

		val id = args[0].toIntOrNull()
		if (id == null || id !in merchandiseList.indices) {
			player.sendMessage("无效的商品ID，请输入有效数字。")
			return true
		}

		val buyAmount = if (args.size >= 2) {
			args[1].toIntOrNull() ?: 1
		} else {
			1
		}.coerceAtLeast(1)

		val price = merchandiseList[id].price * buyAmount

		val items = merchandiseList[id].itemStack.clone().apply {
			amount = buyAmount
		}

		val playerStat = CombatPoint.playerStats[player.uniqueId] ?: return false

		if (playerStat.points < price) {
			player.sendMessage(Component.text("购买失败：点数不足。").color(NamedTextColor.YELLOW))
			return true
		}

		playerStat.minusPoints(price, CombatPoints.RemoveReason.BUY)

		val remaining = player.inventory.addItem(items)
		if (remaining.isNotEmpty()) {
			remaining.values.forEach { leftover ->
				player.world.dropItemNaturally(player.location, leftover)
			}
			player.sendMessage(Component.text("背包已满，部分物品已掉落在地上。").color(NamedTextColor.RED))
		}

		player.sendMessage(
			Component.text("购买成功：").color(NamedTextColor.GREEN)
				.append(items.displayName().color(NamedTextColor.GOLD))
				.append(Component.text(" x${buyAmount}"))
		)

		return true
	}
}