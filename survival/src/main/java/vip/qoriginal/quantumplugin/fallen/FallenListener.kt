package vip.qoriginal.quantumplugin.fallen

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExhaustionEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import vip.qoriginal.quantumplugin.CommandMessages

class FallenListener(private val service: FallenGameService) : Listener {
	@EventHandler(priority = EventPriority.HIGHEST)
	fun onPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
		val message = service.loginDisconnectMessage() ?: return
		event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message)
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	fun onPlayerChat(event: AsyncChatEvent) {
		if (service.isRespawnWaiting(event.player)) {
			event.isCancelled = true
			return
		}
		val message = PLAIN_TEXT.serialize(event.message())
		if (service.shouldBroadcastChatGlobally(event.player, message)) return
		val senderTeam = service.teamOf(event.player) ?: return
		event.viewers().removeIf { viewer ->
			viewer is Player && service.teamOf(viewer) != senderTeam
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun removeGlobalChatPrefix(event: AsyncChatEvent) {
		val message = PLAIN_TEXT.serialize(event.message())
		if (!message.startsWith("!")) return
		event.message(Component.text(message.drop(1)))
	}

	@EventHandler
	fun onPlayerInteract(event: PlayerInteractEvent) {
		if (denyRespawnWaitAction(event.player)) {
			event.isCancelled = true
			return
		}
		if ((event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)
			&& service.fireAlloyBullet(event.player, event.item)) {
			event.isCancelled = true
			return
		}
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
		if (denyRespawnWaitAction(event.player)) {
			event.isCancelled = true
			return
		}
		val item = event.itemDrop.itemStack
		if (service.requestSelfDestruct(event.player, item)) {
			event.isCancelled = true
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
		if (denyRespawnWaitAction(event.player)) {
			event.isCancelled = true
		}
	}

	@EventHandler
	fun onPlayerQuit(event: PlayerQuitEvent) {
		service.handleQuit(event.player)
	}

	@EventHandler
	fun onPlayerJoin(event: PlayerJoinEvent) {
		service.handleJoin(event.player)
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
				if (!event.player.isOnline) return@Runnable
				service.beginRespawnWait(event.player, location, delaySeconds)
			})
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
		if (service.isRespawnWaiting(player)) {
			event.isCancelled = true
			return
		}
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
		if (service.isRespawnWaiting(attacker)) {
			event.isCancelled = true
			service.notifyRespawnWaiting(attacker)
			return
		}
		val target = event.entity as? Player ?: return
		if (service.isRespawnWaiting(target)) {
			event.isCancelled = true
			return
		}
		if (service.isFriendlyFire(attacker, target)) {
			event.isCancelled = true
			return
		}
		service.cancelRespawnProtection(attacker)
		service.recordDamage(attacker, target, event.finalDamage)
	}

	@EventHandler(ignoreCancelled = true)
	fun onEntityExhaustion(event: EntityExhaustionEvent) {
		val player = event.entity as? Player ?: return
		event.exhaustion = service.reduceTeamExhaustion(player, event.exhaustion)
	}

	@EventHandler(ignoreCancelled = true)
	fun onBlockPlace(event: BlockPlaceEvent) {
		if (denyRespawnWaitAction(event.player)) {
			event.isCancelled = true
			return
		}
		if (service.rejectForbiddenEventItem(event.player, event.itemInHand)) {
			event.isCancelled = true
			return
		}
		service.recordBlockPlace(event.block.location, event.block.type)
	}

	@EventHandler(ignoreCancelled = true)
	fun onBlockBreak(event: BlockBreakEvent) {
		if (denyRespawnWaitAction(event.player)) {
			event.isCancelled = true
			return
		}
		if (service.handleStationCoreBreak(event.player, event.block.location)) {
			event.isCancelled = true
			return
		}
		service.recordBlockBreak(event.player, event.block.location, event.block.type)
	}

	@EventHandler(ignoreCancelled = true)
	fun onPlayerMove(event: PlayerMoveEvent) {
		val held = service.holdRespawnMovement(event.player, event.to)
		if (held != null) {
			event.to = held
			service.notifyRespawnWaiting(event.player)
			return
		}
		service.recordMovement(event.player, event.from, event.to)
	}

	@EventHandler(ignoreCancelled = true)
	fun onProjectileLaunch(event: ProjectileLaunchEvent) {
		val player = event.entity.shooter as? Player ?: return
		if (denyRespawnWaitAction(player)) {
			event.isCancelled = true
			return
		}
		if (service.rejectForbiddenEventItem(player, player.inventory.itemInMainHand) || service.rejectForbiddenEventItem(player, player.inventory.itemInOffHand)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onEntityPickupItem(event: EntityPickupItemEvent) {
		val isKey = service.isKeyItem(event.item.itemStack)
		val player = event.entity as? Player
		if (player == null) {
			if (isKey) event.isCancelled = true
			return
		}
		if (denyRespawnWaitAction(player)) {
			event.isCancelled = true
			return
		}
		if (service.rejectForbiddenEventItem(player, event.item.itemStack)) {
			event.isCancelled = true
			event.item.remove()
			return
		}
		if (!service.handleKeyPickup(player, event.item.itemStack)) {
			event.isCancelled = service.keyId(event.item.itemStack) != null
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
		if (denyRespawnWaitAction(event.player)
			|| service.isKeyItem(event.player.inventory.itemInMainHand)
			|| service.isKeyItem(event.player.inventory.itemInOffHand)) {
			event.isCancelled = true
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
		if (service.isKeyItem(event.playerItem) || service.isKeyItem(event.armorStandItem)) {
			event.isCancelled = true
			CommandMessages.warning(event.player, "陷落密钥不能交给其他实体。")
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryClick(event: InventoryClickEvent) {
		val player = event.whoClicked as? Player ?: return
		if (denyRespawnWaitAction(player)) {
			event.isCancelled = true
			return
		}
		val top = event.view.topInventory
		val clickedTop = event.rawSlot in 0 until top.size
		val keyInTop = clickedTop && service.isKeyItem(event.currentItem)
		val cursorIntoTop = clickedTop && service.isKeyItem(event.cursor)
		val shiftIntoTop = event.isShiftClick && !clickedTop && service.isKeyItem(event.currentItem)
		val hotbarIntoTop = clickedTop
			&& event.hotbarButton >= 0
			&& service.isKeyItem(player.inventory.getItem(event.hotbarButton))
		val offhandIntoTop = clickedTop
			&& event.click == ClickType.SWAP_OFFHAND
			&& service.isKeyItem(player.inventory.itemInOffHand)
		val keyIntoPortableContainer = !clickedTop && (
			(service.isKeyItem(event.cursor) && isPortableContainer(event.currentItem))
				|| (service.isKeyItem(event.currentItem) && isPortableContainer(event.cursor))
			)
		if (!keyInTop && !cursorIntoTop && !shiftIntoTop && !hotbarIntoTop && !offhandIntoTop && !keyIntoPortableContainer) return
		event.isCancelled = true
		CommandMessages.warning(player, "陷落密钥不能进入任何容器。")
		if (keyInTop) {
			player.server.scheduler.runTask(vip.qoriginal.quantumplugin.QuantumPlugin.getInstance(), Runnable {
				service.evacuateKeyItemsFromContainer(top, top.location ?: player.location)
			})
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryDrag(event: InventoryDragEvent) {
		if (!service.isKeyItem(event.oldCursor)) return
		val topSize = event.view.topInventory.size
		if (event.rawSlots.none { it < topSize }) return
		event.isCancelled = true
		(event.whoClicked as? Player)?.let {
			CommandMessages.warning(it, "陷落密钥不能进入任何容器。")
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
		if (service.isKeyItem(event.item)) {
			event.isCancelled = true
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryPickupItem(event: InventoryPickupItemEvent) {
		if (service.isKeyItem(event.item.itemStack)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onItemDespawn(event: ItemDespawnEvent) {
		if (service.isLiveKeyItem(event.entity.itemStack)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onItemSpawn(event: ItemSpawnEvent) {
		service.protectDroppedKeyEntity(event.entity)
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onItemMerge(event: ItemMergeEvent) {
		if (service.isKeyItem(event.entity.itemStack) || service.isKeyItem(event.target.itemStack)) {
			event.isCancelled = true
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	fun onEntityRemove(event: EntityRemoveEvent) {
		val item = event.entity as? Item ?: return
		if (event.cause != EntityRemoveEvent.Cause.UNLOAD) {
			service.handleDroppedKeyEntityRemoval(item)
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onPortalCreate(event: PortalCreateEvent) {
		if (event.blocks.any { service.isNearPlacedKey(it.location, 100.0) }) {
			event.isCancelled = true
		}
	}

	@EventHandler
	fun onChunkLoad(event: ChunkLoadEvent) {
		service.reconcileLoadedChunk(event.chunk)
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

	private fun denyRespawnWaitAction(player: Player): Boolean {
		if (!service.isRespawnWaiting(player)) return false
		service.notifyRespawnWaiting(player)
		return true
	}

	private fun isPortableContainer(item: org.bukkit.inventory.ItemStack?): Boolean {
		val name = item?.type?.name ?: return false
		return name == "BUNDLE" || name.endsWith("_BUNDLE") || name.endsWith("_SHULKER_BOX")
	}

	companion object {
		private val PLAIN_TEXT = PlainTextComponentSerializer.plainText()
	}
}
