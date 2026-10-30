package vip.qoriginal.modules.seasonal

import java.time.*
import java.time.format.DateTimeFormatter

object EventTiming {

	private val formatter = DateTimeFormatter.ofPattern("yyyy/M/d'T'HH:mm:ss")

	private val eventMap = mapOf(
		Events.NEWYEAR_2026 to "2025/12/31T00:00:00-2026/03/01T00:00:00"
	)

	fun isEventActive(
		event: Events,
		zone: ZoneId = ZoneId.of("Asia/Shanghai"),
		now: Instant = Instant.now()
	): Boolean {
		val raw = eventMap[event] ?: return false

		val (startStr, endStr) = raw.split("-").takeIf { it.size == 2 } ?: return false

		val start = try {
			LocalDateTime.parse(startStr, formatter).atZone(zone)
		} catch (e: Exception) {
			return false
		}

		val end = try {
			LocalDateTime.parse(endStr, formatter).atZone(zone)
		} catch (e: Exception) {
			return false
		}

		val instant = now.atZone(zone)

		return !instant.isBefore(start) && !instant.isAfter(end)
	}
}

enum class Events {
	NEWYEAR_2026
}
