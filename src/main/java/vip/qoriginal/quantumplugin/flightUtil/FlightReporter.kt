package vip.qoriginal.quantumplugin.flightUtil

import com.google.common.base.Optional
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.jetbrains.kotlin.backend.common.push
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.QuantumPlugin
import vip.qoriginal.quantumplugin.Request

data class ReportObject(
	val name: String,
	val x: Double,
	val z: Double,
	val speedKmh: Float,
	val destination: String
)
object FlightReportScheduler {

	fun start() {
		Bukkit.getScheduler().runTaskTimer(
			QuantumPlugin.getInstance(),
			Runnable {
				FlightReporter.collectAndDispatch()
			},
			20L,
			40L
		)
	}
}

object FlightReporter {

	private val authHeader = hashMapOf<String, String>(
		"auth" to Config.API_SECRET
	)

	private val ioScope =
		CoroutineScope(SupervisorJob() + Dispatchers.IO)

	fun collectAndDispatch() {
		val snapshot = collectSnapshot()
		if (snapshot.isEmpty()) return

		ioScope.launch {
			report(snapshot)
		}
	}

	private fun collectSnapshot(): List<ReportObject> {
		val result = ArrayList<ReportObject>(FlightGUI.speedStates.size)

		for ((uuid, state) in FlightGUI.speedStates) {
			val player = Bukkit.getPlayer(uuid) ?: continue
			val flightInfo = Flight.players[uuid] ?: continue

			result.add(
				ReportObject(
					name = player.name,
					x = state.lastX,
					z = state.lastZ,
					speedKmh = (state.smoothedSpeedMs * 3.6).toFloat(),
					destination = flightInfo.flightDestination.display
				)
			)
		}

		return result
	}
	private fun report(data: List<ReportObject>) {
		val json = Gson().toJson(data)
		Request.sendPostRequest("${Config.API_ENDPOINT}/flight/upload", json,
			Optional.of(authHeader) as java.util.Optional<Map<String?, String?>?>?
		)
	}
}
