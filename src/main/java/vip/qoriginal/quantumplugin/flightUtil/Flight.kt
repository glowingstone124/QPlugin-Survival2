package vip.qoriginal.quantumplugin.flightUtil

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.QuantumPlugin
import java.util.UUID

class Flight {

	companion object {
		val players: MutableMap<UUID, FlightInfo> = mutableMapOf()
	}

	fun setPlayerFlightGui(player: Player, param: Boolean) {
		val info = players[player.uniqueId] ?: return
		info.guiEnable = param
	}

	fun setPlayerFlightReport(player: Player, param: Boolean) {
		val info = players[player.uniqueId] ?: return
		info.reportEnable = param
	}

	fun setPlayerFlightDestination(player: Player, destination: String): Boolean {
		val info = players[player.uniqueId] ?: return false

		val dest = FlightDestination.entries
			.firstOrNull { it.id.equals(destination, ignoreCase = true) }
			?: return false

		info.flightDestination = dest
		return true
	}

	fun isFlightEnabled(player: Player): Boolean {
		return players.containsKey(player.uniqueId)
	}

	data class FlightInfo(
		var flightDestination: FlightDestination,
		var guiEnable: Boolean = true,
		var reportEnable: Boolean = true,
	)

	fun formatDestinationAsHumanReadable(dest: FlightDestination): Component {
		return Component.text("[${dest.id}]").color(NamedTextColor.GREEN)
			.append(Component.text(dest.display).color(NamedTextColor.BLUE))
			.append(Component.text("坐标：X:${dest.location.blockX} Y:${dest.location.blockY} Z:${dest.location.blockZ}").color(NamedTextColor.DARK_BLUE))
	}
}

enum class FlightDestination(
	val id: String,
	val display: String,
	val location: Location
) {
	XCA("XCA", "锡城机场" ,Location(QuantumPlugin.WORLD_MAIN, -3817.0, 0.0, 1449.0)),
	ZCA("ZCA", "主城机场", Location(QuantumPlugin.WORLD_MAIN, -1895.0, 0.0, 484.0)),
	FDA("FDA", "芙岛机场", Location(QuantumPlugin.WORLD_MAIN, -11760.0,0.0,808.0)),
	NONE("NONE", "未设置" ,Location(QuantumPlugin.WORLD_MAIN, 0.0, 0.0, 0.0)),
}
