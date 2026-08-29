package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ActiveHudPopup(
	val display: TextDisplay,
	val startedTick: Long,
	val maxTicks: Int = 24,
	val baseDistance: Double = 1.30,
	val initialYOffset: Double = 0.06
)

class FallenCombatHud(private val plugin: JavaPlugin) {
	private val activePopups = ConcurrentHashMap<UUID, MutableList<ActiveHudPopup>>()
	private var animationTask: BukkitTask? = null
	private var tickCount = 0L

	fun start() {
		if (animationTask != null) return
		animationTask = object : BukkitRunnable() {
			override fun run() {
				tick()
			}
		}.runTaskTimer(plugin, 1L, 1L)
	}

	fun stop() {
		animationTask?.cancel()
		animationTask = null
		clearAll()
	}

	fun clearAll() {
		for ((_, list) in activePopups) {
			for (popup in list) {
				if (popup.display.isValid) {
					popup.display.remove()
				}
			}
		}
		activePopups.clear()
	}

	fun clearForPlayer(playerId: UUID) {
		val list = activePopups.remove(playerId) ?: return
		for (popup in list) {
			if (popup.display.isValid) {
				popup.display.remove()
			}
		}
	}

	/**
	 * Shows a clean tactical score/reason popup in the format:
	 * +100
	 * reason
	 */
	fun showPopup(
		player: Player,
		score: Int?,
		reason: String,
		scoreColor: NamedTextColor = NamedTextColor.YELLOW,
		reasonColor: NamedTextColor = NamedTextColor.WHITE
	) {
		val text = buildScoreHudText(score, reason, scoreColor, reasonColor)
		spawnPopup(player, text)
	}

	/**
	 * Spawns a camera-following HUD popup in front of the player's view for a kill.
	 */
	fun showKillPopup(
		player: Player,
		score: Int = FallenScoreRules.KILL_SCORE,
		reason: String = "消灭敌人",
		reasonColor: NamedTextColor = NamedTextColor.WHITE
	) {
		showPopup(player, score, reason, scoreColor = NamedTextColor.YELLOW, reasonColor = reasonColor)
	}

	/**
	 * Spawns a camera-following HUD popup in front of the player's view for an assist.
	 */
	fun showAssistPopup(
		player: Player,
		score: Int = FallenScoreRules.ASSIST_SCORE,
		reason: String = "协助消灭"
	) {
		showPopup(player, score, reason, scoreColor = NamedTextColor.GOLD, reasonColor = NamedTextColor.WHITE)
	}

	/**
	 * Spawns a custom camera-following HUD popup in front of the player's view.
	 */
	fun showCustomPopup(
		player: Player,
		text: Component
	) {
		spawnPopup(player, text)
	}

	private fun spawnPopup(player: Player, text: Component) {
		if (!player.isOnline) return
		val eyeLoc = player.eyeLocation
		val spawnLoc = calculateCameraLocation(eyeLoc, distance = 1.30, cameraUpOffset = 0.06)

		val display = try {
			player.world.spawn(spawnLoc, TextDisplay::class.java) { entity ->
				entity.text(text)
				entity.billboard = Display.Billboard.CENTER
				entity.isSeeThrough = true
				entity.isShadowed = true
				entity.backgroundColor = Color.fromARGB(0, 0, 0, 0)
				entity.brightness = Display.Brightness(15, 15)
				entity.teleportDuration = 0
				entity.textOpacity = 255.toByte()
				entity.transformation = Transformation(
					Vector3f(0f, 0f, 0f),
					Quaternionf(),
					Vector3f(0.32f, 0.32f, 0.32f),
					Quaternionf()
				)
				entity.isVisibleByDefault = false
			}
		} catch (e: Exception) {
			return
		}

		// Only the target player can see this HUD popup entity
		player.showEntity(plugin, display)

		val playerPopups = activePopups.computeIfAbsent(player.uniqueId) { ArrayList() }
		playerPopups.add(ActiveHudPopup(display, tickCount, maxTicks = 24))
	}

	private fun tick() {
		tickCount++
		val iter = activePopups.entries.iterator()
		while (iter.hasNext()) {
			val entry = iter.next()
			val playerId = entry.key
			val player = Bukkit.getPlayer(playerId)
			val popups = entry.value

			if (player == null || !player.isOnline) {
				for (popup in popups) {
					if (popup.display.isValid) popup.display.remove()
				}
				iter.remove()
				continue
			}

			val eyeLoc = player.eyeLocation
			val popupIter = popups.iterator()
			var index = 0

			while (popupIter.hasNext()) {
				val popup = popupIter.next()
				val age = (tickCount - popup.startedTick).toInt()

				if (age >= popup.maxTicks || !popup.display.isValid) {
					if (popup.display.isValid) popup.display.remove()
					popupIter.remove()
					continue
				}

				val floatOffset = (age.toDouble() * 0.0025) + (index * 0.065)
				val targetLoc = calculateCameraLocation(
					eyeLocation = eyeLoc,
					distance = popup.baseDistance,
					cameraUpOffset = popup.initialYOffset + floatOffset
				)

				popup.display.teleport(targetLoc)

				if (age > popup.maxTicks - 8) {
					val fadeProgress = (popup.maxTicks - age) / 8.0
					val opacity = (fadeProgress * 255).toInt().coerceIn(0, 255).toByte()
					popup.display.textOpacity = opacity
				}

				index++
			}

			if (popups.isEmpty()) {
				iter.remove()
			}
		}
	}

	companion object {
		/**
		 * Calculates a position in the player's camera-space (relative to sightline and camera up vector).
		 */
		fun calculateCameraLocation(
			eyeLocation: Location,
			distance: Double,
			cameraUpOffset: Double
		): Location {
			val dir = eyeLocation.direction.normalize()
			val worldUp = Vector(0, 1, 0)
			val right = if (abs(dir.y) < 0.99) {
				dir.clone().crossProduct(worldUp).normalize()
			} else {
				dir.clone().crossProduct(Vector(1, 0, 0)).normalize()
			}
			val up = right.clone().crossProduct(dir).normalize()

			return eyeLocation.clone()
				.add(dir.clone().multiply(distance))
				.add(up.clone().multiply(cameraUpOffset))
		}

		/**
		 * Builds tactical FPS HUD text in the format:
		 * +100
		 * reason
		 */
		fun buildScoreHudText(
			score: Int?,
			reason: String,
			scoreColor: NamedTextColor = NamedTextColor.YELLOW,
			reasonColor: NamedTextColor = NamedTextColor.WHITE
		): Component {
			if (score != null && score > 0) {
				return Component.text("+$score", scoreColor).decorate(TextDecoration.BOLD)
					.appendNewline()
					.append(Component.text(reason, reasonColor).decorate(TextDecoration.BOLD))
			}
			return Component.text(reason, reasonColor).decorate(TextDecoration.BOLD)
		}
	}
}
