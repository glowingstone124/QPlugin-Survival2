package vip.qoriginal.quantumplugin.adventures

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bukkit.Location
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.Logger
import vip.qoriginal.quantumplugin.LoggerProvider
import vip.qoriginal.quantumplugin.QuantumPlugin.WORLD_MAIN
import java.util.UUID

class Zones {
	companion object {
		val logger = LoggerProvider.getLogger("AdventureZone")
		val trigger = Trigger()
		val ZONE_LIST = listOf(
			Zone(
				"Prometheus",
				Location(WORLD_MAIN, -1590.0, 320.0, 779.0),
				Location(WORLD_MAIN, -1456.0, -64.0, 596.0),
				10_000L,
				{ player ->
					logger.debug("player entered Prometheus zone")
					CoroutineScope(Dispatchers.IO).launch {
						trigger.call(TriggerType.REIMU_AND_MARISA, player)
					}
				},
				{ player ->
					logger.debug("player left Prometheus zone")
				}
			),

			Zone(
				"FuIsland",
				Location(WORLD_MAIN, -11996.0, 320.0, 730.0),
				Location(WORLD_MAIN, -11627.0, 320.0, 1042.0),
				10_000L,
				{ player ->
					logger.debug("player entered FuIsland")
					CoroutineScope(Dispatchers.IO).launch {
						trigger.call(TriggerType.ORIN)
					}
				},
				{ player ->
					logger.debug("player left FuIsland")
				}
			)
		)
	}

	data class Zone(
		val name: String,
		val corner1: Location,
		val corner2: Location,
		val cooldown: Long = 10_000L,
		val onEnter: (Player) -> Unit = {},
		val onExit: (Player) -> Unit = {}
	) {
		private val lastTriggerTimes: MutableMap<UUID, Long> = mutableMapOf()

		fun isInZone(player: Player): Boolean {
			val loc = player.location
			val minX = corner1.x.coerceAtMost(corner2.x)
			val maxX = corner1.x.coerceAtLeast(corner2.x)
			val minZ = corner1.z.coerceAtMost(corner2.z)
			val maxZ = corner1.z.coerceAtLeast(corner2.z)
			return loc.world == corner1.world &&
					loc.x in minX..maxX && loc.z in minZ..maxZ

		}

		fun canTrigger(player: Player): Boolean {
			val now = System.currentTimeMillis()
			val lastTime = lastTriggerTimes[player.uniqueId] ?: 0L
			return now - lastTime >= cooldown
		}

		fun updateTriggerTime(player: Player) {
			lastTriggerTimes[player.uniqueId] = System.currentTimeMillis()
		}

		fun executeEnter(player: Player) {
			if (!player.scoreboardTags.contains("in$name") && canTrigger(player)) {
				onEnter(player)
				player.scoreboardTags.add("in$name")
				updateTriggerTime(player)
			}
		}

		fun executeExit(player: Player) {
			if (player.scoreboardTags.contains("in$name")) {
				onExit(player)
				player.scoreboardTags.remove("in$name")
			}
		}
	}
}