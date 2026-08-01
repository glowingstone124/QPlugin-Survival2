package vip.qoriginal.quantumplugin.fallen

import org.bukkit.entity.EntityType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallenDestructiveEntityPolicyTest {
	@Test
	fun `large scale destructive creatures are restricted`() {
		assertTrue(FallenDestructiveEntityPolicy.isRestricted(EntityType.WITHER))
		assertTrue(FallenDestructiveEntityPolicy.isRestricted(EntityType.ENDER_DRAGON))
		assertFalse(FallenDestructiveEntityPolicy.isRestricted(EntityType.ZOMBIE))
	}
}
