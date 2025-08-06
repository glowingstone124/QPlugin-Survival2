package vip.qoriginal.quantumplugin.combatZone

import org.bukkit.Location

object Utils {
	fun isInZone(loc: Location, corner1: Location, corner2: Location): Boolean {
		val minX: Double = Math.min(corner1.getX(), corner2.getX())
		val maxX: Double = Math.max(corner1.getX(), corner2.getX())
		val minZ: Double = Math.min(corner1.getZ(), corner2.getZ())
		val maxZ: Double = Math.max(corner1.getZ(), corner2.getZ())

		return loc.x in minX..maxX && loc.z >= minZ && loc.z <= maxZ
	}
}