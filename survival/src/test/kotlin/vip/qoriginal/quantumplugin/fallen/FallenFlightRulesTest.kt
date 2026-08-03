package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallenFlightRulesTest {
	@Test
	fun `flight requires a complete displaced exploration segment`() {
		assertFalse(FallenFlightRules.qualifies(29_999L, 500.0, true, false))
		assertFalse(FallenFlightRules.qualifies(30_000L, 299.99, true, false))
		assertFalse(FallenFlightRules.qualifies(30_000L, 500.0, false, false))
		assertTrue(FallenFlightRules.qualifies(30_000L, 300.0, true, false))
		assertTrue(FallenFlightRules.qualifies(30_000L, 300.0, false, true))
	}

	@Test
	fun `hourly and daily caps trim rewards`() {
		assertEquals(20, FallenFlightRules.allowedGrant(20, 0, 0))
		assertEquals(5, FallenFlightRules.allowedGrant(20, 295, 1_000))
		assertEquals(0, FallenFlightRules.allowedGrant(20, 300, 1_000))
		assertEquals(3, FallenFlightRules.allowedGrant(20, 100, 2_397))
	}
}
