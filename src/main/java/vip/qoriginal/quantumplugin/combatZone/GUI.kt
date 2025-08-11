package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Effect
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneMainCity
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneSpawn
import vip.qoriginal.quantumplugin.combatZone.CombatPoints.Companion.hotZoneTinCity
import vip.qoriginal.quantumplugin.combatZone.Utils.getHorizontalDistance
import java.util.*

class GUI : Runnable {

	val combatPoints = CombatPoints()

	enum class BossBarType {
		HOT_ZONE,
		KILL_LEADER_DISTANCE,
	}

	companion object {
		val activeBossBars: MutableMap<UUID, MutableMap<BossBarType, BossBar>> = mutableMapOf()
		var currentKillLeader: Player? = null
		private var lastKillLeaderUUID: UUID? = null
	}

	override fun run() {
		for (player in Bukkit.getOnlinePlayers()) {
			showActionBar(player)
			showCurrentKillLeader()
			showKillLeaderDistance(player)
			showOrUpdateHotZone(player)
		}
		updateResistanceBuff()
	}
	fun getOrCreateBossBar(
		player: Player,
		type: BossBarType,
		name: Component,
		color: BossBar.Color = BossBar.Color.WHITE,
		overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS
	): BossBar {
		val uuid = player.uniqueId
		val bars = activeBossBars.computeIfAbsent(uuid) { mutableMapOf() }

		return bars.computeIfAbsent(type) {
			val bar = BossBar.bossBar(name, 1.0f, color, overlay)
			player.showBossBar(bar)
			bar
		}
	}


	private inline fun showCurrentKillLeader() {
		val currentKiller = combatPoints.getTopKiller() ?: return
		val cPlayer =  Bukkit.getPlayer(currentKiller.first) ?: return
		println("currentKillLeader: ${cPlayer.name}")
		if (currentKillLeader?.name == cPlayer.name) return

		GUI.currentKillLeader = cPlayer
		Bukkit.getOnlinePlayers().forEach {
			it.sendMessage(Utils.prependBroadCast(Component.text("诞生了新的击杀王：")
				.append(Component.text(cPlayer.name).color(NamedTextColor.GOLD))
				.append(Component.text("击杀数:"))
				.append(Component.text(currentKiller.second.kills).color(NamedTextColor.RED))))
		}
	}

	private fun updateResistanceBuff() {
		val newLeader = currentKillLeader ?: return

		if (lastKillLeaderUUID != null && lastKillLeaderUUID != newLeader.uniqueId) {
			Bukkit.getPlayer(lastKillLeaderUUID!!)?.removePotionEffect(PotionEffectType.RESISTANCE)
		}

		val resistanceEffect = PotionEffect(
			PotionEffectType.RESISTANCE,
			Int.MAX_VALUE - 1,
			1,
			false,
			true,
			true,
		)

		newLeader.addPotionEffect(resistanceEffect)

		lastKillLeaderUUID = newLeader.uniqueId
	}

	private fun showKillLeaderDistance(player: Player) {
		val leader = currentKillLeader ?: return
		if (leader.uniqueId == player.uniqueId) {
			val bossBar = getOrCreateBossBar(
				player,
				BossBarType.KILL_LEADER_DISTANCE,
				Component.text("你是新的击杀王"),
				BossBar.Color.PURPLE,
				BossBar.Overlay.NOTCHED_12
			)
			bossBar.progress(1.0f)
			return
		}

		val distance = getHorizontalDistance(player.location, leader.location)
		val bossBar = getOrCreateBossBar(
			player,
			BossBarType.KILL_LEADER_DISTANCE,
			Component.text("初始化距离..."),
			BossBar.Color.PURPLE,
			BossBar.Overlay.NOTCHED_12
		)
		var progress = 0.0
		bossBar.name(
			if (distance < 100) {
				progress = 1.0
				Component.text("距离击杀王非常近(小于100格)")
					.color(NamedTextColor.LIGHT_PURPLE)
			} else if (distance in 100.0..400.0) {
				progress = 0.6
				Component.text("距离击杀王100-400格")
					.color(NamedTextColor.LIGHT_PURPLE)
			} else {
				progress = 0.3
				Component.text("距离击杀王非常远")
					.color(NamedTextColor.LIGHT_PURPLE)
			}
		)


		bossBar.progress(progress.toFloat())
	}


	private inline fun showActionBar(player: Player) {
		val stats = CombatPoint.playerStats[player.uniqueId] ?: return
		val score = stats.points

		val subtitle = Component.text("格斗点数: $score")
			.color(TextColor.color(0, 255, 255))

		(player as Audience).sendActionBar(subtitle)
	}

	private fun showOrUpdateHotZone(player: Player) {
		val loc = player.location
		val currentHotZone = Utils.getCurrentZone(
			loc,
			listOf(hotZoneTinCity, hotZoneMainCity, hotZoneSpawn)
		)
		val multiplier = getLocationMultiplier(loc)

		val bossBar = getOrCreateBossBar(
			player,
			BossBarType.HOT_ZONE,
			Component.text("初始化热区..."),
			BossBar.Color.BLUE
		)

		if (currentHotZone != null) {
			bossBar.name(
				Component.text("进入：${currentHotZone.name}, 当前分数倍率 ${multiplier}x")
					.color(NamedTextColor.RED)
			)
			bossBar.color(BossBar.Color.RED)
			bossBar.progress(1.0f)
		} else {
			bossBar.name(
				Component.text("当前分数倍率 $multiplier")
					.color(NamedTextColor.GRAY)
			)
			bossBar.color(BossBar.Color.WHITE)
			bossBar.progress(0.0f)
		}
	}

}
