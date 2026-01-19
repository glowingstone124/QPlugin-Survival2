package vip.qoriginal.quantumplugin.flightUtil

import net.kyori.adventure.text.Component
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
		var lastZ: Double,
		var smoothedSpeedMs: Double = 0.0,
		var initialized: Boolean = false
	)

	val speedStates = ConcurrentHashMap<UUID, SpeedState>()

	private const val EMA_ALPHA = 0.25
	private const val MIN_DISTANCE = 0.001

	fun getRealSpeedMs(player: Player, tps: Double): Double {
		val uuid = player.uniqueId
		val loc = player.location

		val state = speedStates.computeIfAbsent(uuid) {
			SpeedState(loc.x, loc.z)
		}

		if (!state.initialized) {
			state.lastX = loc.x
			state.lastZ = loc.z
			state.initialized = true
			return 0.0
		}

		val dx = loc.x - state.lastX
		val dz = loc.z - state.lastZ

		state.lastX = loc.x
		state.lastZ = loc.z

		val distSq = dx * dx + dz * dz
		if (distSq < MIN_DISTANCE) {
			state.smoothedSpeedMs *= (1 - EMA_ALPHA)
			return state.smoothedSpeedMs
		}

		val deltaTime = 1.0 / tps
		val speedMs = sqrt(distSq) / deltaTime

		state.smoothedSpeedMs =
			state.smoothedSpeedMs * (1 - EMA_ALPHA) + speedMs * EMA_ALPHA

		return state.smoothedSpeedMs
	}
	fun clearPlayer(player: Player) {
		speedStates.remove(player.uniqueId)
	}

	fun render(player: Player, info: Flight.FlightInfo, tps: Double) {
		val speedMs = getRealSpeedMs(player, tps)
		val speedKmh = speedMs * 3.6

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
				val tps = Bukkit.getTPS()[0].coerceIn(1.0, 20.0)

				for ((uuid, info) in Flight.players) {
					if (!info.guiEnable) continue
					val player = Bukkit.getPlayer(uuid) ?: continue
					render(player, info, tps)
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

		durability?.let{
			builder.append(Component.text(" | "))
			builder.append(Component.text("耐久: $durability"))
		}

		return builder.build()
	}

}