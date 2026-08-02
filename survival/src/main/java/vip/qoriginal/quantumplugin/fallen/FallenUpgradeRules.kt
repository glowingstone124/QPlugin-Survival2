package vip.qoriginal.quantumplugin.fallen

enum class FallenUpgradePath(val displayName: String) {
	A("A · 生存"),
	B("B · 工程"),
	C("C · 机动");

	companion object {
		fun parse(value: String): FallenUpgradePath = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
			?: throw IllegalArgumentException("未知升级路径: $value（可用 A、B、C）")
	}
}

internal object FallenUpgradeRules {
	const val NODE_TWO_AFTER_DEPLOYMENT_MILLIS = 12 * 60 * 60 * 1000L
	const val NODE_THREE_AFTER_DEPLOYMENT_MILLIS = 24 * 60 * 60 * 1000L
	const val A_BONUS_HEALTH = 4.0
	const val C_MOVEMENT_SPEED_BONUS = 0.10
	const val C_EXHAUSTION_MULTIPLIER = 0.80f
	const val C_PRECISE_REVEAL_RADIUS = 35.0
	const val DEFAULT_PRECISE_REVEAL_RADIUS = 20.0
	const val B_CAPTURE_MILLIS = 4_000L
	const val B_STATION_DISRUPT_MILLIS = 5_000L
	const val TNT_CAP = 64
	const val TNT_REFILL_AMOUNT = 8
	const val TNT_REFILL_MILLIS = 60_000L
	const val HEALING_POTION_CAP = 3
	const val HEALING_POTION_REFILL_MILLIS = 60_000L
	const val FIREWORK_CAP = 64
	const val FIREWORK_REFILL_AMOUNT = 8
	const val FIREWORK_REFILL_MILLIS = 20_000L

	fun unlockedNode(startedAtMillis: Long, deploymentMillis: Long, nowMillis: Long): Int {
		if (startedAtMillis <= 0L || nowMillis < startedAtMillis) return 0
		val deploymentEndedAt = startedAtMillis + deploymentMillis
		return when {
			nowMillis >= deploymentEndedAt + NODE_THREE_AFTER_DEPLOYMENT_MILLIS -> 3
			nowMillis >= deploymentEndedAt + NODE_TWO_AFTER_DEPLOYMENT_MILLIS -> 2
			else -> 1
		}
	}
}
