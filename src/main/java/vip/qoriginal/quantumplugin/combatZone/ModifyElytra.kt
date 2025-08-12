package vip.qoriginal.quantumplugin.combatZone

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.EquipmentSlot

class ModifyElytra : Listener {
	@EventHandler
	fun onElytraGlide(event: EntityToggleGlideEvent) {
		val player = event.entity
		if (player is org.bukkit.entity.Player) {
			if (event.isGliding) {
				event.isCancelled = true
				player.isGliding = false
			}
		}
	}

	@EventHandler
	fun onEquipElytra(event: InventoryClickEvent) {
		if (event.slotType == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR &&
			event.slot == EquipmentSlot.CHEST.ordinal
		) {
			val current = event.currentItem
			val cursor = event.cursor
			if (current?.type == Material.ELYTRA || cursor.type == Material.ELYTRA) {
				event.isCancelled = true
			}
		}
	}

}