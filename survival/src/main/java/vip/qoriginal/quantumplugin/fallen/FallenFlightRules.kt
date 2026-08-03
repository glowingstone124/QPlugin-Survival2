package vip.qoriginal.quantumplugin.fallen

internal object FallenFlightRules {
	const val INTERVAL_MILLIS = 30_000L
	const val MIN_NET_DISPLACEMENT = 300.0
	const val HOURLY_CAP = 300
	const val DAILY_CAP = 2_400

	fun qualifies(elapsedMillis: Long, netDisplacement: Double, discoveredNewChunk: Boolean, enteredEnemyRegion: Boolean): Boolean =
		elapsedMillis >= INTERVAL_MILLIS && netDisplacement >= MIN_NET_DISPLACEMENT
			&& (discoveredNewChunk || enteredEnemyRegion)

	fun allowedGrant(requested: Int, hourPoints: Int, dayPoints: Int): Int = minOf(
		requested.coerceAtLeast(0),
		(HOURLY_CAP - hourPoints).coerceAtLeast(0),
		(DAILY_CAP - dayPoints).coerceAtLeast(0)
	)
}
