package vip.qoriginal.quantumplugin.eliteWeapons

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.Request

class EliteWeaponListener : Listener {
	val eldata = EliteWeaponData()
	@EventHandler
	fun onPlayerAttack(event: EntityDamageByEntityEvent) {
		val player = event.damager
		if (player !is Player) return
		val item: ItemStack = player.inventory.itemInMainHand
		if (!eldata.checkIfWeaponHasEliteData(item)) return
		val dmg = event.damage
		val uuid = eldata.getWeaponUuid(item)
		if (EliteWeaponData.EliteWeaponCache[player.name]?.find { it.uuid == uuid } != null) {
			Request.sendPostRequest(Config.API_ENDPOINT + "/qo/elite/add?type=dmg&requester=${player.name}&uuid=${uuid}&amount=${dmg}", "")
		}
	}

	@EventHandler
	fun onEntityDeath(event: EntityDeathEvent) {
		val entity = event.damageSource.causingEntity as? Player ?: return
		val item = entity.inventory.itemInMainHand
		if (!eldata.checkIfWeaponHasEliteData(item)) return
		val uuid = eldata.getWeaponUuid(item)
		if (EliteWeaponData.EliteWeaponCache[entity.name]?.find { it.uuid == uuid } != null) {
			Request.sendPostRequest(Config.API_ENDPOINT + "/qo/elite/add?type=kill&requester=${entity.name}&uuid=${uuid}&amount=1", "")
		}
	}

}