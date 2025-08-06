package vip.qoriginal.quantumplugin.combatZone

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.Random


class Respawn : Listener{

	private fun isInZone(loc: Location, corner1: Location, corner2: Location): Boolean {
		val minX: Double = Math.min(corner1.getX(), corner2.getX())
		val maxX: Double = Math.max(corner1.getX(), corner2.getX())
		val minZ: Double = Math.min(corner1.getZ(), corner2.getZ())
		val maxZ: Double = Math.max(corner1.getZ(), corner2.getZ())

		return loc.x in minX..maxX && loc.z >= minZ && loc.z <= maxZ
	}

	private fun isInArena(loc: Location): Boolean {
		return isInZone(loc, RestrictZones.ArenaLoc1, RestrictZones.ArenaLoc2)
	}

	private fun getRandomSpawnLocation(): Location {
		val rand: Random = Random()
		val minX: Double = Math.min(RestrictZones.ArenaLoc1.getX(), RestrictZones.ArenaLoc2.getX())
		val maxX: Double = Math.max(RestrictZones.ArenaLoc1.getX(), RestrictZones.ArenaLoc2.getX())
		val minZ: Double = Math.min(RestrictZones.ArenaLoc1.getZ(), RestrictZones.ArenaLoc2.getZ())
		val maxZ: Double = Math.max(RestrictZones.ArenaLoc1.getZ(), RestrictZones.ArenaLoc2.getZ())

		val x: Double = minX + (maxX - minX) * rand.nextDouble()
		val z: Double = minZ + (maxZ - minZ) * rand.nextDouble()
		val world: World = RestrictZones.ArenaLoc1.world

		return Location(world, x, (world.getHighestBlockYAt(x.toInt(), z.toInt()) + 1).toDouble(), z)
	}

	@EventHandler
	fun onPlayerRespawn(event: PlayerRespawnEvent) {

		val player = event.getPlayer()
		val spawnLoc = player.respawnLocation

		if (spawnLoc == null || !isInArena(spawnLoc)) {
			event.setRespawnLocation(getRandomSpawnLocation())
		}
	}
}