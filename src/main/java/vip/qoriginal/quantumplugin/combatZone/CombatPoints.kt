package vip.qoriginal.quantumplugin.combatZone

import it.unimi.dsi.fastutil.doubles.DoubleList
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import vip.qoriginal.quantumplugin.combatZone.CombatPoint.playerStats
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.centerLocation
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.PlayerStats
import vip.qoriginal.quantumplugin.combatZone.RestrictZones.Companion.ArenaLoc1
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.sqrt

object CombatPoint {
	val playerStats = ConcurrentHashMap<UUID, PlayerStats>()
}

class CombatPoints : Listener {

	companion object {
		val centerLocation = Location(ArenaLoc1.world, -2140.0, 0.0, 1150.0);
	}

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

	data class PlayerStats(
		var points: Int = 0,
		var kills: Int = 0,
		var deaths: Int = 0,
		var damageDealt: Int = 0
	) {
		fun addPoints(amount: Int, reason: AddReason, loc: Location) {
			points += (amount * getLocationMultiplier(loc)).floor()
		}

		fun minusPoints(amount: Int, reason: RemoveReason) {
			points -= amount
		}

		fun addDamage(amount: Int, loc: Location) {
			damageDealt += amount
			addPoints((amount * 0.5).floor(), AddReason.DAMAGE, loc)
		}

		fun addKill(loc: Location) {
			kills++
			addPoints(50, AddReason.KILL, loc)
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
			killerStats.addPoints(stolenPoints, AddReason.KILL, killer.location)
			killerStats.addKill(killer.location)

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
			stats.addPoints(bonus, AddReason.MINE, player.location)
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
			attacker.sendMessage(
				Component.text("对").color(TextColor.color(255, 255, 255))
					.append(Component.text(receiver.name).color(TextColor.color(255, 0, 0)))
					.append(Component.text("造成"))
					.append(Component.text(damage.toInt().toString()).color(TextColor.color(0, 255, 0))).append(
						Component.text("点伤害")
					)
			)
			stats.addDamage(damage.toInt(), attacker.location)
		}
	}
}
fun getLocationMultiplier(loc: Location) : Double{
	val distance =  getHorizontalDistance(loc, centerLocation)
	return if (distance <= 700) {
		1.0
	} else if(700 < distance && distance < 1200){
		0.8
	} else if(distance in 1200.0..<1600.0){
		0.6
	} else {
		0.25
	}
}
fun Double.floor() = floor(this).toInt()
fun getHorizontalDistance(loc1: Location, loc2: Location): Double {
	val dx = loc1.x - loc2.x
	val dz = loc1.z - loc2.z
	return sqrt(dx * dx + dz * dz)
}