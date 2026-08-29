package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import kotlin.math.roundToInt

data class PostDamageHealthState(
	val remainingHealth: Double,
	val remainingAbsorption: Double,
	val totalRemaining: Double,
	val maxHealth: Double,
	val healthRatio: Double
)

object FallenCombatIndicator {
	const val HEALTH_BAR_SEGMENTS = 10
	const val LONG_SHOT_DISTANCE = 35.0
	const val LOW_HEALTH_THRESHOLD = 6.0

	/**
	 * Calculates the remaining health and absorption after applying final damage according
	 * to standard Minecraft damage absorption mechanics (absorption takes damage first).
	 */
	fun calculatePostDamageState(
		currentHealth: Double,
		currentAbsorption: Double,
		maxHealth: Double,
		finalDamage: Double
	): PostDamageHealthState {
		val safeMaxHealth = if (maxHealth > 0.0) maxHealth else 20.0
		val safeDamage = finalDamage.coerceAtLeast(0.0)
		val absorptionDamage = minOf(currentAbsorption.coerceAtLeast(0.0), safeDamage)
		val remainingAbsorption = (currentAbsorption - absorptionDamage).coerceAtLeast(0.0)
		val healthDamage = safeDamage - absorptionDamage
		val remainingHealth = (currentHealth - healthDamage).coerceIn(0.0, safeMaxHealth)
		val totalRemaining = remainingHealth + remainingAbsorption
		val healthRatio = (remainingHealth / safeMaxHealth).coerceIn(0.0, 1.0)
		return PostDamageHealthState(
			remainingHealth = remainingHealth,
			remainingAbsorption = remainingAbsorption,
			totalRemaining = totalRemaining,
			maxHealth = safeMaxHealth,
			healthRatio = healthRatio
		)
	}

	/**
	 * Formats a heart visual bar for the actionbar indicator.
	 */
	fun renderHealthBar(ratio: Double): Component {
		val filled = (ratio * HEALTH_BAR_SEGMENTS).roundToInt().coerceIn(0, HEALTH_BAR_SEGMENTS)
		val empty = HEALTH_BAR_SEGMENTS - filled
		val color = when {
			ratio > 0.6 -> NamedTextColor.GREEN
			ratio > 0.25 -> NamedTextColor.YELLOW
			else -> NamedTextColor.RED
		}
		return Component.text("❤".repeat(filled), color)
			.append(Component.text("░".repeat(empty), NamedTextColor.DARK_GRAY))
	}

	/**
	 * Builds the actionbar component shown to an attacker upon successfully hitting an enemy player.
	 */
	fun formatHitActionBar(
		targetName: String,
		targetTeam: FallenTeam,
		finalDamage: Double,
		state: PostDamageHealthState,
		isCritical: Boolean = false,
		isKeyCarrier: Boolean = false,
		projectileDistance: Double? = null
	): Component {
		val damageText = "%.1f".format(finalDamage)
		val healthText = "%.1f".format(state.remainingHealth)
		val maxText = "%.0f".format(state.maxHealth)

		val damageComponent = if (isCritical) {
			Component.text(" -$damageText❤", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
		} else {
			Component.text(" -$damageText❤", NamedTextColor.RED)
		}

		var builder = Component.text("[", NamedTextColor.DARK_GRAY)
			.append(Component.text(targetTeam.name, targetTeam.color).decorate(TextDecoration.BOLD))
			.append(Component.text("] ", NamedTextColor.DARK_GRAY))
			.append(Component.text(targetName, targetTeam.color))
			.append(damageComponent)

		if (isCritical) {
			builder = builder.append(Component.text(" 💥", NamedTextColor.YELLOW))
		}

		val healthColor = when {
			state.healthRatio > 0.6 -> NamedTextColor.GREEN
			state.healthRatio > 0.25 -> NamedTextColor.YELLOW
			else -> NamedTextColor.RED
		}

		builder = builder.append(Component.space())
			.append(renderHealthBar(state.healthRatio))
			.append(Component.space())
			.append(Component.text("$healthText/$maxText❤", healthColor))

		if (state.remainingAbsorption > 0.0) {
			val absText = "%.1f".format(state.remainingAbsorption)
			builder = builder.append(Component.text(" (+$absText💛)", NamedTextColor.GOLD))
		}

		if (isKeyCarrier) {
			builder = builder.append(Component.text(" [🔑 密钥]", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
		}

		if (projectileDistance != null && projectileDistance >= 5.0) {
			val distInt = projectileDistance.roundToInt()
			if (projectileDistance >= LONG_SHOT_DISTANCE) {
				builder = builder.append(Component.text(" [🎯 ${distInt}m 远射!]", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
			} else {
				builder = builder.append(Component.text(" [${distInt}m]", NamedTextColor.AQUA))
			}
		}

		return builder
	}

	/**
	 * Builds the actionbar component shown to an attacker upon hitting a mob/non-player entity.
	 */
	fun formatMobHitActionBar(
		mobName: Component,
		finalDamage: Double,
		state: PostDamageHealthState,
		isCritical: Boolean = false,
		projectileDistance: Double? = null
	): Component {
		val damageText = "%.1f".format(finalDamage)
		val healthText = "%.1f".format(state.remainingHealth)
		val maxText = "%.0f".format(state.maxHealth)

		val damageComponent = if (isCritical) {
			Component.text(" -$damageText❤", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
		} else {
			Component.text(" -$damageText❤", NamedTextColor.RED)
		}

		var builder = Component.text("[", NamedTextColor.DARK_GRAY)
			.append(mobName.color(NamedTextColor.GRAY))
			.append(Component.text("] ", NamedTextColor.DARK_GRAY))
			.append(damageComponent)

		if (isCritical) {
			builder = builder.append(Component.text(" 💥", NamedTextColor.YELLOW))
		}

		val healthColor = when {
			state.healthRatio > 0.6 -> NamedTextColor.GREEN
			state.healthRatio > 0.25 -> NamedTextColor.YELLOW
			else -> NamedTextColor.RED
		}

		builder = builder.append(Component.space())
			.append(renderHealthBar(state.healthRatio))
			.append(Component.space())
			.append(Component.text("$healthText/$maxText❤", healthColor))

		if (state.remainingAbsorption > 0.0) {
			val absText = "%.1f".format(state.remainingAbsorption)
			builder = builder.append(Component.text(" (+$absText💛)", NamedTextColor.GOLD))
		}

		if (projectileDistance != null && projectileDistance >= 5.0) {
			val distInt = projectileDistance.roundToInt()
			if (projectileDistance >= LONG_SHOT_DISTANCE) {
				builder = builder.append(Component.text(" [🎯 ${distInt}m 远射!]", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
			} else {
				builder = builder.append(Component.text(" [${distInt}m]", NamedTextColor.AQUA))
			}
		}

		return builder
	}

	/**
	 * Builds the kill actionbar displayed to the killer (non-intrusive, replacing big titles).
	 */
	fun formatKillActionBar(victimName: String, victimTeam: FallenTeam, scoreReward: Int, isKeyCarrier: Boolean): Component {
		var builder = Component.text("⚔ 击杀 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
			.append(Component.text("[", NamedTextColor.DARK_GRAY))
			.append(Component.text(victimTeam.name, victimTeam.color).decorate(TextDecoration.BOLD))
			.append(Component.text("] ", NamedTextColor.DARK_GRAY))
			.append(Component.text(victimName, victimTeam.color).decorate(TextDecoration.BOLD))
			.append(Component.text(" (+$scoreReward 积分)", NamedTextColor.YELLOW))

		if (isKeyCarrier) {
			builder = builder.append(Component.text(" [🔑 击落密钥携带者!]", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
		}
		return builder
	}

	/**
	 * Builds the assist actionbar displayed to assist contributors.
	 */
	fun formatAssistActionBar(victimName: String, victimTeam: FallenTeam, scoreReward: Int): Component {
		return Component.text("助攻击杀 ", NamedTextColor.YELLOW)
			.append(Component.text("[", NamedTextColor.DARK_GRAY))
			.append(Component.text(victimTeam.name, victimTeam.color).decorate(TextDecoration.BOLD))
			.append(Component.text("] ", NamedTextColor.DARK_GRAY))
			.append(Component.text(victimName, victimTeam.color).decorate(TextDecoration.BOLD))
			.append(Component.text(" (+$scoreReward 积分)", NamedTextColor.GOLD))
	}

	/**
	 * Builds the low health warning actionbar.
	 */
	fun formatLowHealthWarning(remainingHealth: Double): Component {
		val hpText = "%.1f".format(remainingHealth)
		return Component.text("⚠ 危险：生命值过低 ($hpText❤)", NamedTextColor.RED).decorate(TextDecoration.BOLD)
	}

	/**
	 * Builds the friendly fire warning actionbar.
	 */
	fun formatFriendlyFireNotice(targetName: String, team: FallenTeam): Component {
		return Component.text("无法伤害同阵营成员 ", NamedTextColor.GRAY)
			.append(Component.text(targetName, team.color))
	}

	/**
	 * Formats a player's entry in the Tab player list with team tag and K/D/A stats.
	 */
	fun formatPlayerListName(
		playerName: String,
		team: FallenTeam?,
		kills: Int,
		deaths: Int,
		assists: Int,
		isSpectator: Boolean
	): Component {
		if (isSpectator || team == null) {
			return Component.text("[", NamedTextColor.DARK_GRAY)
				.append(Component.text("旁观", NamedTextColor.GRAY))
				.append(Component.text("] ", NamedTextColor.DARK_GRAY))
				.append(Component.text(playerName, NamedTextColor.GRAY))
				.append(Component.text("  $kills/$deaths/$assists", NamedTextColor.DARK_GRAY))
		}

		return Component.text("[", NamedTextColor.DARK_GRAY)
			.append(Component.text(team.name, team.color).decorate(TextDecoration.BOLD))
			.append(Component.text("] ", NamedTextColor.DARK_GRAY))
			.append(Component.text(playerName, team.color))
			.append(Component.text("  ", NamedTextColor.DARK_GRAY))
			.append(Component.text("$kills", NamedTextColor.GREEN))
			.append(Component.text("/", NamedTextColor.DARK_GRAY))
			.append(Component.text("$deaths", NamedTextColor.RED))
			.append(Component.text("/", NamedTextColor.DARK_GRAY))
			.append(Component.text("$assists", NamedTextColor.AQUA))
	}

	/**
	 * Formats the header for the Tab list.
	 */
	fun formatTabHeader(phaseDisplayName: String, remainingDurationText: String): Component {
		return Component.text("《陷落》阵营生存对抗", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
			.appendNewline()
			.append(Component.text("阶段: $phaseDisplayName  |  活动剩余: $remainingDurationText", NamedTextColor.GRAY))
	}

	/**
	 * Formats the footer for the Tab list showing personal combat data.
	 */
	fun formatTabFooter(
		kills: Int,
		deaths: Int,
		assists: Int,
		kdText: String,
		damageDealt: Double,
		damageTaken: Double
	): Component {
		val dealtText = "%.1f".format(damageDealt)
		val takenText = "%.1f".format(damageTaken)
		return Component.text("个人战绩: ", NamedTextColor.YELLOW)
			.append(Component.text("$kills 击杀", NamedTextColor.GREEN))
			.append(Component.text(" | ", NamedTextColor.DARK_GRAY))
			.append(Component.text("$deaths 死亡", NamedTextColor.RED))
			.append(Component.text(" | ", NamedTextColor.DARK_GRAY))
			.append(Component.text("$assists 助攻", NamedTextColor.AQUA))
			.append(Component.text(" (K/D: $kdText)", NamedTextColor.GOLD))
			.appendNewline()
			.append(Component.text("输出伤害: $dealtText❤  |  承受伤害: $takenText❤", NamedTextColor.DARK_AQUA))
	}
}
