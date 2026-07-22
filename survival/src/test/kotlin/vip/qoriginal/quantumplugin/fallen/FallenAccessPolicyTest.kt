package vip.qoriginal.quantumplugin.fallen

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallenAccessPolicyTest {
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
