package vip.qoriginal.quantumplugin.patch

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.type.TNT
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import vip.qoriginal.quantumplugin.QuantumPlugin

class FriendlyTnt : CommandExecutor, Listener {
	val customTntKey = NamespacedKey(QuantumPlugin.getInstance(), "custom_tnt")
	override fun onCommand(
		sender: CommandSender,
		command: Command,
		label: String,
		args: Array<out String>
	): Boolean {
		if (sender !is Player) {
			sender.sendMessage("Only players can use this command")
			return false
		}
		if (!(args!!.size == 1 && args[0] == "give")) {
			sender.sendMessage("/newyeartnt give")
			return false
		}
		val player = sender as Player
		val tnt = ItemStack(Material.TNT).add(7)
		val meta = tnt.itemMeta
		if (meta != null) {
			meta.displayName(Component.text("红包").decoration(TextDecoration.BOLD, true).color(NamedTextColor.YELLOW))
			meta.lore(
				listOf<Component>(
					Component.text("新年快乐！说不定里面有什么惊喜...").color(NamedTextColor.YELLOW),
					Component.text("此物品用于庆祝2025年农历春节").color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
				)
			)
			meta.persistentDataContainer.set(customTntKey, PersistentDataType.BYTE, 1.toByte())
			tnt.itemMeta = meta
		}
		player.inventory.addItem(tnt)
		player.sendMessage("成功")
		return true
	}
	@EventHandler
	fun onBlockPlace(event: BlockPlaceEvent) {
		val player = event.player
		val item = event.getItemInHand()
		if (item.type === Material.TNT) {
			val meta = item.itemMeta
			if (meta != null && meta.persistentDataContainer.has(customTntKey, PersistentDataType.BYTE)) {
				event.isCancelled = true
				item.amount -= 1
				meta.persistentDataContainer.set(customTntKey, PersistentDataType.FLOAT, 0f)
				val location = player.location
				val tnt = location.world!!.spawn(location, TNTPrimed::class.java)
				tnt.fuseTicks = 60
				tnt.persistentDataContainer.set(customTntKey, PersistentDataType.BYTE, 1.toByte());
			}
		}
	}
	@EventHandler
	fun onTntExplode(event: EntityExplodeEvent) {
		val entity = event.entity
		if (entity.type == EntityType.TNT) {
			val tnt = entity as TNTPrimed
			if (tnt.persistentDataContainer.has(customTntKey, PersistentDataType.BYTE)) {
				event.isCancelled = true
				tnt.fireTicks = 0

				entity.getNearbyEntities(5.0, 5.0, 5.0).forEach { nearbyEntity ->
					if (nearbyEntity is Player) {
						val player = nearbyEntity as Player
						val direction = player.location.toVector().subtract(entity.location.toVector()).normalize()
						player.velocity = direction.multiply(2)
					}
				}
			}
		}
	}
}