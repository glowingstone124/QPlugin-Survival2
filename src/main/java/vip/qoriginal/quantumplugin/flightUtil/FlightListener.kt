package vip.qoriginal.quantumplugin.flightUtil

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class FlightListener : Listener {

	@EventHandler
	fun onPlayerJoin(event: PlayerJoinEvent) {
		val player = event.player
		Flight.players.putIfAbsent(
			player.uniqueId,
			Flight.FlightInfo(
				flightDestination = FlightDestination.NONE
			)
		)
	}

	@EventHandler
	fun onPlayerQuit(event: PlayerQuitEvent) {
		Flight.players.remove(event.player.uniqueId)
		FlightGUI.clearPlayer(event.player)
	}
}
