package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallenNarrativeTest {
	@Test
	fun `cues follow effective game time`() {
		assertNull(FallenNarrative.latestDue(14 * 60 * 1000L, emptySet()))
		val first = FallenNarrative.latestDue(15 * 60 * 1000L, emptySet())
		assertEquals("narrative-archive-index", first?.cue?.key)
	}

	@Test
	fun `late installation emits only newest context and consumes backlog`() {
		val selection = FallenNarrative.latestDue(73 * 60 * 60 * 1000L, emptySet())
		assertEquals("narrative-s03-assimilation", selection?.cue?.key)
		assertEquals(5, selection?.consumedKeys?.size)
		assertTrue(selection?.consumedKeys?.contains("narrative-archive-index") == true)
	}

	@Test
	fun `announced cues are not repeated`() {
		val all = FallenNarrative.timedCues.mapTo(linkedSetOf()) { it.key }
		assertNull(FallenNarrative.latestDue(Long.MAX_VALUE, all))
	}
}
