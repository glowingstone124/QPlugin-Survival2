package vip.qoriginal.quantumplugin.fallen

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile

class FallenListener(private val service: FallenGameService) : Listener {
	@EventHandler
	fun onPlayerInteract(event: PlayerInteractEvent) {
		if (event.action != Action.RIGHT_CLICK_BLOCK) return
		val block = event.clickedBlock ?: return
		val item = event.item ?: return
		if (service.placeKey(event.player, item, block.location)) {
			event.isCancelled = true
		}
	}

	@EventHandler
	fun onPlayerDropItem(event: PlayerDropItemEvent) {
		val item = event.itemDrop.itemStack
		if (service.requestSelfDestruct(event.player, item)) {
			event.isCancelled = true
		}
	}

	@EventHandler
	fun onPlayerQuit(event: PlayerQuitEvent) {
		service.dropPlayerKeys(event.player)
	}

	@EventHandler
	fun onPlayerJoin(event: PlayerJoinEvent) {
		service.claimPendingPoolKeys(event.player)
	}

	@EventHandler
	fun onPlayerDeath(event: PlayerDeathEvent) {
		event.drops.removeIf { service.isFallenCompass(it) }
		service.handleDeath(event.player)
		service.recordKill(event.player, event.player.killer)
	}

	@EventHandler
	fun onPlayerRespawn(event: PlayerRespawnEvent) {
		val location = service.respawnLocation(event.player) ?: return
		event.respawnLocation = location
		event.player.server.scheduler.runTask(vip.qoriginal.quantumplugin.QuantumPlugin.getInstance(), Runnable {
			service.protectRespawn(event.player)
		})
	}

	@EventHandler(ignoreCancelled = true)
	fun onEntityDamage(event: EntityDamageEvent) {
		val player = event.entity as? Player ?: return
		if (service.hasRespawnProtection(player)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
		val attacker = attackingPlayer(event) ?: return
		service.cancelRespawnProtection(attacker)
		val target = event.entity as? Player ?: return
		service.recordDamage(attacker, target, event.finalDamage)
	}

	@EventHandler(ignoreCancelled = true)
	fun onBlockBreak(event: BlockBreakEvent) {
		service.recordBlockBreak(event.player, event.block.type)
	}

	private fun attackingPlayer(event: EntityDamageByEntityEvent): Player? {
		val damager = event.damager
		if (damager is Player) return damager
		if (damager is Projectile) return damager.shooter as? Player
		return null
	}
}
