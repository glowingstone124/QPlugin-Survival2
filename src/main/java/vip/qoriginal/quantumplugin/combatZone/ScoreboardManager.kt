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
		val board = createScoreboard(player, points)
		player.scoreboard = board
	}

	fun createScoreboard(player: Player, points: Int): Scoreboard {
		playerBoardInfo.getOrPut(player.uniqueId) { PlayerScoreboardInfo() }
		val manager = Bukkit.getScoreboardManager()
		val board = manager.newScoreboard
		val objective = board.registerNewObjective("info", Criteria.DUMMY, Component.text("Olympus HUD"))
		objective.displaySlot = DisplaySlot.SIDEBAR
		objective.getScore("战斗点数: $points").score = 3
		val levelInfo = getPlayerLevel(points)
		objective.getScore("当前等级${levelInfo.level}").score = 2
		levelInfo.pointsToNext.let {
			objective.getScore("距离升级还需要$it").score = 1
		}
		return board
	}


	fun updateScoreboard(player: Player, points: Int) {
		val board = player.scoreboard
		val objective = board.getObjective(DisplaySlot.SIDEBAR) ?: return
		val levelInfo = getPlayerLevel(points)

		val info = playerBoardInfo.getOrPut(player.uniqueId) { PlayerScoreboardInfo() }

		board.resetScores("战斗点数: ${info.lastPoints}")
		board.resetScores("当前等级${info.lastLevel}")

		objective.getScore("战斗点数: $points").score = 3
		objective.getScore("当前等级${levelInfo.level}").score = 2
		levelInfo.pointsToNext.let {
			objective.getScore("距离升级还需要$it").score = 1
		}

		info.lastPoints = points
		info.lastLevel = levelInfo.level
	}

}