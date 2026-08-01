package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FallenScoreRulesTest {
	@Test
	fun `full release values match the published rules`() {
		assertEquals(2, FallenScoreRules.DAMAGE_SCORE_PER_POINT)
		assertEquals(50, FallenScoreRules.DAMAGE_SCORE_CAP_PER_WINDOW)
		assertEquals(120, FallenScoreRules.KILL_SCORE)
		assertEquals(50, FallenScoreRules.ASSIST_SCORE)
		assertEquals(25, FallenScoreRules.PLACED_KEY_SCORE)
		assertEquals(250, FallenScoreRules.CAPTURE_SCORE)
		assertEquals(800, FallenScoreRules.CONVERSION_SCORE)
		assertEquals(450, FallenScoreRules.SELF_DESTRUCT_SCORE)
		assertEquals(20, FallenScoreRules.DEATH_LOSS)
		assertEquals(40, FallenScoreRules.KEY_CARRIER_DEATH_LOSS)
	}

	@Test
	fun `normal competition increases the total score`() {
		assertTrue(FallenScoreRules.KILL_SCORE - FallenScoreRules.DEATH_LOSS > 0)
		assertTrue(FallenScoreRules.CAPTURE_SCORE - FallenScoreRules.CAPTURE_LOSS > 0)
		assertTrue(FallenScoreRules.CONVERSION_SCORE - FallenScoreRules.CONVERSION_LOSS > 0)
		assertTrue(FallenScoreRules.SELF_DESTRUCT_SCORE - FallenScoreRules.SELF_DESTRUCT_LOSS > 0)
	}

	@Test
	fun `damage score uses the full release multiplier`() {
		assertEquals(15, FallenScoreRules.damageScore(7.5))
		assertEquals(0, FallenScoreRules.damageScore(-1.0))
	}
}
