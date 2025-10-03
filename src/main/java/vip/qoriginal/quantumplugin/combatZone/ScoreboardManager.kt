package vip.qoriginal.quantumplugin.combatZone

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import vip.qoriginal.quantumplugin.combatZone.Utils.getPlayerLevel
import java.util.UUID

class ScoreboardManager : Listener {
	companion object {
		val playerBoardInfo = mutableMapOf<UUID, PlayerScoreboardInfo>()
	}

	data class PlayerScoreboardInfo(
		var lastPoints: Int = 0,
		var lastLevel: Int = 0
	)

	val combatPoints = CombatPoints()

	@EventHandler
	fun onJoin(event: PlayerJoinEvent) {
		val player = event.player
		val pStat = combatPoints.getStats(player)
		val points = pStat.points
		val board = createOrGetScoreboard(player, points)
		player.scoreboard = board
	}

	fun createOrGetScoreboard(player: Player, points: Int): Scoreboard {
		val manager = Bukkit.getScoreboardManager() ?: return Bukkit.getScoreboardManager().newScoreboard
		val board = player.scoreboard.takeIf { it != manager.mainScoreboard } ?: manager.newScoreboard

		val objective = board.getObjective("info")
			?: board.registerNewObjective("info", Criteria.DUMMY, Component.text("Olympus HUD"))
		objective.displaySlot = DisplaySlot.SIDEBAR

		val levelInfo = getPlayerLevel(points)

		val pointsTeam = board.getTeam("points") ?: board.registerNewTeam("points").apply { addEntry("§1") }
		pointsTeam.prefix(Component.text("战斗点数: $points"))
		objective.getScore("§1").score = 3

		val levelTeam = board.getTeam("level") ?: board.registerNewTeam("level").apply { addEntry("§2") }
		levelTeam.prefix(Component.text("当前等级${levelInfo.level}"))
		objective.getScore("§2").score = 2

		val nextTeam = board.getTeam("next") ?: board.registerNewTeam("next").apply { addEntry("§3") }
		nextTeam.prefix(Component.text("距离升级还需要${levelInfo.pointsToNext}"))
		objective.getScore("§3").score = 1

		return board
	}

	fun updateScoreboard(player: Player, points: Int) {
		val board = createOrGetScoreboard(player, points)
		player.scoreboard = board
	}
}
