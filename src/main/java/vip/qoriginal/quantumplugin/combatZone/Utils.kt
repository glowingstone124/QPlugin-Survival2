package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import kotlin.math.sqrt

object Utils {
	fun isInZone(loc: Location, corner1: Location, corner2: Location): Boolean {
		val minX: Double = Math.min(corner1.getX(), corner2.getX())
		val maxX: Double = Math.max(corner1.getX(), corner2.getX())
		val minZ: Double = Math.min(corner1.getZ(), corner2.getZ())
		val maxZ: Double = Math.max(corner1.getZ(), corner2.getZ())

		return loc.x in minX..maxX && loc.z >= minZ && loc.z <= maxZ
	}
	fun getHorizontalDistance(loc1: Location, loc2: Location): Double {
		val dx = loc1.x - loc2.x
		val dz = loc1.z - loc2.z
		return sqrt(dx * dx + dz * dz)
	}
	fun getCurrentZone(loc: Location, zones: List<CombatPoints.HotZone>): CombatPoints.HotZone? {
		for (zone in zones) {
			if (isInZone(loc, zone.LeftTop, zone.RightBottom)) {
				return zone
			}
		}
		return null
	}
	fun prependBroadCast(component: Component): Component {
		val prefix = Component.text("[奥林匹斯工业] ", NamedTextColor.YELLOW)
		return prefix.append(component)
	}
}