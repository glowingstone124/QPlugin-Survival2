package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FallenItemAnomalyTest {
	@Test
	fun `anomaly assignment is deterministic and uncommon`() {
		val assignments = (0 until 1_024).map { FallenItemAnomaly.variant("refresh-key-$it", 16) }
		val marked = assignments.filterNotNull()
		assertTrue(marked.size in 35..100)
		assertTrue(marked.all { it in 0 until FallenItemAnomaly.VARIANT_COUNT })
		val selected = assignments.indexOfFirst { it != null }
		assertTrue(selected >= 0)
		assertEquals(assignments[selected], FallenItemAnomaly.variant("refresh-key-$selected", 16))
		assertNotNull(assignments[selected])
	}

	@Test
	fun `invalid probability is rejected`() {
		assertFailsWith<IllegalArgumentException> { FallenItemAnomaly.variant("x", 0) }
	}
}
