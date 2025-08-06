package vip.qoriginal.quantumplugin.combatZone

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent

class RestrictZones : Listener{
	val MIN_X = -6500
	val MAX_X = 1000
	val MIN_Z = -1000
	val MAX_Z = 5000

	@EventHandler
	fun onPlayerMove(event: org.bukkit.event.player.PlayerMoveEvent) {
		val player = event.player
		val from = event.from
		val to = event.to

		if (to == null) return
		if ("world" != to.world.name) {
			player.teleport(Location(Bukkit.getWorld("world"), 3.0, 235.0, 0.0))
		}

		val x = to.x
		val z = to.z
		var outOfBounds = false

		var newX = x
		var newZ = z

		if (x < MIN_X) {
			newX = MIN_X + 0.5
			outOfBounds = true
		} else if (x > MAX_X) {
			newX = MAX_X - 0.5
			outOfBounds = true
		}

		if (z < MIN_Z) {
			newZ = MIN_Z + 0.5
			outOfBounds = true
		} else if (z > MAX_Z) {
			newZ = MAX_Z - 0.5
			outOfBounds = true
		}

		if (outOfBounds) {
			val corrected = Location(to.world, newX, to.y, newZ, to.yaw, to.pitch)
			player.teleport(corrected)
			player.sendMessage("你已到达边界")
		}
	}
	@EventHandler
	fun onPlayerTeleport(event: PlayerTeleportEvent) {
		val player = event.player
		val from = event.from
		val to = event.to ?: return

		val fromWorld = from.world
		val toWorld = to.world

		if (toWorld.name != "world") {
			event.isCancelled = true
			player.sendMessage("§c你不能传送到其他世界！")
		}
	}

	@EventHandler
	fun onPlayerUsePortal(event: PlayerPortalEvent) {
		val player = event.player
		val toWorld = event.to?.world ?: return

		if (toWorld.name != "world") {
			event.isCancelled = true
			player.sendMessage("§c你不能进入其他世界！")
		}
	}
}