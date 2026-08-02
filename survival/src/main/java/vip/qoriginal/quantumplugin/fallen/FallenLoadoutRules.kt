package vip.qoriginal.quantumplugin.fallen

internal object FallenLoadoutRules {
	const val ELYTRA_COST = 400
	const val CHESTPLATE_REFUND = 200
	const val MAX_ELYTRA_PLAYERS_PER_TEAM = 2
	const val GEAR_SWITCH_COOLDOWN_MILLIS = 15 * 60 * 1000L

	fun remainingCooldown(availableAtMillis: Long, nowMillis: Long): Long =
		(availableAtMillis - nowMillis).coerceAtLeast(0L)

	fun canSelectElytra(currentElytraPlayers: Int): Boolean =
		currentElytraPlayers < MAX_ELYTRA_PLAYERS_PER_TEAM
}
