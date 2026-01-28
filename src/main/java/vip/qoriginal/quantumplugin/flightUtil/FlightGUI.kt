package vip.qoriginal.quantumplugin.flightUtil

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.Damageable
import vip.qoriginal.quantumplugin.QuantumPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object FlightGUI {
	data class SpeedState(
		var lastX: Double,
		var lastY: Double,
		var lastZ: Double,
		var lastTimeMs: Long,
		var smoothedSpeedMs: Double = 0.0,
		var initialized: Boolean = false
	)

	val speedStates = ConcurrentHashMap<UUID, SpeedState>()

	private const val EMA_ALPHA = 0.25
	private const val MIN_DISTANCE = 0.001

	fun clearPlayer(player: Player) {
		speedStates.remove(player.uniqueId)
	}

	private fun updateSpeedKmh(player: Player): Double {
		val now = System.currentTimeMillis()
		val location = player.location

		val state = speedStates.computeIfAbsent(player.uniqueId) {
			SpeedState(
				lastX = location.x,
				lastY = location.y,
				lastZ = location.z,
				lastTimeMs = now,
				smoothedSpeedMs = 0.0,
				initialized = false
			)
		}

		val deltaMs = (now - state.lastTimeMs).coerceAtLeast(1L)
		val dx = location.x - state.lastX
		val dy = location.y - state.lastY
		val dz = location.z - state.lastZ
		val distance = sqrt(dx * dx + dy * dy + dz * dz)

		val instantSpeedMs = if (distance < MIN_DISTANCE) {
			0.0
		} else {
			distance / (deltaMs / 1000.0)
		}

		val smoothed = if (state.initialized) {
			EMA_ALPHA * instantSpeedMs + (1.0 - EMA_ALPHA) * state.smoothedSpeedMs
		} else {
			instantSpeedMs
		}

		state.lastX = location.x
		state.lastY = location.y
		state.lastZ = location.z
		state.lastTimeMs = now
		state.smoothedSpeedMs = smoothed
		state.initialized = true

		return smoothed * 3.6
	}

	fun render(player: Player, info: Flight.FlightInfo, speedKmh: Double) {
		val destination =
			if (info.flightDestination.id != "NONE") info.flightDestination.id else "未指定"

		val durability = getDurability(player)

		player.sendActionBar(
			constructComponents(speedKmh, destination, durability)
		)
	}

	fun startTicking() {
		Bukkit.getScheduler().runTaskTimer(
			QuantumPlugin.getInstance(),
			Runnable {
				for ((uuid, info) in Flight.players) {
					val player = Bukkit.getPlayer(uuid) ?: continue
					val speedKmh = updateSpeedKmh(player)
					if (!info.guiEnable) continue
					render(player, info, speedKmh)
				}
			},
			20L,
			5L
		)
	}

	fun getDurability(player: Player): Int? {
		val item = player.inventory.chestplate ?: return null
		if (item.type != Material.ELYTRA) return null
		val meta = item.itemMeta as? Damageable ?: return null
		return item.type.maxDurability - meta.damage
	}


	inline fun constructComponents(
		speed: Double,
		destination: String,
		durability: Int?
	): Component {
		val builder = Component.text()

		val speedText = String.format(Locale.US, "%.1f", speed)
		builder.append(Component.text("速度: ${speedText} km/h"))

		builder.append(Component.text(" | "))

		builder.append(Component.text("[$destination]"))

		durability?.let {
			builder.append(Component.text(" | "))
			builder.append(Component.text("耐久: $durability")).color(
				if (durability >= 200) NamedTextColor.GREEN else {
					NamedTextColor.GREEN
				}
			)
		}

		return builder.build()
	}

}
