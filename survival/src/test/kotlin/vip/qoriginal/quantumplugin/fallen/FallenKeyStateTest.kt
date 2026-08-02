package vip.qoriginal.quantumplugin.fallen

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallenKeyStateTest {
	@Test
	fun `item keys can only be placed destroyed or start self destruction`() {
		assertTrue(FallenKeyState.ITEM.canTransitionTo(FallenKeyState.PLACED))
		assertTrue(FallenKeyState.ITEM.canTransitionTo(FallenKeyState.SELF_DESTRUCTING))
		assertTrue(FallenKeyState.ITEM.canTransitionTo(FallenKeyState.DESTROYED))
	}

	@Test
	fun `placed keys must be captured before self destruction`() {
		assertTrue(FallenKeyState.PLACED.canTransitionTo(FallenKeyState.ITEM))
		assertFalse(FallenKeyState.PLACED.canTransitionTo(FallenKeyState.SELF_DESTRUCTING))
	}

	@Test
	fun `self destruction cannot be cancelled by placing the key`() {
		assertFalse(FallenKeyState.SELF_DESTRUCTING.canTransitionTo(FallenKeyState.ITEM))
		assertFalse(FallenKeyState.SELF_DESTRUCTING.canTransitionTo(FallenKeyState.PLACED))
		assertTrue(FallenKeyState.SELF_DESTRUCTING.canTransitionTo(FallenKeyState.DESTROYED))
	}

	@Test
	fun `destroyed keys are terminal`() {
		assertFalse(FallenKeyState.DESTROYED.canTransitionTo(FallenKeyState.ITEM))
		assertFalse(FallenKeyState.DESTROYED.canTransitionTo(FallenKeyState.PLACED))
		assertFalse(FallenKeyState.DESTROYED.canTransitionTo(FallenKeyState.SELF_DESTRUCTING))
	}

	@Test
	fun `refresh key stops counting during its own final ten minutes`() {
		val now = 1_000_000L
		val key = FallenKey(
			UUID.randomUUID(),
			FallenTeam.A,
			FallenTeam.A,
			FallenKeyState.ITEM,
			FallenKeyType.REFRESH,
			expiresAtMillis = now + FALLEN_REFRESH_KEY_EXPIRY_WARNING_MILLIS + 1L
		)

		assertTrue(key.isEffectiveForSurvival(now))
		assertFalse(key.isEffectiveForSurvival(now + 1L))
		assertFalse(key.isExpired(now))
		assertTrue(key.isExpired(key.expiresAtMillis))
	}

	@Test
	fun `destroyed keys never count for survival`() {
		val key = FallenKey(
			UUID.randomUUID(),
			FallenTeam.A,
			FallenTeam.A,
			FallenKeyState.DESTROYED,
			FallenKeyType.INITIAL
		)

		assertFalse(key.isEffectiveForSurvival(0L))
	}
}
