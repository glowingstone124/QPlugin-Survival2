package vip.qoriginal.quantumplugin.fallen

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object FallenAccessPolicy {
	val eventZone: ZoneId = ZoneId.of("Asia/Shanghai")

	private val curfewStartsAt = LocalTime.of(1, 0)
	private val curfewEndsAt = LocalTime.of(7, 0)

	fun isEventInProgress(phase: FallenPhase): Boolean {
		return phase == FallenPhase.DEPLOYMENT
			|| phase == FallenPhase.ACTIVE
			|| phase == FallenPhase.OVERTIME
	}

	fun isCurfew(
		phase: FallenPhase,
		now: Instant = Instant.now()
	): Boolean {
		if (!isEventInProgress(phase)) return false
		val localTime = now.atZone(eventZone).toLocalTime()
		return !localTime.isBefore(curfewStartsAt) && localTime.isBefore(curfewEndsAt)
	}
}
