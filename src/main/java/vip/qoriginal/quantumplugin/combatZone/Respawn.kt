package vip.qoriginal.quantumplugin.combatZone

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerRespawnEvent
import vip.qoriginal.quantumplugin.combatZone.Utils.isInZone
import java.util.Random


class Respawn : Listener{

	private fun isInArena(loc: Location): Boolean {
		return isInZone(loc, RestrictZones.ArenaLoc1, RestrictZones.ArenaLoc2)
	}

	private fun getRandomSpawnLocation(): Location {
		val rand: Random = Random()
		val minX: Double = RestrictZones.ArenaLoc1.x.coerceAtMost(RestrictZones.ArenaLoc2.x)
		val maxX: Double = RestrictZones.ArenaLoc1.x.coerceAtLeast(RestrictZones.ArenaLoc2.x)
		val minZ: Double = RestrictZones.ArenaLoc1.z.coerceAtMost(RestrictZones.ArenaLoc2.z)
		val maxZ: Double = RestrictZones.ArenaLoc1.z.coerceAtLeast(RestrictZones.ArenaLoc2.z)

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