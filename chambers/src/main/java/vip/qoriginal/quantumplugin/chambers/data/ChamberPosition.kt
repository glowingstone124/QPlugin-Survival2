package vip.qoriginal.quantumplugin.chambers.data

import org.bukkit.Location
import org.bukkit.World

data class ChamberPosition(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    fun inWorld(world: World): Location = Location(world, x, y, z, yaw, pitch)

    fun relativeTo(world: World, origin: Location): Location = Location(
	    world,
	    origin.x + x,
	    origin.y + y,
	    origin.z + z,
	    yaw,
	    pitch,
    )
}