package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals

class FallenUpgradeRulesTest {
	@Test
	fun `nodes unlock at start then twelve and twenty four hours after deployment`() {
		val start = 1_000_000L
		val deployment = 2 * 60 * 60 * 1000L
		val nodeTwoAt = start + deployment + 12 * 60 * 60 * 1000L
		val nodeThreeAt = start + deployment + 24 * 60 * 60 * 1000L

		assertEquals(0, FallenUpgradeRules.unlockedNode(start, deployment, start - 1))
		assertEquals(1, FallenUpgradeRules.unlockedNode(start, deployment, start))
		assertEquals(1, FallenUpgradeRules.unlockedNode(start, deployment, nodeTwoAt - 1))
		assertEquals(2, FallenUpgradeRules.unlockedNode(start, deployment, nodeTwoAt))
		assertEquals(2, FallenUpgradeRules.unlockedNode(start, deployment, nodeThreeAt - 1))
		assertEquals(3, FallenUpgradeRules.unlockedNode(start, deployment, nodeThreeAt))
	}

	@Test
	fun `engineer and mobility values match designed nodes`() {
		assertEquals(4_000L, FallenUpgradeRules.B_CAPTURE_MILLIS)
		assertEquals(5_000L, FallenUpgradeRules.B_STATION_DISRUPT_MILLIS)
		assertEquals(0.10, FallenUpgradeRules.C_MOVEMENT_SPEED_BONUS)
		assertEquals(0.80f, FallenUpgradeRules.C_EXHAUSTION_MULTIPLIER)
		assertEquals(35.0, FallenUpgradeRules.C_PRECISE_REVEAL_RADIUS)
	}
}
