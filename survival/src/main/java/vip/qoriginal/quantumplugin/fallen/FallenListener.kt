package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile

class FallenListener(private val service: FallenGameService) : Listener {
	@EventHandler
	fun onPlayerInteract(event: PlayerInteractEvent) {
		if (service.rejectForbiddenEventItem(event.player, event.item)) {
			event.isCancelled = true
			return
		}
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
		service.handleQuit(event.player)
	}

	@EventHandler
	fun onPlayerJoin(event: PlayerJoinEvent) {
		service.sanitizeForbiddenEventItems(event.player)
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
		val delaySeconds = service.respawnDelaySeconds(event.player)
		if (delaySeconds > 0) {
			event.player.server.scheduler.runTask(vip.qoriginal.quantumplugin.QuantumPlugin.getInstance(), Runnable {
				service.allowNextGameModeChange(event.player)
				event.player.gameMode = GameMode.SPECTATOR
				event.player.sendMessage(Component.text("复活等待 ${delaySeconds} 秒。", NamedTextColor.YELLOW))
			})
			event.player.server.scheduler.runTaskLater(vip.qoriginal.quantumplugin.QuantumPlugin.getInstance(), Runnable {
				if (!event.player.isOnline || service.teamOf(event.player) == null) return@Runnable
				event.player.teleport(location)
				service.allowNextGameModeChange(event.player)
				event.player.gameMode = GameMode.SURVIVAL
				service.protectRespawn(event.player)
			}, delaySeconds * 20L)
			return
		}
		event.player.server.scheduler.runTask(vip.qoriginal.quantumplugin.QuantumPlugin.getInstance(), Runnable {
			service.protectRespawn(event.player)
		})
	}

	@EventHandler(ignoreCancelled = true)
	fun onGameModeChange(event: PlayerGameModeChangeEvent) {
		if (service.isGameModeChangeAllowed(event.player)) return
		event.isCancelled = true
		event.player.sendMessage(Component.text("《陷落》活动期间禁止切换游戏模式。", NamedTextColor.YELLOW))
	}

	@EventHandler(ignoreCancelled = true)
	fun onEntityDamage(event: EntityDamageEvent) {
		val item = event.entity as? Item
		if (item != null && service.isLiveKeyItem(item.itemStack)) {
			event.isCancelled = true
			return
		}
		val player = event.entity as? Player ?: return
		if (service.hasRespawnProtection(player)) {
			event.isCancelled = true
			return
		}
		if (isExplosionDamage(event) && service.applyBlastProtection(player)) {
			event.damage = event.damage * 0.4
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
	fun onBlockPlace(event: BlockPlaceEvent) {
		if (service.rejectForbiddenEventItem(event.player, event.itemInHand)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onBlockBreak(event: BlockBreakEvent) {
		if (service.handleStationCoreBreak(event.player, event.block.location)) {
			event.isCancelled = true
			return
		}
		service.recordBlockBreak(event.player, event.block.type)
	}

	@EventHandler(ignoreCancelled = true)
	fun onPlayerMove(event: PlayerMoveEvent) {
		service.recordMovement(event.player, event.from, event.to)
	}

	@EventHandler(ignoreCancelled = true)
	fun onProjectileLaunch(event: ProjectileLaunchEvent) {
		val player = event.entity.shooter as? Player ?: return
		if (service.rejectForbiddenEventItem(player, player.inventory.itemInMainHand) || service.rejectForbiddenEventItem(player, player.inventory.itemInOffHand)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onEntityPickupItem(event: EntityPickupItemEvent) {
		val player = event.entity as? Player ?: return
		if (service.rejectForbiddenEventItem(player, event.item.itemStack)) {
			event.isCancelled = true
			event.item.remove()
			return
		}
		if (!service.handleKeyPickup(player, event.item.itemStack)) {
			event.isCancelled = service.keyId(event.item.itemStack) != null
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onItemDespawn(event: ItemDespawnEvent) {
		if (service.isLiveKeyItem(event.entity.itemStack)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onPortalCreate(event: PortalCreateEvent) {
		if (event.blocks.any { service.isNearPlacedKey(it.location, 100.0) }) {
			event.isCancelled = true
		}
	}

	private fun attackingPlayer(event: EntityDamageByEntityEvent): Player? {
		val damager = event.damager
		if (damager is Player) return damager
		if (damager is Projectile) return damager.shooter as? Player
		return null
	}

	private fun isExplosionDamage(event: EntityDamageEvent): Boolean {
		return event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
			|| event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
	}
}
