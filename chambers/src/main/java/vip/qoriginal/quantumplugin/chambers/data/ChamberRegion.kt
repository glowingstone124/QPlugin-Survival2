package vip.qoriginal.quantumplugin.chambers.data

import org.bukkit.Location
import org.bukkit.World

data class ChamberRegion(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    fun contains(location: Location, instanceWorld: World, origin: Location): Boolean {
        val offsetX = origin.blockX
        val offsetY = origin.blockY
        val offsetZ = origin.blockZ
        return location.world.uid == instanceWorld.uid &&
            location.blockX in (offsetX + minX)..(offsetX + maxX) &&
            location.blockY in (offsetY + minY)..(offsetY + maxY) &&
            location.blockZ in (offsetZ + minZ)..(offsetZ + maxZ)
    }
}