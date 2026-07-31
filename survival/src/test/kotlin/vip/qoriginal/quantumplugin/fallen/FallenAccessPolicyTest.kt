package vip.qoriginal.quantumplugin.fallen

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallenAccessPolicyTest {
	@Test
	fun `event opens exactly at 2pm Asia Shanghai on August first`() {
		val before = LocalDateTime.of(2026, 8, 1, 13, 59, 59)
			.atZone(FallenAccessPolicy.eventZone)
			.toInstant()
		val exact = LocalDateTime.of(2026, 8, 1, 14, 0)
			.atZone(FallenAccessPolicy.eventZone)
			.toInstant()

		assertFalse(FallenAccessPolicy.hasEventStarted(before))
		assertTrue(FallenAccessPolicy.hasEventStarted(exact))
		assertEquals(
			LocalDateTime.of(2026, 8, 1, 6, 0).toInstant(ZoneOffset.UTC),
			FallenAccessPolicy.eventStartsAt
		)
	}

	@Test
	fun `curfew includes 1am and excludes 7am in event timezone`() {
		assertFalse(isCurfewAt(FallenPhase.ACTIVE, 0, 59))
		assertTrue(isCurfewAt(FallenPhase.ACTIVE, 1, 0))
		assertTrue(isCurfewAt(FallenPhase.ACTIVE, 6, 59))
		assertFalse(isCurfewAt(FallenPhase.ACTIVE, 7, 0))
	}

	@Test
	fun `curfew covers every gameplay phase`() {
		assertTrue(isCurfewAt(FallenPhase.DEPLOYMENT, 3, 0))
		assertTrue(isCurfewAt(FallenPhase.ACTIVE, 3, 0))
		assertTrue(isCurfewAt(FallenPhase.OVERTIME, 3, 0))
	}

	@Test
	fun `only gameplay phases can mutate activity results`() {
		assertFalse(FallenAccessPolicy.isEventInProgress(FallenPhase.IDLE))
		assertTrue(FallenAccessPolicy.isEventInProgress(FallenPhase.DEPLOYMENT))
		assertTrue(FallenAccessPolicy.isEventInProgress(FallenPhase.ACTIVE))
		assertTrue(FallenAccessPolicy.isEventInProgress(FallenPhase.OVERTIME))
		assertFalse(FallenAccessPolicy.isEventInProgress(FallenPhase.ENDED))
	}

	@Test
	fun `curfew does not affect before or after event`() {
		assertFalse(isCurfewAt(FallenPhase.IDLE, 3, 0))
		assertFalse(isCurfewAt(FallenPhase.ENDED, 3, 0))
	}

	private fun isCurfewAt(phase: FallenPhase, hour: Int, minute: Int): Boolean {
		val instant = LocalDateTime.of(2026, 8, 2, hour, minute)
			.atZone(FallenAccessPolicy.eventZone)
			.toInstant()
		return FallenAccessPolicy.isCurfew(phase, instant)
	}
}
