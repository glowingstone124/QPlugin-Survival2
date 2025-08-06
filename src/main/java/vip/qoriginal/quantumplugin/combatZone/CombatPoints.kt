package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import vip.qoriginal.quantumplugin.combatZone.CombatPoint.playerStats
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.PlayerStats
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

object CombatPoint {
	val playerStats = ConcurrentHashMap<UUID, PlayerStats>()
}

class CombatPoints : Listener {

	enum class AddReason {
		KILL,
		MINE,
		DAMAGE,
		SELL
	}
	enum class RemoveReason {
		KILLED_PLAYER,
		KILLED_ACCIDENT,
		BUY,
	}
	data class PlayerStats (
		var points : Int = 0,
		var kills : Int = 0,
		var deaths : Int = 0,
		var damageDealt : Int = 0
	) {
		fun addPoints(amount: Int, reason: AddReason) {
			points += amount
		}
		fun minusPoints(amount: Int, reason: RemoveReason) {
			points -= amount
		}

		fun addDamage(amount: Int) {
			damageDealt += amount
			addPoints((amount * 0.5).floor(), AddReason.DAMAGE)
		}
		fun addKill() {
			kills++
			addPoints(50, AddReason.KILL)
		}
	}
	fun getStats(player: Player) = playerStats.getOrPut(player.uniqueId) { PlayerStats() }

	@EventHandler
	fun onPlayerDeath(event: PlayerDeathEvent) {
		val dead = event.entity
		val killer = event.entity.killer

		val deadStats = getStats(dead)
		if (killer != null && killer != dead) {
			val killerStats = getStats(killer)
			val stolenPoints = (deadStats.points * 0.7).floor()
			killerStats.addPoints(stolenPoints, AddReason.KILL)
			killerStats.addKill()

			deadStats.minusPoints(deadStats.points, RemoveReason.KILLED_PLAYER)
			deadStats.deaths++
		} else {
			deadStats.minusPoints((deadStats.points * 0.25).floor(), RemoveReason.KILLED_ACCIDENT)
		}
	}


	@EventHandler
	fun onBlockBreak(event: org.bukkit.event.block.BlockBreakEvent) {
		val player = event.player
		val block = event.block.type
		val bonus = when (block) {
			Material.ANCIENT_DEBRIS -> 4;
			Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> 2;
			Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> 1;
			else -> 0;
		}
		if (bonus > 0) {
			val stats = getStats(player)
			stats.addPoints(bonus, AddReason.MINE)
		}
	}

	@EventHandler
	fun onEntityDamageByEntity(event: org.bukkit.event.entity.EntityDamageByEntityEvent) {
		val damage = event.finalDamage
		var attacker: Player? = null
		var receiver: Player? = null
		when (event.entity) {
			is Player -> receiver = event.entity as Player
		}

		when (val damager = event.damager) {
			is Player -> attacker = damager
			is Arrow -> if (damager.shooter is Player) attacker = damager.shooter as Player
		}

		if (attacker != null && receiver != null) {
			val stats = getStats(attacker)
			attacker.sendMessage(Component.text("对").color(TextColor.color(255, 255, 255))
				.append(Component.text(receiver.name).color(TextColor.color(255, 0, 0)))
				.append(Component.text("造成"))
				.append(Component.text(damage.toInt().toString()).color(TextColor.color(0, 255, 0))).append(
				Component.text("点伤害")))
			stats.addDamage(damage.toInt())
		}
	}
}

fun Double.floor() = floor(this).toInt()