package vip.qoriginal.quantumplugin.fallen

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityExhaustionEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import vip.qoriginal.quantumplugin.CommandMessages

class FallenListener(private val service: FallenGameService) : Listener {
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onDestructiveCreatureSpawn(event: CreatureSpawnEvent) {
		if (!destructiveEntityRestrictionsActive()) return
		if (!FallenDestructiveEntityPolicy.isRestricted(event.entityType)) return
		event.isCancelled = true
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onDestructiveEntityExplode(event: EntityExplodeEvent) {
		if (isRestrictedDestructiveSource(event.entity)) {
			event.isCancelled = true
			return
		}
		service.recordDestroyedBlocks(event.blockList().map { it.location })
		event.blockList().removeIf { service.isFixedStationBlock(it.location) }
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onBlockExplode(event: BlockExplodeEvent) {
		service.recordDestroyedBlocks(event.blockList().map { it.location })
		event.blockList().removeIf { service.isFixedStationBlock(it.location) }
	}

	@EventHandler(ignoreCancelled = true)
	fun onTntPrime(event: TNTPrimeEvent) = service.recordLaboratoryTntPrimed(event.block.location)

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onPistonExtend(event: BlockPistonExtendEvent) {
		if (service.containsLaboratoryTnt(event.blocks.map { it.location })) event.isCancelled = true
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onPistonRetract(event: BlockPistonRetractEvent) {
		if (service.containsLaboratoryTnt(event.blocks.map { it.location })) event.isCancelled = true
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onDestructiveEntityChangeBlock(event: EntityChangeBlockEvent) {
		if (isRestrictedDestructiveSource(event.entity)) {
			event.isCancelled = true
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	fun onPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
		val message = service.loginDisconnectMessage() ?: return
		event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message)
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	fun onPlayerChat(event: AsyncChatEvent) {
		if (service.isRespawnWaiting(event.player) || service.isFinaleLocked(event.player)) {
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
		if (service.isLoadoutProtectionActive(event.player) && event.item?.let { service.isArmorMaterial(it.type) } == true) {
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
		if (service.isProtectedLoadoutItem(item)) {
			event.isCancelled = true
			CommandMessages.warning(event.player, "初始装备不可丢弃。")
			return
		}
		if (service.requestSelfDestruct(event.player, item)) {
			event.isCancelled = true
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
		if (service.isFinaleLocked(event.player)) {
			val canCancel = event.message.equals("/fallen finale cancel", ignoreCase = true)
				&& (event.player.isOp || event.player.hasPermission("quantumplugin.fallen.admin"))
			if (!canCancel) event.isCancelled = true
			return
		}
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
		val protectedItems = event.drops.filter { service.isProtectedLoadoutItem(it) }
		event.itemsToKeep.addAll(protectedItems)
		event.drops.removeAll(protectedItems.toSet())
		service.handleDeath(event.player)
		service.recordKill(event.player, event.player.killer)
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onPlayerItemDamage(event: PlayerItemDamageEvent) {
		if (service.isProtectedLoadoutItem(event.item)) event.isCancelled = true
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
		if (item != null && (service.isLiveKeyItem(item.itemStack) || service.isProtectedLoadoutItem(item.itemStack))) {
			event.isCancelled = true
			return
		}
		val player = event.entity as? Player ?: return
		if (service.isFinaleLocked(player)) {
			event.isCancelled = true
			return
		}
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
		if (isExplosionDamage(event)) {
			event.damage = event.damage * service.explosionDamageMultiplier(player)
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	fun onCaptureDamage(event: EntityDamageEvent) {
		val player = event.entity as? Player ?: return
		if (event.finalDamage > 0.0) service.interruptCapture(player)
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
		if (service.isPlayerCombatForbidden(attacker, target)) {
			event.isCancelled = true
			return
		}
		service.cancelRespawnProtection(attacker)
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	fun onPlayerDamageRecorded(event: EntityDamageByEntityEvent) {
		val attacker = attackingPlayer(event) ?: return
		val target = event.entity as? Player ?: return
		val actualDamage = minOf(event.finalDamage, target.health + target.absorptionAmount)
		service.recordDamage(attacker, target, actualDamage)
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
		if (service.rejectStationBlockEdit(event.player, event.block.location)) {
			event.isCancelled = true
			return
		}
		if (service.rejectKeyRegionBlockEdit(event.player, event.block.location)) {
			event.isCancelled = true
			return
		}
		if (!service.recordBlockPlace(event.player, event.block.location, event.block.type, event.itemInHand)) event.isCancelled = true
	}

	@EventHandler(ignoreCancelled = true)
	fun onBlockBreak(event: BlockBreakEvent) {
		if (denyRespawnWaitAction(event.player)) {
			event.isCancelled = true
			return
		}
		if (service.rejectStationBlockEdit(event.player, event.block.location)) {
			event.isCancelled = true
			return
		}
		if (service.rejectKeyRegionBlockEdit(event.player, event.block.location)) {
			event.isCancelled = true
			return
		}
		service.recordBlockBreak(event.player, event.block.location, event.block.type)
	}

	@EventHandler(ignoreCancelled = true)
	fun onPlayerMove(event: PlayerMoveEvent) {
		if (service.isFinaleLocked(event.player)) {
			event.to = event.from
			return
		}
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
	fun onProjectileHit(event: ProjectileHitEvent) {
		service.handleAlloyBulletImpact(event.entity)
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onPlayerTeleport(event: PlayerTeleportEvent) {
		if (service.isFinaleLocked(event.player)) {
			event.isCancelled = true
			return
		}
		if (service.rejectKeyTeleport(event.player, event.cause.name)) event.isCancelled = true
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onEntityPortal(event: EntityPortalEvent) {
		val item = event.entity as? Item ?: return
		if (service.isLiveKeyItem(item.itemStack)) event.isCancelled = true
	}

	@EventHandler(ignoreCancelled = true)
	fun onEntityPickupItem(event: EntityPickupItemEvent) {
		val isKey = service.isKeyItem(event.item.itemStack)
		if (service.isProtectedLoadoutItem(event.item.itemStack)) {
			event.isCancelled = true
			event.item.remove()
			return
		}
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
		if (service.isKeyItem(event.playerItem) || service.isKeyItem(event.armorStandItem)
			|| service.isProtectedLoadoutItem(event.playerItem) || service.isProtectedLoadoutItem(event.armorStandItem)) {
			event.isCancelled = true
			CommandMessages.warning(event.player, "陷落密钥不能交给其他实体。")
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryClick(event: InventoryClickEvent) {
		val player = event.whoClicked as? Player ?: return
		if (service.handlePlayerMenuClick(player, event.view.topInventory, event.rawSlot, event.currentItem)) {
			event.isCancelled = true
			return
		}
		if (denyRespawnWaitAction(player)) {
			event.isCancelled = true
			return
		}
		val armorLocked = service.isLoadoutProtectionActive(player) && (
			event.slotType == InventoryType.SlotType.ARMOR
				|| (event.isShiftClick && event.currentItem?.let { service.isArmorMaterial(it.type) } == true)
		)
		if (armorLocked) {
			event.isCancelled = true
			CommandMessages.warning(player, "活动护甲不可手动卸下或替换；请使用 /fallen gear 切换胸甲。")
			return
		}
		val top = event.view.topInventory
		val clickedTop = event.rawSlot in 0 until top.size
		val protected: (org.bukkit.inventory.ItemStack?) -> Boolean = { service.isKeyItem(it) || service.isProtectedLoadoutItem(it) }
		val keyInTop = clickedTop && protected(event.currentItem)
		val cursorIntoTop = clickedTop && protected(event.cursor)
		val shiftIntoTop = event.isShiftClick && !clickedTop && protected(event.currentItem)
		val hotbarIntoTop = clickedTop
			&& event.hotbarButton >= 0
			&& protected(player.inventory.getItem(event.hotbarButton))
		val offhandIntoTop = clickedTop
			&& event.click == ClickType.SWAP_OFFHAND
			&& protected(player.inventory.itemInOffHand)
		val keyIntoPortableContainer = !clickedTop && (
			(protected(event.cursor) && isPortableContainer(event.currentItem))
				|| (protected(event.currentItem) && isPortableContainer(event.cursor))
			)
		if (!keyInTop && !cursorIntoTop && !shiftIntoTop && !hotbarIntoTop && !offhandIntoTop && !keyIntoPortableContainer) return
		event.isCancelled = true
		CommandMessages.warning(player, "陷落密钥和初始装备不能进入任何容器。")
		if (keyInTop) {
			player.server.scheduler.runTask(vip.qoriginal.quantumplugin.QuantumPlugin.getInstance(), Runnable {
				service.evacuateKeyItemsFromContainer(top, top.location ?: player.location)
			})
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryDrag(event: InventoryDragEvent) {
		val player = event.whoClicked as? Player ?: return
		if (service.isPlayerMenu(event.view.topInventory)) {
			event.isCancelled = true
			return
		}
		if (service.isLoadoutProtectionActive(player)
			&& event.rawSlots.any { event.view.getSlotType(it) == InventoryType.SlotType.ARMOR }) {
			event.isCancelled = true
			return
		}
		if (!service.isKeyItem(event.oldCursor) && !service.isProtectedLoadoutItem(event.oldCursor)) return
		val topSize = event.view.topInventory.size
		if (event.rawSlots.none { it < topSize }) return
		event.isCancelled = true
		(event.whoClicked as? Player)?.let {
			CommandMessages.warning(it, "陷落密钥不能进入任何容器。")
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
		if (service.isKeyItem(event.item) || service.isProtectedLoadoutItem(event.item)) {
			event.isCancelled = true
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	fun onInventoryPickupItem(event: InventoryPickupItemEvent) {
		if (service.isKeyItem(event.item.itemStack) || service.isProtectedLoadoutItem(event.item.itemStack)) {
			event.isCancelled = true
		}
	}

	@EventHandler(ignoreCancelled = true)
	fun onItemDespawn(event: ItemDespawnEvent) {
		if (service.isLiveKeyItem(event.entity.itemStack) || service.isProtectedLoadoutItem(event.entity.itemStack)) {
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
		if (destructiveEntityRestrictionsActive()) {
			event.chunk.entities
				.filter { FallenDestructiveEntityPolicy.isRestricted(it.type) }
				.forEach(Entity::remove)
		}
		service.reconcileLoadedChunk(event.chunk)
		service.reconcileLaboratoryTntChunk(event.chunk)
	}

	private fun destructiveEntityRestrictionsActive(): Boolean =
		FallenAccessPolicy.isEventInProgress(service.phase)

	private fun isRestrictedDestructiveSource(entity: Entity): Boolean {
		if (!destructiveEntityRestrictionsActive()) return false
		if (FallenDestructiveEntityPolicy.isRestricted(entity.type)) return true
		val shooter = (entity as? Projectile)?.shooter as? Entity ?: return false
		return FallenDestructiveEntityPolicy.isRestricted(shooter.type)
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
		if (service.isFinaleLocked(player)) return true
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
