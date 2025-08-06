package vip.qoriginal.quantumplugin.combatZone

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class CombatPoints : Listener {
	val playerStats = ConcurrentHashMap<UUID, PlayerStats>()

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

		}
		fun minusPoints(amount: Int, reason: RemoveReason) {

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

	fun getStats(player: Player) = playerStats.getOrPut(player.uniqueId) { PlayerStats() }

}

fun Double.floor() = floor(this).toInt()