package vip.qoriginal.quantumplugin.eliteWeapons

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.LoggerProvider
import vip.qoriginal.quantumplugin.Request
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class EliteWeaponListener(private val plugin: JavaPlugin) : Listener {
	private val eldata = EliteWeaponData()
	private val logger = LoggerProvider.getLogger("EliteWeaponListener")
	private val pending = ConcurrentHashMap<UpdateKey, PendingStats>()
	private val inFlight = ConcurrentHashMap.newKeySet<UpdateKey>()
	private val flushTask: BukkitTask = plugin.server.scheduler.runTaskTimer(
		plugin,
		Runnable(::flush),
		FLUSH_INTERVAL_TICKS,
		FLUSH_INTERVAL_TICKS
	)

	private data class UpdateKey(val requester: String, val uuid: String)
	private class PendingStats {
		val damage = AtomicLong()
		val kills = AtomicLong()
	}

	@EventHandler
	fun onPlayerAttack(event: EntityDamageByEntityEvent) {
		val player = event.damager
		if (player !is Player) return
		val item: ItemStack = player.inventory.itemInMainHand
		if (!eldata.checkIfWeaponHasEliteData(item)) return
		val damage = event.damage.roundToLong()
		if (damage <= 0L) return
		val uuid = eldata.getWeaponUuid(item) ?: return
		if (EliteWeaponData.EliteWeaponCache[player.name]?.find { it.uuid == uuid } != null) {
			pending.computeIfAbsent(UpdateKey(player.name, uuid)) { PendingStats() }.damage.addAndGet(damage)
		}
	}

	@EventHandler
	fun onEntityDeath(event: EntityDeathEvent) {
		val entity = event.damageSource.causingEntity as? Player ?: return
		val item = entity.inventory.itemInMainHand
		if (!eldata.checkIfWeaponHasEliteData(item)) return
		val uuid = eldata.getWeaponUuid(item) ?: return
		if (EliteWeaponData.EliteWeaponCache[entity.name]?.find { it.uuid == uuid } != null) {
			pending.computeIfAbsent(UpdateKey(entity.name, uuid)) { PendingStats() }.kills.incrementAndGet()
		}
	}

	fun shutdown() {
		flushTask.cancel()
		flush()
	}

	private fun flush() {
		for ((key, stats) in pending) {
			if (!inFlight.add(key)) continue
			val damage = stats.damage.getAndSet(0L)
			val kills = stats.kills.getAndSet(0L)
			if (damage <= 0L && kills <= 0L) {
				pending.remove(key, stats)
				inFlight.remove(key)
				continue
			}
			val url = logger.strWithDebugPrint(
				"${Config.API_ENDPOINT}/qo/elite/batch?requester=${key.requester}&uuid=${key.uuid}&damage=$damage&kills=$kills"
			)
			Request.sendPostRequestWithStatus(
				url,
				"",
				Optional.of(mapOf("Token" to Config.API_SECRET))
			).whenComplete { response, error ->
				val accepted = error == null && response != null && response.status in 200..299
				if (!accepted) {
					pending.computeIfAbsent(key) { PendingStats() }.also {
						it.damage.addAndGet(damage)
						it.kills.addAndGet(kills)
					}
					logger.log("批量上传失败，将重试 ${key.uuid}: ${error?.message ?: "HTTP ${response?.status}"}")
				} else if (stats.damage.get() == 0L && stats.kills.get() == 0L) {
					pending.remove(key, stats)
				}
				inFlight.remove(key)
			}
		}
	}

	@EventHandler
	fun onAnvilPrepare(event: PrepareAnvilEvent) {
		val result = event.result ?: return

		if (eldata.checkIfWeaponHasEliteData(result)) {
			event.result = null
		}
	}

	@EventHandler
	fun onAnvilClick(event: InventoryClickEvent) {
		val inv = event.inventory
		if (inv.type != InventoryType.ANVIL) return

		val current = event.currentItem ?: return
		if (eldata.checkIfWeaponHasEliteData(current)) {
			event.isCancelled = true
		}
	}

	companion object {
		private const val FLUSH_INTERVAL_TICKS = 5L * 20L
	}
}
