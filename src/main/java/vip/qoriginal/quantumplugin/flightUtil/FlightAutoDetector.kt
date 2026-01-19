package vip.qoriginal.quantumplugin.flightUtil

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FlightAutoDetector(
	private val plugin: JavaPlugin,
	private val flight: Flight
) {

	private val airborneSince: MutableMap<UUID, Long> = ConcurrentHashMap()
	private val THRESHOLD_MS = 5_000L

	fun start() {
		Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
			checkPlayers()
		}, 20L, 20L)
	}

	private fun checkPlayers() {
		for (player in Bukkit.getOnlinePlayers()) {
			if (shouldTrack(player)) {
				val now = System.currentTimeMillis()
				val start = airborneSince.computeIfAbsent(player.uniqueId) { now }

				if (now - start >= THRESHOLD_MS) {
					if (!flight.isFlightEnabled(player)) {
						Flight.players[player.uniqueId] =
							Flight.FlightInfo(FlightDestination.NONE)
						player.sendMessage(
							Component.text("已检测到持续飞行，飞行仪表已自动启用。").color(NamedTextColor.GREEN)
						)
					}
				}
			} else {
				airborneSince.remove(player.uniqueId)
			}
		}
	}
	fun getFlightMinutes(player: Player): Long {
		val start = airborneSince[player.uniqueId] ?: return 0
		val elapsedMs = System.currentTimeMillis() - start
		return elapsedMs / 1000 / 60
	}
	private fun shouldTrack(player: Player): Boolean {
		if (player.isDead) return false
		if (player.isInsideVehicle) return false

		if (isOnSolidGround(player)) return false

		return player.isGliding || player.velocity.lengthSquared() > 0.15
	}

	private fun isOnSolidGround(player: Player): Boolean {
		val loc = player.location
		val below = loc.clone().add(0.0, -0.1, 0.0)
		return below.block.type.isSolid
	}

}
