package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FallenFinaleRulesTest {
	@Test
	fun `chunk rings start below the player and contain no duplicates`() {
		assertEquals(listOf(FallenChunkOffset(0, 0)), FallenFinaleRules.chunkRing(0))
		for (radius in 1..12) {
			val ring = FallenFinaleRules.chunkRing(radius)
			assertEquals(radius * 8, ring.size)
			assertEquals(ring.size, ring.toSet().size)
			assertTrue(ring.all { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.z)) == radius })
		}
	}

	@Test
	fun `negative chunk ring is rejected`() {
		assertFailsWith<IllegalArgumentException> { FallenFinaleRules.chunkRing(-1) }
	}
}
