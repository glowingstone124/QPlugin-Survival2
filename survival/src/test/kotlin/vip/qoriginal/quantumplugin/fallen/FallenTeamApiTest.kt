package vip.qoriginal.quantumplugin.fallen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallenTeamApiTest {
	@Test
	fun `accepts QAPI finalized selection response`() {
		val result = FallenTeamApi.parseLookupResponse(
			"""{"selected":true,"username":"alex","team":"C","expectedTeam":"A","finalized":true,"selectedAt":1234,"assignedAt":5678}"""
		)

		assertTrue(result.responseValid)
		assertEquals(FallenTeam.C, result.finalizedTeam)
	}

	@Test
	fun `accepts valid responses without a finalized team`() {
		val unselected = FallenTeamApi.parseLookupResponse("""{"selected":false}""")
		val awaitingFinalization = FallenTeamApi.parseLookupResponse(
			"""{"selected":true,"team":"B","expectedTeam":"B","finalized":false,"selectedAt":1234}"""
		)

		assertTrue(unselected.responseValid)
		assertNull(unselected.finalizedTeam)
		assertTrue(awaitingFinalization.responseValid)
		assertNull(awaitingFinalization.finalizedTeam)
	}

	@Test
	fun `rejects missing or malformed QAPI responses`() {
		assertFalse(FallenTeamApi.parseLookupResponse(null).responseValid)
		assertFalse(FallenTeamApi.parseLookupResponse("").responseValid)
		assertFalse(FallenTeamApi.parseLookupResponse("{}").responseValid)
		assertFalse(FallenTeamApi.parseLookupResponse("not-json").responseValid)
		assertFalse(FallenTeamApi.parseLookupResponse("""{"selected":true,"finalized":true,"team":"D"}""").responseValid)
	}
}
