package vip.qoriginal.quantumplugin.metro

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ExperimentalAcceleration {

	private val allowBlockList = setOf(
		Material.WAXED_COPPER_BLOCK,
		Material.WAXED_COPPER_GRATE
	)

	companion object {
		private const val MAX_SPEED_400_KMH = 5.56
		private const val ACCELERATION_FACTOR = 1.03
		private val eAccMinecarts =
			ConcurrentHashMap.newKeySet<UUID>()
	}

	fun ensuresCondition(minecart: Minecart): Boolean {
		val blockCord = minecart.location.clone()
		return allowBlockList.contains(blockCord.add(Vector(0.0, -1.0, 0.0)).block.type)
	}

	fun startExperimentalAcceleration(minecart: Minecart) {
		eAccMinecarts.add(minecart.uniqueId)
	}

	fun endExperimentalAcceleration(minecart: Minecart) {
		eAccMinecarts.remove(minecart.uniqueId)
	}

	fun apply() {
		eAccMinecarts.forEach { uuid ->
			val minecart = Bukkit.getEntity(uuid) as? Minecart
			if (minecart == null || !minecart.isValid || minecart.isDead) {
				eAccMinecarts.remove(uuid)
				return@forEach
			}

			minecart.maxSpeed = MAX_SPEED_400_KMH
			val velocity = minecart.velocity
			val horizontal = Vector(
				velocity.x,
				0.0,
				velocity.z
			)
			val speed = horizontal.length()
			if (speed > 0.0 && speed < MAX_SPEED_400_KMH) {
				val targetSpeed = minOf(speed * ACCELERATION_FACTOR, MAX_SPEED_400_KMH)
				minecart.velocity = horizontal.multiply(targetSpeed / speed).setY(velocity.y)
			}
		}
	}
}
