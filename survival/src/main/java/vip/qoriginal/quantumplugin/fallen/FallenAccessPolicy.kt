package vip.qoriginal.quantumplugin.fallen

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object FallenAccessPolicy {
	const val eventStartDisplay = "2026-10-01 14:00（Asia/Shanghai）"
	val eventZone: ZoneId = ZoneId.of("Asia/Shanghai")
	val eventStartsAt: Instant = LocalDateTime.of(2026, 10, 1, 14, 0)
		.atZone(eventZone)
		.toInstant()
	private val curfewStartsAt = LocalTime.of(1, 0)
	private val curfewEndsAt = LocalTime.of(7, 0)

	fun hasEventStarted(now: Instant = Instant.now()): Boolean {
		return !now.isBefore(eventStartsAt)
	}

	fun isEventInProgress(phase: FallenPhase): Boolean {
		return phase == FallenPhase.DEPLOYMENT
			|| phase == FallenPhase.ACTIVE
			|| phase == FallenPhase.OVERTIME
	}

	fun isCurfew(phase: FallenPhase, now: Instant = Instant.now()): Boolean {
		if (!isEventInProgress(phase)) return false
		val localTime = now.atZone(eventZone).toLocalTime()
		return !localTime.isBefore(curfewStartsAt) && localTime.isBefore(curfewEndsAt)
	}
}
