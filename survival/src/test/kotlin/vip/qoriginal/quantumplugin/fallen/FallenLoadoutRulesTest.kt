package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallenLoadoutRulesTest {
	@Test
	fun `gear prices cooldown and team cap match activity rules`() {
		assertEquals(400, FallenLoadoutRules.ELYTRA_COST)
		assertEquals(200, FallenLoadoutRules.CHESTPLATE_REFUND)
		assertEquals(15 * 60 * 1000L, FallenLoadoutRules.GEAR_SWITCH_COOLDOWN_MILLIS)
		assertTrue(FallenLoadoutRules.canSelectElytra(0))
		assertTrue(FallenLoadoutRules.canSelectElytra(1))
		assertFalse(FallenLoadoutRules.canSelectElytra(2))
	}

	@Test
	fun `cooldown reaches zero at availability boundary`() {
		assertEquals(1L, FallenLoadoutRules.remainingCooldown(1_001L, 1_000L))
		assertEquals(0L, FallenLoadoutRules.remainingCooldown(1_000L, 1_000L))
		assertEquals(0L, FallenLoadoutRules.remainingCooldown(999L, 1_000L))
	}
}
