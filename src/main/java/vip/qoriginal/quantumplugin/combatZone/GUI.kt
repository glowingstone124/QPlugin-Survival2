package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.bossbar.BossBar.Color
import net.kyori.adventure.bossbar.BossBar.Overlay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneMainCity
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneSpawn
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneTinCity
import java.util.*

class GUI : Runnable {
	companion object {
		val activeBossBars: MutableMap<UUID, BossBar> = mutableMapOf()
	}

	override fun run() {
		for (player in Bukkit.getOnlinePlayers()) {
			showActionBar(player)
			showOrUpdateBossBar(player)
		}
	}

	private inline fun showActionBar(player: Player) {
		val stats = CombatPoint.playerStats[player.uniqueId] ?: return
		val score = stats.points

		val subtitle = Component.text("格斗点数: $score")
			.color(TextColor.color(0, 255, 255))

		(player as Audience).sendActionBar(subtitle)
	}

	private inline fun showOrUpdateBossBar(player: Player) {
		val uuid = player.uniqueId
		val loc = player.location
		val currentHotZone = Utils.getCurrentZone(
			loc,
			listOf(hotZoneTinCity, hotZoneMainCity, hotZoneSpawn)
		)
		val multiplier = getLocationMultiplier(loc)

		val bossBar = activeBossBars.computeIfAbsent(uuid) {
			val newBar = BossBar.bossBar(
				Component.text("初始化..."),
				1.0f,
				Color.BLUE,
				Overlay.PROGRESS
			)
			player.showBossBar(newBar)
			newBar
		}

		if (currentHotZone != null) {
			bossBar.name(
				Component.text("进入：${currentHotZone.name}, 当前分数倍率 ${multiplier}x")
					.color(NamedTextColor.RED)
			)
			bossBar.color(Color.RED)
			bossBar.progress(1.0f)
		} else {
			bossBar.name(
				Component.text("当前分数倍率 $multiplier")
					.color(NamedTextColor.GRAY)
			)
			bossBar.color(Color.WHITE)
			bossBar.progress(0.0f)
		}
	}
}
