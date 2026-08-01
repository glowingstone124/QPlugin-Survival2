package vip.qoriginal.quantumplugin.fallen

internal object FallenScoreRules {
	const val DAMAGE_SCORE_PER_POINT = 2
	const val DAMAGE_SCORE_CAP_PER_WINDOW = 50
	const val KILL_SCORE = 120
	const val ASSIST_SCORE = 50
	const val PLACED_KEY_SCORE = 25
	const val CAPTURE_SCORE = 250
	const val CAPTURE_LOSS = 50
	const val CONVERSION_SCORE = 800
	const val CONVERSION_LOSS = 200
	const val SELF_DESTRUCT_SCORE = 450
	const val SELF_DESTRUCT_LOSS = 150
	const val DIAMOND_SCORE = 25
	const val EMERALD_SCORE = 30
	const val REDSTONE_SCORE = 8
	const val DEEPSLATE_COAL_SCORE = 12
	const val ANCIENT_DEBRIS_SCORE = 100
	const val ELYTRA_SCORE = 20
	const val DEATH_LOSS = 20
	const val KEY_CARRIER_DEATH_LOSS = 40

	fun damageScore(finalDamage: Double): Int =
		(finalDamage * DAMAGE_SCORE_PER_POINT).toInt().coerceAtLeast(0)
}
