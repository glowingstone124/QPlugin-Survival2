package vip.qoriginal.quantumplugin.combatZone

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.scoreboard.Scoreboard
import vip.qoriginal.quantumplugin.combatZone.CombatPoint.playerStats
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.centerLocation
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneMainCity
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneSpawn
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneTinCity
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.PlayerStats
import vip.qoriginal.quantumplugin.combatZone.RestrictZones.Companion.ArenaLoc1
import vip.qoriginal.quantumplugin.combatZone.Utils.getHorizontalDistance
import vip.qoriginal.quantumplugin.combatZone.Utils.isInZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import vip.qoriginal.quantumplugin.combatZone.Utils.updatePlayerHealth

object CombatPoint {
	val playerStats = ConcurrentHashMap<UUID, PlayerStats>()
}

class CombatPoints : Listener {
	private val gson = Gson()
	companion object {
		val centerLocation = Location(ArenaLoc1.world, -2140.0, 0.0, 1150.0);
		val hotZoneTinCity = HotZone(
			"热区-锡城",
			Location(ArenaLoc1.world, -4191.0, 0.0, 1537.0),
			Location(ArenaLoc1.world, -3761.0, 0.0, 2015.0)
		)
		val hotZoneMainCity = HotZone(
			"热区-主城",
			Location(ArenaLoc1.world, -2655.0, 0.0, 1455.0),
			Location(ArenaLoc1.world, -1441.0, 0.0, 641.0)
		)
		val hotZoneSpawn = HotZone(
			"热区-出生点",
			Location(ArenaLoc1.world, -225.0, 0.0, -223.0),
			Location(ArenaLoc1.world, 527.0, 0.0, 237.0)
		)
	}

	data class HotZone(
		val name: String,
		val LeftTop: Location,
		val RightBottom: Location
	)

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
		var damageDealt: Int = 0,
		var scoreboard: Scoreboard? = null
	) {
		val scoreBoardManager = ScoreboardManager()
		fun addPoints(amount: Int, reason: AddReason, loc: Location) {
			val multiplier = if (reason == AddReason.SELL) 1.0 else getLocationMultiplier(loc)
			points += (amount * multiplier).floor()
			handlePointsEvent { player ->
				updatePlayerHealth(player, points)
				scoreBoardManager.updateScoreboard(player, points)
			}
		}

		fun minusPoints(amount: Int, reason: RemoveReason) {
			points -= amount
			handlePointsEvent { player ->
				updatePlayerHealth(player, points)
				scoreBoardManager.updateScoreboard(player, points)
			}
		}

		fun addDamage(amount: Int, loc: Location) {
			damageDealt += amount
			addPoints((amount * 0.5).floor(), AddReason.DAMAGE, loc)
		}

		fun addKill(loc: Location) {
			kills++
			addPoints(50, AddReason.KILL, loc)
		}

		private fun handlePointsEvent(action: (Player) -> Unit) {
			val uuid = playerStats.entries
				.firstOrNull { it.value == this }
				?.key
				?: return

			val player = Bukkit.getPlayer(uuid) ?: return
			action(player)
		}
	}

	fun getTopKiller(): Pair<UUID, PlayerStats>? {
		val entry = CombatPoint.playerStats.maxByOrNull { it.value.kills }
		if (entry == null || entry.value.kills < 3) {
			return null
		}
		return Pair(entry.key, entry.value)
	}

	fun serialize(): String {
		val mapToSave = playerStats.mapKeys { it.key.toString() }
		return gson.toJson(mapToSave)
	}

	fun deserialize(json: String) {
		val type = object : TypeToken<Map<String, CombatPoints.PlayerStats>>() {}.type
		val loadedMap: Map<String, PlayerStats> = gson.fromJson(json, type)
		playerStats.clear()
		playerStats.putAll(loadedMap.mapKeys { UUID.fromString(it.key) })
	}

	fun getStats(player: Player) = playerStats.getOrPut(player.uniqueId) { PlayerStats() }

	@EventHandler
	fun onPlayerDeath(event: PlayerDeathEvent) {
		val dead = event.entity
		val killer = event.entity.killer

		if (dead.uniqueId == GUI.currentKillLeader?.uniqueId) {
			Utils.broadcast(Utils.prependBroadCast(Component.text("击杀王已被清除").color(NamedTextColor.DARK_RED)))
		}

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

fun getLocationMultiplier(loc: Location): Double {
	if (isInZone(loc, hotZoneMainCity.LeftTop, hotZoneMainCity.RightBottom)) {
		return 1.5
	}
	if (isInZone(loc, hotZoneTinCity.LeftTop, hotZoneTinCity.RightBottom) || isInZone(
			loc,
			hotZoneSpawn.LeftTop,
			hotZoneSpawn.RightBottom
		)
	) {
		return 1.3
	}
	val distance = getHorizontalDistance(loc, centerLocation)
	return if (distance <= 700) {
		1.0
	} else if (700 < distance && distance < 1200) {
		0.8
	} else if (distance in 1200.0..<1600.0) {
		0.6
	} else {
		0.25
	}
}

fun Double.floor() = floor(this).toInt()