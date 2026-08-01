package vip.qoriginal.quantumplugin.fallen

import org.bukkit.entity.EntityType

internal object FallenDestructiveEntityPolicy {
	private val restrictedTypes = setOf(
		EntityType.WITHER,
		EntityType.ENDER_DRAGON,
	)

	fun isRestricted(type: EntityType): Boolean = type in restrictedTypes
}
