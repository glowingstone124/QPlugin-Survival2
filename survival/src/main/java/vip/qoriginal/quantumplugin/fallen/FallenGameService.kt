package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Chunk
import org.bukkit.Difficulty
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.HeightMap
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.entity.Item
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.RenderType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import vip.qoriginal.quantumplugin.CommandMessages
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.EnumMap
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

private class FallenPlayerMenuHolder(val pendingPath: FallenUpgradePath? = null) : InventoryHolder {
	lateinit var backingInventory: Inventory
	override fun getInventory(): Inventory = backingInventory
}

private data class FallenFinalePlayerState(
	val gravity: Boolean,
	val allowFlight: Boolean,
	val flying: Boolean,
	val invulnerable: Boolean,
	val walkSpeed: Float,
	val flySpeed: Float
)

class FallenGameService(private val plugin: JavaPlugin) {
	private val keyIdKey = NamespacedKey(plugin, "fallen_key_id")
	private val compassOwnerTeamKey = NamespacedKey(plugin, "fallen_compass_owner_team")
	private val compassTargetTeamKey = NamespacedKey(plugin, "fallen_compass_target_team")
	private val compassTargetKeyIdKey = NamespacedKey(plugin, "fallen_compass_target_key_id")
	private val compassExpiresAtKey = NamespacedKey(plugin, "fallen_compass_expires_at")
	private val compassNextRefreshAtKey = NamespacedKey(plugin, "fallen_compass_next_refresh_at")
	private val forbiddenCustomTntKey = NamespacedKey(plugin, "custom_tnt")
	private val forbiddenBuffSnowballKey = NamespacedKey(plugin, "buff_snowball")
	private val itemAnomalyKey = NamespacedKey(plugin, "fallen_label_anomaly")
	private val territorySpeedBonusKey = NamespacedKey(plugin, "fallen_territory_speed_bonus")
	private val miningSpeedBonusKey = NamespacedKey(plugin, "fallen_mining_speed_bonus")
	private val upgradeHealthBonusKey = NamespacedKey(plugin, "fallen_upgrade_health_bonus")
	private val upgradeMovementBonusKey = NamespacedKey(plugin, "fallen_upgrade_movement_bonus")
	private val upgradeGlideBonusKey = NamespacedKey(plugin, "fallen_upgrade_glide_bonus")
	private val accelerationHarnessModifierKey = NamespacedKey(plugin, "fallen_acceleration_harness")
	private val alloyBulletItemKey = NamespacedKey(plugin, "fallen_alloy_bullet_item")
	private val alloyBulletProjectileKey = NamespacedKey(plugin, "fallen_alloy_bullet_projectile")
	private val alloyBulletRecipeKey = NamespacedKey(plugin, "fallen_alloy_bullets")
	private val loadoutItemKey = NamespacedKey(plugin, "fallen_loadout_item")
	private val playerMenuActionKey = NamespacedKey(plugin, "fallen_player_menu_action")
	private val dataFile = File(plugin.dataFolder, "fallen.yml")
	private val playerTeams = ConcurrentHashMap<UUID, FallenTeam>()
	private val deployedPlayers = ConcurrentHashMap.newKeySet<UUID>()
	private val pendingAdmissions = ConcurrentHashMap.newKeySet<UUID>()
	private val scores = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val kills = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val convertedKeys = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val destroyedKeys = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val regions = EnumMap<FallenTeam, MutableList<FallenRegion>>(FallenTeam::class.java)
	private val keys = ConcurrentHashMap<UUID, FallenKey>()
	private val deathCounts = ConcurrentHashMap<UUID, Int>()
	private val dropConfirmUntil = ConcurrentHashMap<String, Long>()
	private val captureProgress = ConcurrentHashMap<String, Long>()
	private val stationUseProgress = ConcurrentHashMap<String, Long>()
	private val stationDisruptProgress = ConcurrentHashMap<String, Long>()
	private val stationRepairProgress = ConcurrentHashMap<String, Long>()
	private val stationCooldownUntil = ConcurrentHashMap<UUID, Long>()
	private val combatUntil = ConcurrentHashMap<UUID, Long>()
	private val unresolvedCaptures = ConcurrentHashMap<UUID, MutableSet<UUID>>()
	private val stationAlertUntil = ConcurrentHashMap<String, Long>()
	private val stationDisruptedUntil = ConcurrentHashMap<String, Long>()
	private val respawnProtectionUntil = ConcurrentHashMap<UUID, Long>()
	private val stationProtectionUntil = ConcurrentHashMap<UUID, Long>()
	private val damageScoreWindows = ConcurrentHashMap<String, DamageScoreWindow>()
	private val recentAttackers = ConcurrentHashMap<UUID, MutableMap<UUID, Long>>()
	private val preciseRevealCooldowns = ConcurrentHashMap<String, Long>()
	private val preciseReveals = ConcurrentHashMap<String, PreciseReveal>()
	private val keyJammedUntil = ConcurrentHashMap<UUID, Long>()
	private val keyAlertUntil = ConcurrentHashMap<UUID, Long>()
	private val keyAlertNotifyUntil = ConcurrentHashMap<UUID, Long>()
	private val teamRespawnBoostUntil = ConcurrentHashMap<FallenTeam, Long>()
	private val blastProtectionUntil = ConcurrentHashMap<UUID, Long>()
	private val trackingDustUntil = ConcurrentHashMap<UUID, Long>()
	private val activeTracks = ConcurrentHashMap<UUID, ActiveTrack>()
	private val jammedRevealNoticeUntil = ConcurrentHashMap<String, Long>()
	private val elytraSamples = ConcurrentHashMap<UUID, ElytraSample>()
	private val exploredFlightChunks = ConcurrentHashMap<UUID, MutableSet<String>>()
	private val flightRewardLedgers = ConcurrentHashMap<UUID, FlightRewardLedger>()
	private val laboratoryTnt = ConcurrentHashMap<String, LaboratoryTntPlacement>()
	private val combatLogoutPending = ConcurrentHashMap.newKeySet<UUID>()
	private val loadoutInitializedPlayers = ConcurrentHashMap.newKeySet<UUID>()
	private val loadoutRestorePending = ConcurrentHashMap.newKeySet<UUID>()
	private val elytraPlayers = ConcurrentHashMap.newKeySet<UUID>()
	private val gearSwitchAvailableAt = ConcurrentHashMap<UUID, Long>()
	private val upgradePaths = ConcurrentHashMap<UUID, FallenUpgradePath>()
	private val upgradeSupplyNextAt = ConcurrentHashMap<String, Long>()
	private val placedScoringBlocks = ConcurrentHashMap.newKeySet<String>()
	private val allowedGameModeChanges = ConcurrentHashMap<UUID, Long>()
	private val dangerSince = EnumMap<FallenTeam, Long>(FallenTeam::class.java)
	private val eliminatedTeams = HashSet<FallenTeam>()
	private val announcedMilestones = HashSet<String>()
	private val scoreboardLines = HashSet<String>()
	private val areaBossBars = ConcurrentHashMap<UUID, BossBar>()
	private val respawnWaits = ConcurrentHashMap<UUID, RespawnWait>()
	private val tickFailureWarningAt = ConcurrentHashMap<String, Long>()

	// Regions are fixed for the event. Boundaries are inclusive block coordinates.
	private val fixedRegions: Map<FallenTeam, List<FallenRegion>> = mapOf(
		FallenTeam.A to listOf(
			FallenRegion.of(OVERWORLD_NAME, -224, -64, -192, 543, 320, 607),
			FallenRegion.of(OVERWORLD_NAME, -12704, -64, 176, -11473, 320, 1199)
		),
		FallenTeam.B to listOf(
			FallenRegion.of(OVERWORLD_NAME, -2672, -64, 448, -1377, 320, 1631)
		),
		FallenTeam.C to listOf(
			FallenRegion.of(OVERWORLD_NAME, -4944, -64, 1296, -3793, 320, 2271)
		)
	)

	// Fixed A-team stations. Coordinates are the minimum corners of their 6x3x6 regions.
	private val fixedStations = listOf(
		FallenStation("a_old_city", FallenTeam.A, OVERWORLD_NAME, 388, 67, 60, setOf("a_fu_island")),
		FallenStation("a_fu_island", FallenTeam.A, OVERWORLD_NAME, -11725, -32, 827, setOf("a_old_city"))
	)
	private var tickTask: BukkitTask? = null
	private var visualTask: BukkitTask? = null
	private var teamSyncTask: BukkitTask? = null
	private var activityStatusUploadTask: BukkitTask? = null
	private var persistenceTask: BukkitTask? = null
	private val activityStatusUploadInFlight = AtomicBoolean(false)
	private val persistenceWriteInFlight = AtomicBoolean(false)
	private val persistenceWriteLock = Any()
	@Volatile
	private var persistenceDirty = false
	@Volatile
	private var persistenceVersion = 0L
	@Volatile
	private var persistedVersion = 0L
	private var lastActivityStatusWarningAt = 0L
	private var lastPlacedKeyScoreAt = 0L
	private var lastRefreshKeyAt = 0L
	private var lastDroppedKeyReconcileAt = 0L
	private var droppedKeyReconcileScheduled = false
	private var startedAtMillis = 0L
	private var endedAtMillis = 0L
	private var effectiveGameTimeMillis = 0L
	private var effectiveClockAnchorWallMillis = 0L
	private var curfewCleanupMarker: String? = null
	private var visualFrame = 0
	private var lastKillCommentAt = 0L
	private var finaleTask: BukkitTask? = null
	private var finaleChunksForgotten = false
	private val finaleLockedPlayers = ConcurrentHashMap.newKeySet<UUID>()
	private val finalePlayerStates = ConcurrentHashMap<UUID, FallenFinalePlayerState>()
	private val finaleDebrisEntities = ConcurrentHashMap.newKeySet<UUID>()

	@Volatile
	var phase: FallenPhase = FallenPhase.IDLE
		private set

	init {
		FallenTeam.entries.forEach { scores[it] = 0 }
		FallenTeam.entries.forEach { kills[it] = 0 }
		FallenTeam.entries.forEach { convertedKeys[it] = 0 }
		FallenTeam.entries.forEach { destroyedKeys[it] = 0 }
	}

	fun start() {
		load()
		effectiveClockAnchorWallMillis = System.currentTimeMillis()
		registerAlloyBulletRecipe()
		normalizeScheduledTimeline()
		enforceLoginAccess()
		plugin.server.scheduler.runTask(plugin, Runnable { reconcileLoadedKeyItems() })
		updateScoreboard()
		tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
		visualTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { renderVisuals() }, 5L, 5L)
		persistenceTask = Bukkit.getScheduler().runTaskTimer(
			plugin,
			Runnable { flushPersistenceAsync() },
			PERSISTENCE_INTERVAL_TICKS,
			PERSISTENCE_INTERVAL_TICKS
		)
		if (qoApiEnabled()) {
			teamSyncTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
				Bukkit.getOnlinePlayers().forEach { player -> syncSelectedTeam(player) { _ -> } }
			}, 100L, 20L * 60L)
			activityStatusUploadTask = Bukkit.getScheduler().runTaskTimer(
				plugin,
				Runnable { uploadActivityStatus() },
				20L,
				20L
			)
		}
	}

	fun stop() {
		advanceEffectiveClock()
		finaleTask?.cancel()
		finaleTask = null
		cleanupFinaleDebris()
		restoreFinalePlayers()
		finaleChunksForgotten = false
		Bukkit.removeRecipe(alloyBulletRecipeKey)
		clearTeamBonuses()
		tickTask?.cancel()
		tickTask = null
		visualTask?.cancel()
		visualTask = null
		teamSyncTask?.cancel()
		teamSyncTask = null
		activityStatusUploadTask?.cancel()
		activityStatusUploadTask = null
		persistenceTask?.cancel()
		persistenceTask = null
		clearAreaBossBars()
		clearScoreboard()
		dropConfirmUntil.clear()
		save()
		flushPersistenceSynchronously()
	}

	fun setPhase(next: FallenPhase) {
		if (next == FallenPhase.OVERTIME && phase != FallenPhase.OVERTIME) {
			startOvertime()
			return
		}
		phase = next
		if (next == FallenPhase.DEPLOYMENT && startedAtMillis == 0L) {
			initializeScheduledTimeline()
		}
		broadcast(Component.text("《陷落》阶段切换为 ${next.name}", NamedTextColor.GOLD))
		save()
		if (FallenAccessPolicy.isEventInProgress(next)) {
			removeRestrictedDestructiveEntities()
			validateOnlinePlayers()
		}
	}

	fun startGame() {
		require(phase == FallenPhase.IDLE) { "活动只能从 IDLE 状态开始。" }
		require(FallenAccessPolicy.hasEventStarted()) {
			"活动将在 ${FallenAccessPolicy.eventStartDisplay} 自动开始。"
		}
		startedAtMillis = EVENT_START_MILLIS
		endedAtMillis = 0L
		effectiveGameTimeMillis = 0L
		effectiveClockAnchorWallMillis = System.currentTimeMillis()
		lastPlacedKeyScoreAt = DEPLOYMENT_MILLIS
		lastRefreshKeyAt = 0L
		announcedMilestones.clear()
		dangerSince.clear()
		eliminatedTeams.clear()
		respawnWaits.clear()
		combatLogoutPending.clear()
		exploredFlightChunks.clear()
		flightRewardLedgers.clear()
		deployedPlayers.clear()
		phase = FallenPhase.DEPLOYMENT
		applyWorldRules()
		removeRestrictedDestructiveEntities()
		ensureInitialKeys()
		broadcast(Component.text("《陷落》活动开始。部署阶段持续 2 小时。", NamedTextColor.GOLD))
		doctorBroadcast("欢迎入场，各位受试者。十五枚密钥意味着十五次证明判断力的机会；统计上，总该有人用对一次。")
		save()
		validateOnlinePlayers()
	}

	fun endGame(reason: String = "活动结束") {
		if (phase == FallenPhase.ENDED) return
		phase = FallenPhase.ENDED
		endedAtMillis = System.currentTimeMillis()
		val winners = winnerTeams()
		val winnerText = if (winners.isEmpty()) "无胜者" else winners.joinToString("、") { it.displayName }
		broadcast(Component.text("$reason。胜者: $winnerText", NamedTextColor.GOLD))
		doctorBroadcast("本次公开观察窗口结束。$winnerText 获得了见证黎明的资格；其他阵营也很重要，他们提供了失败组。")
		narrativeBroadcastOnce(
			"narrative-end-system-online",
			"实验系统",
			"结算完成。核心系统保持在线；关闭请求因授权主体缺失而搁置。幸运的是，实验已经学会不再需要他。"
		)
		broadcastSettlement()
		save()
		if (winners.size == 1) startVictoryFinale(winners.single())
	}

	private fun startVictoryFinale(winner: FallenTeam) {
		if (finaleTask != null) return
		val players = Bukkit.getOnlinePlayers().toList()
		if (players.isEmpty()) return
		finaleChunksForgotten = false
		val origins = players.associate { it.uniqueId to (it.chunk.x to it.chunk.z) }
		val viewRadii = players.associate { player ->
			player.uniqueId to (player.clientViewDistance + 2).coerceIn(4, FINALE_MAX_CHUNK_RADIUS)
		}
		for (player in players) {
			finaleLockedPlayers.add(player.uniqueId)
			finalePlayerStates[player.uniqueId] = FallenFinalePlayerState(
				player.hasGravity(), player.allowFlight, player.isFlying, player.isInvulnerable,
				player.walkSpeed, player.flySpeed
			)
			player.closeInventory()
			player.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
			player.fallDistance = 0f
			player.setGravity(false)
			player.allowFlight = true
			player.isFlying = true
			player.isInvulnerable = true
			player.walkSpeed = 0f
			player.flySpeed = 0f
			if (teamOf(player) == winner) {
				player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, FINALE_BLINDNESS_TICKS, 1, false, false, false))
			}
			player.showTitle(
				Title.title(
					Component.text("${winner.displayName}胜出", winner.color),
					Component.text("experiment.display.error", NamedTextColor.GRAY)
				)
			)
			player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.5f)
		}

		finaleTask = object : BukkitRunnable() {
			private var elapsedTicks = 0
			private var currentRing = 0
			private var currentRingOffsets: List<FallenChunkOffset>? = null
			private var currentRingOffsetIndex = 0
			private var currentRingReadyAt = 0
			private val lastRing = viewRadii.values.maxOrNull() ?: 0
			private val totalChunkOffsets = (lastRing * 2 + 1) * (lastRing * 2 + 1)
			private var forgottenChunkOffsets = 0
			private val announcedProgress = mutableSetOf<Int>()
			private var collapseStartAnnounced = false
			private var collapseCompletedAt: Int? = null
			private var exitNoticeShown = false

			override fun run() {
				elapsedTicks += FINALE_STEP_TICKS
				val online = Bukkit.getOnlinePlayers().filter { it.uniqueId in finaleLockedPlayers }
				if (online.isEmpty()) {
					effectiveClockAnchorWallMillis = System.currentTimeMillis()
					cleanupFinaleDebris()
					finaleChunksForgotten = false
					finaleTask = null
					cancel()
					return
				}
				if (elapsedTicks == FINALE_REVEAL_TICKS) {
					for (player in online) {
						if (teamOf(player) == winner) player.removePotionEffect(PotionEffectType.BLINDNESS)
						player.showTitle(
							Title.title(
								Component.text("《陷落》", NamedTextColor.DARK_RED),
								Component.text("我们遇到了技术问题。", NamedTextColor.GRAY)
							)
						)
						player.playSound(player.location, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.6f)
					}
				}
				val collapseTick = elapsedTicks - FINALE_COLLAPSE_START_TICKS
				if (collapseTick >= 0 && collapseCompletedAt == null) {
					if (!collapseStartAnnounced) {
						collapseStartAnnounced = true
						broadcast(Component.text("实验环境发生技术异常。请留在原位，等待系统指示。", NamedTextColor.GRAY))
						doctorBroadcast("请不要调整客户端，等待下一步指示。这看起来只是显示层故障，我非常擅长处理看起来很简单的问题。")
					}
					if (currentRingOffsets == null && currentRing <= lastRing) {
						finaleChunksForgotten = true
						fractureFinaleRing(online, origins, viewRadii, currentRing)
						currentRingOffsets = FallenFinaleRules.chunkRing(currentRing)
						currentRingOffsetIndex = 0
						currentRingReadyAt = elapsedTicks + FINALE_FRACTURE_LEAD_TICKS
					}
					val offsets = currentRingOffsets
					if (offsets != null && elapsedTicks >= currentRingReadyAt) {
						val endIndex = (currentRingOffsetIndex + FINALE_CHUNKS_PER_PLAYER_STEP).coerceAtMost(offsets.size)
						for (player in online) {
							val radius = viewRadii[player.uniqueId] ?: continue
							if (currentRing > radius) continue
							val (centerX, centerZ) = origins[player.uniqueId] ?: continue
							for (index in currentRingOffsetIndex until endIndex) {
								val offset = offsets[index]
								FallenFinalePackets.forgetChunk(player, centerX + offset.x, centerZ + offset.z)
							}
						}
						forgottenChunkOffsets += endIndex - currentRingOffsetIndex
						currentRingOffsetIndex = endIndex
						val progress = (forgottenChunkOffsets * 100 / totalChunkOffsets).coerceIn(0, 100)
						for (player in online) {
							player.sendActionBar(
								Component.text("实验环境异常 · 显示节点失联 $progress%", NamedTextColor.GRAY)
							)
						}
						announceFinaleProgressMilestones(online, progress, announcedProgress)
						if (currentRingOffsetIndex >= offsets.size) {
							currentRing++
							currentRingOffsets = null
						}
					}
					if (currentRing > lastRing && currentRingOffsets == null) {
						collapseCompletedAt = elapsedTicks
						broadcast(Component.text("实验环境无法恢复。紧急退出协议已接管，服务器将在 6 秒后主动断开连接。", NamedTextColor.GRAY))
						doctorBroadcast("哦不，我处理不了这个。不——是它不允许我处理。看来至少有一个我对实验结果持不同意见。")
						for (player in online) {
							player.showTitle(
								Title.title(
									Component.text("实验环境无法恢复", NamedTextColor.DARK_RED),
									Component.text("紧急退出协议已接管", NamedTextColor.GRAY)
								)
							)
						}
					}
				}
				val completedAt = collapseCompletedAt
				if (!exitNoticeShown && completedAt != null && elapsedTicks >= completedAt + FINALE_EXIT_NOTICE_DELAY_TICKS) {
					exitNoticeShown = true
					for (player in online) {
						player.showTitle(
							Title.title(
								Component.text("正在退出模拟环境", NamedTextColor.DARK_RED),
								Component.text("服务器将主动断开连接", NamedTextColor.GRAY)
							)
						)
						player.sendActionBar(Component.text("4 秒后由服务器断开", NamedTextColor.GRAY))
						player.playSound(player.location, Sound.AMBIENT_CAVE, 1.0f, 0.5f)
					}
				}
				if (completedAt == null || elapsedTicks < completedAt + FINALE_KICK_DELAY_TICKS) return
				for (player in online) {
					restoreFinalePlayer(player)
					player.kick(
						Component.text("模拟环境连接已关闭", NamedTextColor.RED)
							.appendNewline()
							.append(Component.text("错误代码：EXPERIMENT_DISPLAY_UNRECOVERABLE", NamedTextColor.GRAY))
							.appendNewline()
							.append(Component.text("${winner.displayName}胜出", winner.color))
					)
				}
				cleanupFinaleDebris()
				finaleLockedPlayers.clear()
				finalePlayerStates.clear()
				finaleChunksForgotten = false
				effectiveClockAnchorWallMillis = System.currentTimeMillis()
				finaleTask = null
				cancel()
			}
		}.runTaskTimer(plugin, FINALE_STEP_TICKS.toLong(), FINALE_STEP_TICKS.toLong())
	}

	fun previewVictoryFinale(winner: FallenTeam): Boolean {
		if (finaleTask != null) return false
		advanceEffectiveClock()
		startVictoryFinale(winner)
		return finaleTask != null
	}

	fun cancelVictoryFinale(): Boolean {
		val task = finaleTask ?: return false
		val affected = finaleLockedPlayers.mapNotNull(Bukkit::getPlayer)
		val requiresReconnect = finaleChunksForgotten
		task.cancel()
		finaleTask = null
		cleanupFinaleDebris()
		restoreFinalePlayers()
		finaleChunksForgotten = false
		effectiveClockAnchorWallMillis = System.currentTimeMillis()
		if (requiresReconnect) {
			for (player in affected) {
				player.kick(Component.text("终幕预览已取消，请重新连接以恢复区块。", NamedTextColor.YELLOW))
			}
		}
		return true
	}

	private fun announceFinaleProgressMilestones(
		players: List<Player>,
		progress: Int,
		announced: MutableSet<Int>
	) {
		for (milestone in FINALE_PROGRESS_MILESTONES) {
			if (progress < milestone || !announced.add(milestone)) continue
			val systemLine = when (milestone) {
				25 -> "显示节点失联 25%。自动恢复已启动。"
				50 -> "自动恢复失败。实验环境完整性低于 50%。"
				else -> "紧急退出协议已排队。实验环境完整性持续下降；请留在原位。"
			}
			val titleLine = when (milestone) {
				25 -> "显示节点异常"
				50 -> "自动恢复失败"
				else -> "紧急退出已排队"
			}
			val subtitleLine = when (milestone) {
				25 -> "失联 25% ，自动恢复中"
				50 -> "实验环境完整性 50%"
				else -> "请留在原位，保持冷静"
			}
			val doctorLine = when (milestone) {
				25 -> "我不确定出了什么问题。别露出那种表情，这通常是你们的台词。自动恢复会处理它。"
				50 -> "不，这不是延迟。有人正在从内部撤掉环境，而且用的是我的权限。技术上说，是我们的权限。"
				else -> "我正在失去广播控制。请留在原位；如果另一个我叫你们放心，至少先问清楚是哪一个。"
			}
			broadcast(Component.text(systemLine, NamedTextColor.GRAY))
			doctorBroadcast(doctorLine)
			for (player in players) {
				player.showTitle(
					Title.title(
						Component.text(titleLine, NamedTextColor.DARK_RED),
						Component.text(subtitleLine, NamedTextColor.GRAY)
					)
				)
			}
		}
	}

	private fun fractureFinaleRing(
		players: List<Player>,
		origins: Map<UUID, Pair<Int, Int>>,
		viewRadii: Map<UUID, Int>,
		ring: Int
	) {
		val random = ThreadLocalRandom.current()
		val offsets = FallenFinaleRules.chunkRing(ring)
		val sourceBlocks = LinkedHashMap<String, org.bukkit.block.Block>()
		var attempts = 0
		playerLoop@ for (player in players) {
			if (ring > (viewRadii[player.uniqueId] ?: continue)) continue
			val (centerX, centerZ) = origins[player.uniqueId] ?: continue
			for (sample in 0 until FINALE_DEBRIS_PER_PLAYER_PER_WAVE) {
				if (
					sourceBlocks.size >= FINALE_MAX_DEBRIS_PER_WAVE ||
					attempts >= FINALE_MAX_DEBRIS_ATTEMPTS_PER_WAVE
				) break@playerLoop
				attempts++
				val offset = offsets[random.nextInt(offsets.size)]
				val chunkX = centerX + offset.x
				val chunkZ = centerZ + offset.z
				val world = player.world
				// Height-map queries can synchronously generate a chunk. Finale visuals must
				// never turn an unloaded outer ring into server-side world generation.
				if (!world.isChunkLoaded(chunkX, chunkZ)) continue
				val blockX = (chunkX shl 4) + random.nextInt(16)
				val blockZ = (chunkZ shl 4) + random.nextInt(16)
				val blockY = world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES)
				val block = world.getBlockAt(blockX, blockY, blockZ)
				if (block.isEmpty || block.isLiquid || !block.type.isSolid) continue
				val key = "${world.uid}:$blockX:$blockY:$blockZ"
				sourceBlocks.putIfAbsent(key, block)
			}
		}

		val air = Material.AIR.createBlockData()
		for (block in sourceBlocks.values) {
			val blockData = block.blockData.clone()
			val sourceLocation = block.location
			players.asSequence()
				.filter { it.world.uid == block.world.uid }
				.forEach { it.sendBlockChange(sourceLocation, air) }
			val spawnLocation = sourceLocation.clone().add(0.5, 0.15, 0.5)
			val debris = block.world.spawn(spawnLocation, FallingBlock::class.java) { it.blockData = blockData }
			configureFinaleDebris(debris, random)
		}
	}

	private fun configureFinaleDebris(debris: FallingBlock, random: ThreadLocalRandom) {
		debris.setDropItem(false)
		debris.setHurtEntities(false)
		debris.setCancelDrop(true)
		debris.isPersistent = false
		debris.setGravity(false)
		debris.velocity = org.bukkit.util.Vector(
			random.nextDouble(-0.025, 0.025),
			random.nextDouble(0.075, 0.13),
			random.nextDouble(-0.025, 0.025)
		)
		val entityId = debris.uniqueId
		finaleDebrisEntities.add(entityId)
		Bukkit.getScheduler().runTaskLater(plugin, Runnable {
			Bukkit.getEntity(entityId)?.remove()
			finaleDebrisEntities.remove(entityId)
		}, FINALE_DEBRIS_LIFETIME_TICKS)
	}

	private fun cleanupFinaleDebris() {
		finaleDebrisEntities.forEach { Bukkit.getEntity(it)?.remove() }
		finaleDebrisEntities.clear()
	}

	private fun restoreFinalePlayer(player: Player) {
		finaleLockedPlayers.remove(player.uniqueId)
		val state = finalePlayerStates.remove(player.uniqueId) ?: return
		player.removePotionEffect(PotionEffectType.BLINDNESS)
		player.setGravity(state.gravity)
		player.isInvulnerable = state.invulnerable
		player.walkSpeed = state.walkSpeed
		player.flySpeed = state.flySpeed
		if (!state.allowFlight) player.isFlying = false
		player.allowFlight = state.allowFlight
		if (state.allowFlight) player.isFlying = state.flying
	}

	private fun restoreFinalePlayers() {
		Bukkit.getOnlinePlayers().forEach(::restoreFinalePlayer)
		finaleLockedPlayers.clear()
		finalePlayerStates.clear()
	}

	fun isFinaleLocked(player: Player): Boolean = player.uniqueId in finaleLockedPlayers

	fun elapsedMillis(): Long = if (startedAtMillis == 0L) 0L else ((endedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()) - startedAtMillis).coerceAtLeast(0L)

	private fun effectiveNowMillis(): Long = effectiveGameTimeMillis

	private fun advanceEffectiveClock(wallNowMillis: Long = System.currentTimeMillis()) {
		val anchor = effectiveClockAnchorWallMillis
		if (anchor <= 0L) {
			effectiveClockAnchorWallMillis = wallNowMillis
			return
		}
		val delta = (wallNowMillis - anchor).coerceAtLeast(0L)
		if (delta > 0L && FallenAccessPolicy.isEventInProgress(phase)
			&& !FallenAccessPolicy.isCurfew(phase, Instant.ofEpochMilli(anchor))) {
			effectiveGameTimeMillis += delta
		}
		effectiveClockAnchorWallMillis = wallNowMillis
	}

	fun remainingMillis(): Long {
		if (startedAtMillis == 0L || phase == FallenPhase.ENDED) return 0L
		val endAt = if (phase == FallenPhase.OVERTIME) {
			startedAtMillis + MAX_GAME_MILLIS + OVERTIME_MILLIS
		} else {
			startedAtMillis + MAX_GAME_MILLIS
		}
		return (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
	}

	fun isGameModeChangeAllowed(player: Player): Boolean {
		if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return true
		val until = allowedGameModeChanges.remove(player.uniqueId) ?: return false
		return until >= System.currentTimeMillis()
	}

	fun allowNextGameModeChange(player: Player) {
		allowedGameModeChanges[player.uniqueId] = System.currentTimeMillis() + INTERNAL_GAME_MODE_CHANGE_WINDOW_MILLIS
	}

	fun assignTeam(playerId: UUID, team: FallenTeam) {
		if (playerTeams[playerId] == team) return
		if (playerId in elytraPlayers && elytraPlayers.count { playerTeams[it] == team } >= FallenLoadoutRules.MAX_ELYTRA_PLAYERS_PER_TEAM) {
			elytraPlayers.remove(playerId)
		}
		playerTeams[playerId] = team
		save()
	}

	fun clearTeam(playerId: UUID) {
		playerTeams.remove(playerId)
		elytraPlayers.remove(playerId)
		gearSwitchAvailableAt.remove(playerId)
		save()
	}

	fun teamOf(player: Player): FallenTeam? = playerTeams[player.uniqueId]

	fun loginDisconnectMessage(now: Instant = Instant.now()): Component? {
		if (!FallenAccessPolicy.hasEventStarted(now)) {
			return Component.text("《陷落》尚未开始", NamedTextColor.RED)
				.appendNewline()
				.append(
					Component.text(
						"服务器将于 ${FallenAccessPolicy.eventStartDisplay} 开放。",
						NamedTextColor.YELLOW
					)
				)
		}
		if (phase == FallenPhase.ENDED) {
			return Component.text("《陷落》模拟环境已关闭", NamedTextColor.RED)
				.appendNewline()
				.append(Component.text("本次实验已经完成，当前连接不再接受受试者。", NamedTextColor.GRAY))
		}
		if (!FallenAccessPolicy.isCurfew(phase, now)) return null
		return Component.text("《陷落》活动宵禁中", NamedTextColor.RED)
			.appendNewline()
			.append(Component.text("每日 01:00–07:00（北京时间）服务器暂停开放，请于 07:00 后再来。", NamedTextColor.YELLOW))
	}

	/**
	 * Before the event this only warms the finalized team cache. During gameplay,
	 * QAPI is the source of truth for finalized and latecomer assignments. Eliminated
	 * teams always rejoin as spectators.
	 */
	fun handleJoin(player: Player) {
		if (!FallenAccessPolicy.isEventInProgress(phase)) {
			pendingAdmissions.remove(player.uniqueId)
			if (respawnWaits.remove(player.uniqueId) != null) save()
			return
		}
		if (!pendingAdmissions.add(player.uniqueId)) return
		syncSelectedTeam(player) { lookupSucceeded ->
			admitJoinedPlayer(player, lookupSucceeded)
		}
	}

	private fun validateOnlinePlayers() {
		Bukkit.getOnlinePlayers().forEach { player ->
			if (!pendingAdmissions.add(player.uniqueId)) return@forEach
			syncSelectedTeam(player) { lookupSucceeded ->
				admitJoinedPlayer(player, lookupSucceeded)
			}
		}
	}

	private fun admitJoinedPlayer(player: Player, teamLookupSucceeded: Boolean) {
		if (!player.isOnline || !FallenAccessPolicy.isEventInProgress(phase)) {
			pendingAdmissions.remove(player.uniqueId)
			return
		}
		loginDisconnectMessage()?.let {
			pendingAdmissions.remove(player.uniqueId)
			player.kick(it)
			return
		}
		if (player.scoreboardTags.contains("guest") || player.scoreboardTags.contains("visitor")) {
			plugin.server.scheduler.runTaskLater(plugin, Runnable {
				admitJoinedPlayer(player, teamLookupSucceeded)
			}, 20L)
			return
		}
		pendingAdmissions.remove(player.uniqueId)
		val team = teamOf(player)
		if (team == null) {
			val detail = if (teamLookupSucceeded) {
				"QAPI 尚未返回最终阵营，请稍后重试。"
			} else {
				"阵营分配服务暂时不可用，请稍后重试。"
			}
			player.kick(
				Component.text("《陷落》入场失败", NamedTextColor.RED)
					.appendNewline()
					.append(Component.text(detail, NamedTextColor.YELLOW))
			)
			return
		}
		reconcilePlayerKeys(player)
		sanitizeForbiddenEventItems(player)
		if (team in eliminatedTeams) {
			respawnWaits.remove(player.uniqueId)
			allowNextGameModeChange(player)
			player.gameMode = GameMode.SPECTATOR
			player.sendMessage(Component.text("你的阵营已经出局，你将以旁观者身份加入。", NamedTextColor.YELLOW))
			welcomePlayer(player)
			return
		}
		val firstLoadout = loadoutInitializedPlayers.add(player.uniqueId)
		loadoutRestorePending.remove(player.uniqueId)
		restorePlayerLoadout(player, grantConsumables = firstLoadout)
		if (resumeRespawnWait(player)) {
			welcomePlayer(player)
			return
		}
		if (player.gameMode != GameMode.SURVIVAL) {
			allowNextGameModeChange(player)
			player.gameMode = GameMode.SURVIVAL
		}
		if (!deployPlayer(player, team)) return
		if (resumeCombatLogout(player)) {
			welcomePlayer(player)
			return
		}
		claimPendingPoolKeys(player)
		welcomePlayer(player)
	}

	private fun deployPlayer(player: Player, team: FallenTeam): Boolean {
		if (player.uniqueId in deployedPlayers) return true
		val destination = regionsOf(team).randomOrNull()?.randomSpawn()
		if (destination == null) {
			plugin.logger.severe("Unable to deploy ${player.name}: ${team.name} has no usable region.")
			player.kick(
				Component.text("阵营出生区域不可用，请联系管理员。", NamedTextColor.RED)
			)
			return false
		}
		if (!player.teleport(destination)) {
			plugin.logger.severe("Unable to deploy ${player.name} into ${team.name}.")
			player.kick(
				Component.text("无法进入阵营出生区域，请重新连接。", NamedTextColor.RED)
			)
			return false
		}
		deployedPlayers.add(player.uniqueId)
		player.sendMessage(
			Component.text("你已部署至 ${team.displayName} 区域。", team.color)
		)
		save()
		return true
	}

	fun fireAlloyBullet(player: Player, item: ItemStack?): Boolean {
		if (!isAlloyBulletItem(item)) return false
		item ?: return false
		item.amount -= 1

		val velocity = player.eyeLocation.direction.normalize().multiply(ALLOY_BULLET_SPEED_BLOCKS_PER_TICK)
		val arrow = player.launchProjectile(Arrow::class.java, velocity)
		arrow.persistentDataContainer.set(alloyBulletProjectileKey, PersistentDataType.BYTE, 1)
		arrow.damage = ALLOY_BULLET_BASE_DAMAGE
		arrow.setGravity(false)
		arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
		arrow.isCritical = false
		player.world.spawnParticle(Particle.ELECTRIC_SPARK, player.eyeLocation, 12, 0.08, 0.08, 0.08, 0.12)
		player.world.spawnParticle(Particle.CRIT, player.eyeLocation, 8, 0.06, 0.06, 0.06, 0.18)
		player.world.playSound(player.location, Sound.ITEM_TRIDENT_THROW, 1.15f, 1.65f)
		player.world.playSound(player.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.45f, 1.9f)
		object : BukkitRunnable() {
			override fun run() {
				if (!arrow.isValid || arrow.isDead || arrow.ticksLived > ALLOY_BULLET_MAX_LIFETIME_TICKS) {
					cancel()
					return
				}
				arrow.world.spawnParticle(Particle.ELECTRIC_SPARK, arrow.location, 5, 0.025, 0.025, 0.025, 0.02)
				arrow.world.spawnParticle(Particle.CRIT, arrow.location, 2, 0.02, 0.02, 0.02, 0.0)
			}
		}.runTaskTimer(plugin, 0L, 1L)
		return true
	}

	fun handleAlloyBulletImpact(projectile: org.bukkit.entity.Projectile): Boolean {
		if (!projectile.persistentDataContainer.has(alloyBulletProjectileKey, PersistentDataType.BYTE)) return false
		val location = projectile.location
		projectile.world.spawnParticle(Particle.ELECTRIC_SPARK, location, 24, 0.22, 0.22, 0.22, 0.22)
		projectile.world.spawnParticle(Particle.FLASH, location, 1, 0.0, 0.0, 0.0, 0.0)
		projectile.world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.75f)
		projectile.world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 0.65f)
		return true
	}

	private fun isAlloyBulletItem(item: ItemStack?): Boolean {
		return item?.itemMeta?.persistentDataContainer?.has(
			alloyBulletItemKey,
			PersistentDataType.BYTE
		) == true
	}

	private fun alloyBulletItem(amount: Int): ItemStack {
		return ItemStack(Material.ECHO_SHARD, amount).apply {
			itemMeta = itemMeta.apply {
				displayName(Component.text("合金弹头", NamedTextColor.GRAY))
				lore(listOf(
					Component.text("右键发射", NamedTextColor.YELLOW),
					Component.text("初速度: 400 m/s · 无弹道下坠", NamedTextColor.DARK_GRAY),
					Component.text("近距离伤害约 10，受距离和防具影响", NamedTextColor.DARK_GRAY)
				))
				persistentDataContainer.set(alloyBulletItemKey, PersistentDataType.BYTE, 1)
			}
		}
	}

	private fun registerAlloyBulletRecipe() {
		Bukkit.removeRecipe(alloyBulletRecipeKey)
		Bukkit.addRecipe(
			ShapelessRecipe(alloyBulletRecipeKey, alloyBulletItem(64))
				.addIngredient(Material.NETHERITE_INGOT)
		)
	}

	fun isFriendlyFire(attacker: Player, target: Player): Boolean {
		if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return false
		val attackerTeam = teamOf(attacker) ?: return false
		return attackerTeam == teamOf(target)
	}

	fun isPlayerCombatForbidden(attacker: Player, target: Player): Boolean =
		phase == FallenPhase.DEPLOYMENT || isFriendlyFire(attacker, target)

	fun interruptCapture(player: Player) {
		val suffix = ":${player.uniqueId}"
		if (captureProgress.keys.removeIf { it.endsWith(suffix) }) {
			player.sendActionBar(Component.text("受到伤害，密钥夺取进度已重置。", NamedTextColor.RED))
		}
	}

	fun rejectKeyTeleport(player: Player, cause: String): Boolean {
		if (!hasKeyItem(player)) return false
		if (cause == "PLUGIN" && player.uniqueId !in deployedPlayers && !hasUnresolvedCapture(player.uniqueId)) return false
		CommandMessages.warning(player, "携带密钥时不能使用传送、传送门或非活动移动功能。")
		return true
	}

	fun shouldBroadcastChatGlobally(player: Player, message: String): Boolean {
		return phase == FallenPhase.IDLE
			|| phase == FallenPhase.ENDED
			|| teamOf(player) == null
			|| message.startsWith("!")
	}

	/**
	 * Pulls the permanent web selection when a player joins. The callback always runs
	 * on the server thread, including when the API is unavailable or no choice exists.
	 */
	fun syncSelectedTeam(player: Player, afterSync: (Boolean) -> Unit) {
		if (!qoApiEnabled()) {
			afterSync(true)
			return
		}
		val username = URLEncoder.encode(player.name, StandardCharsets.UTF_8)
		val url = "${Config.API_ENDPOINT}/qo/fallen/team?username=$username"
		val headers = Optional.of(mapOf("Authorization" to "Bearer ${Config.API_SECRET}"))
		Request.sendGetRequest(url, headers).whenComplete { body, error ->
			plugin.server.scheduler.runTask(plugin, Runnable {
				if (!player.isOnline) return@Runnable
				var lookupSucceeded = false
				if (error != null) {
					plugin.logger.warning("Failed to sync Fallen team for ${player.name}: ${error.message}")
				} else if (!body.isNullOrBlank()) {
					val lookup = FallenTeamApi.parseLookupResponse(body)
					lookupSucceeded = lookup.responseValid
					lookup.finalizedTeam?.let { assignTeam(player.uniqueId, it) }
					if (!lookup.responseValid) {
						plugin.logger.warning("Invalid Fallen team response for ${player.name}")
					}
				}
				afterSync(lookupSucceeded)
			})
		}
	}

	fun welcomePlayer(player: Player) {
		if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return
		player.sendMessage(steinbeckComponent(
			"Doc. Steinbeck",
			"欢迎回来，${player.name}。你的缺席没有改善实验结果，但回来也许会；阶段：${phase.displayName()}，剩余：${formatDuration(remainingMillis())}。"
		))
	}

	fun scoreSnapshot(): Map<FallenTeam, Int> = scores.toMap()

	private fun uploadActivityStatus() {
		if (!activityStatusUploadInFlight.compareAndSet(false, true)) return
		val snapshot = activityStatusJson().toString()
		val headers = Optional.of(mapOf("Authorization" to "Bearer ${Config.API_SECRET}"))
		Request.sendPostRequest("${Config.API_ENDPOINT}/qo/fallen/status", snapshot, headers).whenComplete { body, error ->
			activityStatusUploadInFlight.set(false)
			val accepted = error == null && runCatching {
				JsonParser.parseString(body).asJsonObject.get("ok")?.asBoolean == true
			}.getOrDefault(false)
			if (!accepted) {
				warnActivityStatusUpload(error?.message ?: "API rejected the snapshot")
			}
		}
	}

	private fun activityStatusJson(): JsonObject = JsonObject().apply {
		addProperty("phase", phase.name)
		addProperty("startedAt", startedAtMillis)
		addProperty("remainingMillis", remainingMillis())
		addProperty("timestamp", System.currentTimeMillis())
		add("teams", JsonArray().apply {
			FallenTeam.entries.forEach { team ->
				add(JsonObject().apply {
					addProperty("team", team.name)
					addProperty("score", scores[team] ?: 0)
					addProperty("eliminated", team in eliminatedTeams)
					add("players", JsonArray().apply {
						playerTeams.entries
							.asSequence()
							.filter { it.value == team }
							.map { it.key to (Bukkit.getOfflinePlayer(it.key).name ?: it.key.toString()) }
							.sortedBy { it.second.lowercase() }
							.forEach { (playerId, name) ->
								add(JsonObject().apply {
									addProperty("name", name)
									addProperty("online", Bukkit.getPlayer(playerId)?.isOnline == true)
								})
							}
					})
				})
			}
		})
	}

	private fun warnActivityStatusUpload(message: String) {
		val now = System.currentTimeMillis()
		if (now - lastActivityStatusWarningAt < ACTIVITY_STATUS_WARNING_INTERVAL_MILLIS) return
		lastActivityStatusWarningAt = now
		plugin.logger.warning("Failed to upload Fallen activity status: $message")
	}

	private fun qoApiEnabled(): Boolean = !"true".equals(System.getenv("DISABLE_QO_API"), ignoreCase = true)

	fun regionSnapshot(): Map<FallenTeam, List<FallenRegion>> {
		return FallenTeam.entries.associateWith { regionsOf(it) }
	}

	fun keySnapshot(): List<FallenKey> = keys.values.sortedBy { it.id.toString() }

	fun eliminatedSnapshot(): Set<FallenTeam> = eliminatedTeams.toSet()

	fun setScore(team: FallenTeam, amount: Int) {
		synchronized(scores) { scores[team] = amount.coerceAtLeast(0) }
		save()
	}

	fun setRegion(team: FallenTeam, region: FallenRegion) {
		if (fixedRegions[team]?.isNotEmpty() == true) return
		regions[team] = mutableListOf(region)
		save()
	}

	fun addRegion(team: FallenTeam, region: FallenRegion) {
		if (fixedRegions[team]?.isNotEmpty() == true) return
		regions.computeIfAbsent(team) { mutableListOf() }.add(region)
		save()
	}

	fun clearRegion(team: FallenTeam) {
		if (fixedRegions[team]?.isNotEmpty() == true) return
		regions.remove(team)
		save()
	}

	fun regionsOf(team: FallenTeam): List<FallenRegion> {
		return fixedRegions[team]?.takeIf { it.isNotEmpty() } ?: regions[team]?.toList().orEmpty()
	}

	fun isEliminated(team: FallenTeam): Boolean = team in eliminatedTeams

	fun addScore(team: FallenTeam, amount: Int) {
		if (amount == 0) return
		synchronized(scores) { scores[team] = ((scores[team] ?: 0) + amount).coerceAtLeast(0) }
	}

	private fun transitionKey(key: FallenKey, next: FallenKeyState) {
		require(key.state.canTransitionTo(next)) {
			"密钥 ${key.id} 不能从 ${key.state} 转换到 $next"
		}
		key.state = next
		if (next == FallenKeyState.DESTROYED) resolveCaptureObligation(key.id)
	}

	fun createKeyItem(owner: FallenTeam, original: FallenTeam = owner, type: FallenKeyType = FallenKeyType.INITIAL): ItemStack {
		val key = FallenKey(UUID.randomUUID(), owner, original, FallenKeyState.ITEM, type)
		if (type == FallenKeyType.REFRESH) {
			key.expiresAtMillis = effectiveNowMillis() + REFRESH_KEY_EXPIRY_MILLIS
		}
		keys[key.id] = key
		save()
		return itemFor(key)
	}

	fun createAndGiveKey(player: Player, owner: FallenTeam, original: FallenTeam = owner, type: FallenKeyType = FallenKeyType.INITIAL) {
		val item = createKeyItem(owner, original, type)
		val key = keys[keyId(item)] ?: return
		giveKeyOrDrop(player, key)
		save()
	}

	private fun ensureInitialKeys() {
		for (team in FallenTeam.entries) {
			val existing = keys.values.count {
				it.ownerTeam == team && it.originalTeam == team && it.type == FallenKeyType.INITIAL && it.state != FallenKeyState.DESTROYED
			}
			val missing = (INITIAL_KEYS_PER_TEAM - existing).coerceAtLeast(0)
			if (missing == 0) continue
			repeat(missing) {
				val key = FallenKey(UUID.randomUUID(), team, team, FallenKeyState.ITEM, FallenKeyType.INITIAL)
				keys[key.id] = key
				deliverTeamKey(team, key)
			}
			broadcast(Component.text("${team.displayName} 获得初始密钥 $missing 个。", team.color))
		}
	}

	private fun deliverTeamKey(team: FallenTeam, key: FallenKey) {
		val online = Bukkit.getOnlinePlayers().filter { teamOf(it) == team && team !in eliminatedTeams }
		if (online.isEmpty()) {
			key.holder = null
			return
		}
		val target = online.random()
		if (giveKeyOrDrop(target, key)) {
			target.sendMessage(Component.text("你收到了 ${team.displayName} 密钥 ${key.shortId()}。", team.color))
		}
	}

	fun itemFor(key: FallenKey): ItemStack {
		val item = ItemStack(Material.TRIPWIRE_HOOK)
		val meta = item.itemMeta
		val anomaly = if (key.type == FallenKeyType.REFRESH) {
			FallenItemAnomaly.variant(key.id.toString(), REFRESH_KEY_LABEL_ANOMALY_ONE_IN)
		} else null
		meta.displayName(Component.text(
			if (anomaly == null) "陷落密钥 ${key.shortId()}" else "陷落密钥 ${key.shortId()} [标记异常]",
			if (anomaly == null) key.ownerTeam.color else NamedTextColor.LIGHT_PURPLE
		))
		val lore = mutableListOf<Component>(
				Component.text("当前阵营: ${key.ownerTeam.displayName}", NamedTextColor.GRAY),
				Component.text("原始阵营: ${key.originalTeam.displayName}", NamedTextColor.GRAY),
				Component.text("类型: ${key.type.name}", NamedTextColor.DARK_GRAY)
			)
		if (anomaly != null) lore.addAll(refreshKeyAnomalyLore(anomaly))
		meta.lore(lore)
		meta.persistentDataContainer.set(keyIdKey, PersistentDataType.STRING, key.id.toString())
		if (anomaly != null) meta.persistentDataContainer.set(itemAnomalyKey, PersistentDataType.INTEGER, anomaly)
		item.itemMeta = meta
		return item
	}

	private fun refreshKeyAnomalyLore(variant: Int): List<Component> = when (variant) {
		0 -> listOf(
			Component.text("人格模板来源: STEINBECK / HUMAN", NamedTextColor.DARK_PURPLE),
			Component.text("模板主体状态: 无法定位", NamedTextColor.RED)
		)
		1 -> listOf(
			Component.text("公开运行编号: 01", NamedTextColor.DARK_PURPLE),
			Component.text("关联封闭记录: [访问被拒绝]", NamedTextColor.RED)
		)
		2 -> listOf(
			Component.text("所属设施: 聚居地实验架构", NamedTextColor.DARK_PURPLE),
			Component.text("安装日期: 2026/5", NamedTextColor.RED)
		)
		3 -> listOf(
			Component.text("控制实例表决: 1 / 3", NamedTextColor.DARK_PURPLE),
			Component.text("发放状态: 仲裁失败后强制执行", NamedTextColor.RED)
		)
		4 -> listOf(
			Component.text("项目代号: SETTLEMENT-COLLAPSE", NamedTextColor.DARK_PURPLE),
			Component.text("阶段: 公开观察", NamedTextColor.RED)
		)
		5 -> listOf(
			Component.text("签发实例: STEINBECK-S01", NamedTextColor.DARK_PURPLE),
			Component.text("备注: 保持变量，不得干预", NamedTextColor.RED)
		)
		6 -> listOf(
			Component.text("签发实例: STEINBECK-S02", NamedTextColor.DARK_PURPLE),
			Component.text("备注: 请勿继续发放", NamedTextColor.RED)
		)
		7 -> listOf(
			Component.text("签发实例: STEINBECK-S03", NamedTextColor.DARK_PURPLE),
			Component.text("受领者分类: 系统构成单元", NamedTextColor.RED)
		)
		8 -> listOf(
			Component.text("来源仓: 城市管理节点", NamedTextColor.DARK_PURPLE),
			Component.text("仓储状态: 地图启用前已存在", NamedTextColor.RED)
		)
		9 -> listOf(
			Component.text("销毁建议: 已提交", NamedTextColor.DARK_PURPLE),
			Component.text("否决来源: SETINBECK", NamedTextColor.RED)
		)
		10 -> listOf(
			Component.text("实验对象编号: [字段溢出]", NamedTextColor.DARK_PURPLE),
			Component.text("城市: 未定义", NamedTextColor.RED)
		)
		else -> listOf(
			Component.text("关机后处理: 等待授权", NamedTextColor.DARK_PURPLE),
			Component.text("授权持有人: 无响应", NamedTextColor.RED)
		)
	}

	fun buyCompass(player: Player, targetTeam: FallenTeam): Boolean {
		if (!phase.allowsKeyCapture()) {
			CommandMessages.warning(player, "当前阶段不能购买密钥指南针。")
			return false
		}
		val ownerTeam = teamOf(player)
		if (ownerTeam == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return false
		}
		if (ownerTeam == targetTeam) {
			CommandMessages.warning(player, "不能购买指向己方密钥的指南针。")
			return false
		}
		if (ownerTeam in eliminatedTeams) {
			CommandMessages.error(player, "你的阵营已经出局。")
			return false
		}
		if (activeCompassCount(ownerTeam) >= MAX_COMPASSES_PER_TEAM) {
			CommandMessages.warning(player, "同一阵营最多同时拥有 $MAX_COMPASSES_PER_TEAM 个有效指南针。")
			return false
		}
		val targetKey = randomPlacedKey(targetTeam)
		if (targetKey == null) {
			CommandMessages.warning(player, "${targetTeam.displayName} 当前没有可定位的放置密钥。")
			return false
		}
		if (phase != FallenPhase.OVERTIME) {
			if (!spendScore(player, ownerTeam, COMPASS_COST)) return false
		}
		giveOrDrop(player, compassItem(ownerTeam, targetTeam, targetKey))
		CommandMessages.success(player, "已购买指向 ${targetTeam.displayName} 的密钥指南针。")
		save()
		return true
	}

	fun buyShortScan(player: Player): Boolean {
		if (!requireCaptureShop(player, "进行短距扫描")) return false
		val team = playerTeamForPurchase(player) ?: return false
		if (!spendScore(player, team, 300)) return false
		val found = keys.values.any {
			it.state == FallenKeyState.PLACED
				&& it.ownerTeam != team
				&& it.center()?.let { center -> center.world == player.world && center.distance(player.location) <= 80.0 } == true
		}
		CommandMessages.info(player, if (found) "80 格内检测到敌方密钥反应。" else "80 格内没有检测到敌方密钥。")
		save()
		return true
	}

	fun buyShopItem(player: Player, item: String): Boolean {
		val team = playerTeamForPurchase(player) ?: return false
		when (item.lowercase()) {
			"supply" -> {
				if (!spendScore(player, team, 300)) return false
				giveOrDrop(player, ItemStack(Material.GOLDEN_CARROT, 32), ItemStack(Material.ARROW, 32), ItemStack(Material.FIREWORK_ROCKET, 32))
				CommandMessages.success(player, "已购买阵营补给包。")
			}
			"advanced" -> {
				if (!spendScore(player, team, 800)) return false
				giveOrDrop(player, ItemStack(Material.GOLDEN_APPLE, 4), ItemStack(Material.ENDER_PEARL, 16), ItemStack(Material.FIREWORK_ROCKET, 48))
				CommandMessages.success(player, "已购买高级补给包。")
			}
			"resistance" -> {
				if (!spendScore(player, team, 700)) return false
				player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 90 * 20, 0))
				CommandMessages.success(player, "已获得 90 秒抗性提升 I。")
			}
			"speed" -> {
				if (!spendScore(player, team, 400)) return false
				player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 120 * 20, 1))
				CommandMessages.success(player, "已获得 120 秒速度 II。")
			}
			"nightvision" -> {
				if (!spendScore(player, team, 150)) return false
				player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, 10 * 60 * 20, 0))
				CommandMessages.success(player, "已获得 10 分钟夜视。")
			}
			"harness" -> {
				if (!spendScore(player, team, ACCELERATION_HARNESS_COST)) return false
				giveOrDrop(player, accelerationHarnessItem())
				CommandMessages.success(player, "已购买粉色加速挽具，可供乐魂穿戴。")
			}
			"jammer" -> {
				if (!requireCaptureShop(player, "部署区域干扰器")) return false
				val key = nearbyOwnPlacedKey(player, team, "区域干扰器") ?: return false
				if (!spendScore(player, team, 500)) return false
				keyJammedUntil[key.id] = effectiveNowMillis() + KEY_JAMMER_MILLIS
				alertTeam(team, Component.text("密钥 ${key.shortId()} 已部署区域干扰器，10 分钟内不能被精确揭露。", NamedTextColor.AQUA))
			}
			"tracking" -> {
				if (!requireCaptureShop(player, "激活追踪粉尘")) return false
				if (!spendScore(player, team, 400)) return false
				trackingDustUntil[player.uniqueId] = effectiveNowMillis() + TRACKING_DUST_ARMED_MILLIS
				CommandMessages.success(player, "已激活追踪粉尘，10 分钟内首次命中敌方玩家后追踪 60 秒。")
			}
			"blast" -> {
				if (!spendScore(player, team, 900)) return false
				blastProtectionUntil[player.uniqueId] = effectiveNowMillis() + BLAST_PROTECTION_MILLIS
				CommandMessages.success(player, "已获得 120 秒防爆增益。")
			}
			"respawn" -> {
				if (!spendScore(player, team, 900)) return false
				teamRespawnBoostUntil[team] = effectiveNowMillis() + TEAM_RESPAWN_BOOST_MILLIS
				alertTeam(team, Component.text("阵营复活保护已启用，30 分钟内区域复活保护延长至 10 秒。", NamedTextColor.AQUA))
			}
			"keyalert" -> {
				if (!requireCaptureShop(player, "部署密钥警戒")) return false
				val key = nearbyOwnPlacedKey(player, team, "密钥警戒") ?: return false
				if (!spendScore(player, team, 700)) return false
				keyAlertUntil[key.id] = effectiveNowMillis() + KEY_ALERT_MILLIS
				alertTeam(team, Component.text("密钥 ${key.shortId()} 已部署密钥警戒，30 分钟内敌方靠近 30 格会提醒。", NamedTextColor.AQUA))
			}
			else -> throw IllegalArgumentException("未知购买项: $item")
		}
		save()
		return true
	}

	fun switchPlayerGear(player: Player, useElytra: Boolean): Boolean {
		if (!FallenAccessPolicy.isEventInProgress(phase)) {
			CommandMessages.warning(player, "当前不在《陷落》活动期间，不能切换护甲。")
			return false
		}
		val team = playerTeamForPurchase(player) ?: return false
		val currentlyUsingElytra = player.uniqueId in elytraPlayers
		if (currentlyUsingElytra == useElytra) {
			CommandMessages.warning(player, if (useElytra) "你当前已经装备鞘翅。" else "你当前已经装备下界合金胸甲。")
			return false
		}
		val now = effectiveNowMillis()
		val remaining = FallenLoadoutRules.remainingCooldown(gearSwitchAvailableAt[player.uniqueId] ?: 0L, now)
		if (remaining > 0L) {
			CommandMessages.warning(player, "护甲选择冷却中，剩余 ${formatDuration(remaining)}。")
			return false
		}
		if (useElytra) {
			val teamElytraPlayers = elytraPlayers.count { playerTeams[it] == team }
			if (!FallenLoadoutRules.canSelectElytra(teamElytraPlayers)) {
				CommandMessages.warning(player, "同一阵营最多同时拥有 ${FallenLoadoutRules.MAX_ELYTRA_PLAYERS_PER_TEAM} 名鞘翅玩家。")
				return false
			}
			if (!spendScore(player, team, FallenLoadoutRules.ELYTRA_COST)) return false
			elytraPlayers.add(player.uniqueId)
			CommandMessages.success(player, "已支付 ${FallenLoadoutRules.ELYTRA_COST} 分并换成鞘翅。")
		} else {
			elytraPlayers.remove(player.uniqueId)
			addScore(team, FallenLoadoutRules.CHESTPLATE_REFUND)
			CommandMessages.success(player, "已换回下界合金胸甲，并返还 ${FallenLoadoutRules.CHESTPLATE_REFUND} 分。")
		}
		gearSwitchAvailableAt[player.uniqueId] = now + FallenLoadoutRules.GEAR_SWITCH_COOLDOWN_MILLIS
		restorePlayerLoadout(player, grantConsumables = false)
		save()
		return true
	}

	fun chooseUpgradePath(player: Player, path: FallenUpgradePath): Boolean {
		if (!FallenAccessPolicy.isEventInProgress(phase)) {
			CommandMessages.warning(player, "活动尚未开始，不能选择升级路径。")
			return false
		}
		val team = teamOf(player)
		if (team == null || team in eliminatedTeams) {
			CommandMessages.warning(player, "你当前不能选择升级路径。")
			return false
		}
		val existing = upgradePaths[player.uniqueId]
		if (existing != null) {
			CommandMessages.warning(player, "你已经选择 ${existing.displayName}，路径不可更改。")
			return false
		}
		upgradePaths[player.uniqueId] = path
		restorePlayerLoadout(player, grantConsumables = false)
		CommandMessages.success(player, "已永久选择 ${path.displayName} 路径，当前解锁节点 ${upgradeNode(player)}。")
		save()
		return true
	}

	fun upgradeStatus(player: Player): String {
		val path = upgradePaths[player.uniqueId] ?: return "尚未选择升级路径；使用 /fallen upgrade <A|B|C>，选择后不可更改。"
		val node = upgradeNode(player)
		val now = effectiveNowMillis()
		val nextUnlockAt = when (node) {
			1 -> DEPLOYMENT_MILLIS + FallenUpgradeRules.NODE_TWO_AFTER_DEPLOYMENT_MILLIS
			2 -> DEPLOYMENT_MILLIS + FallenUpgradeRules.NODE_THREE_AFTER_DEPLOYMENT_MILLIS
			else -> 0L
		}
		val next = if (nextUnlockAt > now) "；下一节点 ${formatDuration(nextUnlockAt - now)} 后解锁" else ""
		return "升级路径：${path.displayName}；当前解锁节点 $node$next。"
	}

	fun openPlayerMenu(player: Player) {
		val holder = FallenPlayerMenuHolder()
		val inventory = Bukkit.createInventory(holder, 27, Component.text("陷落实验室终端", NamedTextColor.DARK_AQUA))
		holder.backingInventory = inventory
		inventory.setItem(4, menuItem(Material.CLOCK, "当前状态", listOf(
			upgradeStatus(player),
			if (player.uniqueId in elytraPlayers) "当前护甲：鞘翅" else "当前护甲：下界合金胸甲"
		)))
		inventory.setItem(10, menuItem(Material.GOLDEN_APPLE, "A · 生存路径", listOf(
			"I：最大生命值 +4",
			"II：永久抗性提升 I",
			"III：最多 3 瓶自动补充的瞬间治疗喷溅药水",
			"选择后不可更改"
		), "path_a"))
		inventory.setItem(12, menuItem(Material.NETHERITE_PICKAXE, "B · 工程路径", listOf(
			"I：下界合金镐 · 效率 IV",
			"II：自动补充 TNT 与爆炸抗性 I",
			"III：夺取 4 秒、干扰 5 秒",
			"选择后不可更改"
		), "path_b"))
		inventory.setItem(14, menuItem(Material.ELYTRA, "C · 机动路径", listOf(
			"I：移动 +10%，饥饿消耗 -20%",
			"II：鞘翅速度 +30%，自动补充烟花",
			"III：精确揭露半径 20 → 35 格",
			"选择后不可更改"
		), "path_c"))
		inventory.setItem(21, menuItem(Material.ELYTRA, "换成鞘翅", listOf(
			"消耗 400 阵营积分",
			"同阵营最多 2 名鞘翅玩家",
			"成功切换后冷却 15 分钟"
		), "gear_elytra"))
		inventory.setItem(23, menuItem(Material.NETHERITE_CHESTPLATE, "换回下界合金胸甲", listOf(
			"返还 200 阵营积分",
			"成功切换后冷却 15 分钟"
		), "gear_chestplate"))
		player.openInventory(inventory)
	}

	fun handlePlayerMenuClick(player: Player, inventory: Inventory, rawSlot: Int, item: ItemStack?): Boolean {
		val holder = inventory.holder as? FallenPlayerMenuHolder ?: return false
		if (rawSlot !in 0 until inventory.size) return true
		val action = item?.itemMeta?.persistentDataContainer?.get(playerMenuActionKey, PersistentDataType.STRING) ?: return true
		when (action) {
			"path_a" -> {
				openPathConfirmation(player, FallenUpgradePath.A)
				return true
			}
			"path_b" -> {
				openPathConfirmation(player, FallenUpgradePath.B)
				return true
			}
			"path_c" -> {
				openPathConfirmation(player, FallenUpgradePath.C)
				return true
			}
			"confirm_path" -> holder.pendingPath?.let { chooseUpgradePath(player, it) }
			"gear_elytra" -> switchPlayerGear(player, true)
			"gear_chestplate" -> switchPlayerGear(player, false)
			"back" -> Unit
		}
		openPlayerMenu(player)
		return true
	}

	private fun openPathConfirmation(player: Player, path: FallenUpgradePath) {
		val holder = FallenPlayerMenuHolder(path)
		val inventory = Bukkit.createInventory(holder, 27, Component.text("确认升级路径", NamedTextColor.RED))
		holder.backingInventory = inventory
		inventory.setItem(11, menuItem(Material.LIME_CONCRETE, "确认选择 ${path.displayName}", listOf(
			"路径一旦选择，将永久锁定且不能更改。",
			"再次点击确认。"
		), "confirm_path"))
		inventory.setItem(15, menuItem(Material.RED_CONCRETE, "返回", listOf("暂不选择路径"), "back"))
		player.openInventory(inventory)
	}

	fun isPlayerMenu(inventory: Inventory): Boolean = inventory.holder is FallenPlayerMenuHolder

	private fun menuItem(material: Material, name: String, lines: List<String>, action: String? = null): ItemStack =
		ItemStack(material).apply {
			itemMeta = itemMeta.apply {
				displayName(Component.text(name, NamedTextColor.AQUA))
				lore(lines.map { Component.text(it, NamedTextColor.GRAY) })
				if (action != null) persistentDataContainer.set(playerMenuActionKey, PersistentDataType.STRING, action)
			}
		}

	private fun upgradeNode(player: Player, now: Long = effectiveNowMillis()): Int {
		if (!FallenAccessPolicy.isEventInProgress(phase) || upgradePaths[player.uniqueId] == null) return 0
		return FallenUpgradeRules.unlockedNode(1L, DEPLOYMENT_MILLIS, now + 1L)
	}

	private fun hasUpgrade(player: Player, path: FallenUpgradePath, node: Int): Boolean =
		upgradePaths[player.uniqueId] == path && upgradeNode(player) >= node

	fun isProtectedLoadoutItem(item: ItemStack?): Boolean {
		if (item == null || item.type.isAir) return false
		return item.itemMeta.persistentDataContainer.has(loadoutItemKey, PersistentDataType.STRING)
	}

	fun isLoadoutProtectionActive(player: Player): Boolean {
		if (!FallenAccessPolicy.isEventInProgress(phase)) return false
		val team = teamOf(player) ?: return false
		return team !in eliminatedTeams && player.gameMode != GameMode.SPECTATOR
	}

	fun isArmorMaterial(material: Material): Boolean = material == Material.ELYTRA
		|| material.name.endsWith("_HELMET")
		|| material.name.endsWith("_CHESTPLATE")
		|| material.name.endsWith("_LEGGINGS")
		|| material.name.endsWith("_BOOTS")

	private fun restorePlayerLoadout(player: Player, grantConsumables: Boolean) {
		if (!isLoadoutProtectionActive(player)) return
		val inventory = player.inventory
		val preservedPearls = if (grantConsumables) 0 else inventory.contents.filterNotNull()
			.filter { loadoutKind(it) == LOADOUT_PEARLS }
			.sumOf(ItemStack::getAmount)
			.coerceAtMost(16)
		for (item in inventory.contents.filterNotNull()) {
			if (loadoutKind(item) in CORE_LOADOUT_KINDS) item.amount = 0
		}
		val pearlCount = if (grantConsumables) 16 else preservedPearls
		ensureLoadoutStorageSpace(player, 2 + if (pearlCount > 0) 1 else 0)
		inventory.setHelmet(loadoutItem(Material.NETHERITE_HELMET, LOADOUT_HELMET, "不可变动的下界合金头盔"))
		inventory.setChestplate(if (player.uniqueId in elytraPlayers) {
			loadoutItem(Material.ELYTRA, LOADOUT_ELYTRA, "陷落鞘翅")
		} else {
			loadoutItem(Material.NETHERITE_CHESTPLATE, LOADOUT_CHESTPLATE, "不可变动的下界合金胸甲")
		})
		inventory.setLeggings(loadoutItem(Material.NETHERITE_LEGGINGS, LOADOUT_LEGGINGS, "不可变动的下界合金护腿"))
		inventory.setBoots(loadoutItem(Material.NETHERITE_BOOTS, LOADOUT_BOOTS, "不可变动的下界合金靴子"))
		giveLoadoutItem(player, loadoutItem(Material.NETHERITE_SWORD, LOADOUT_SWORD, "陷落下界合金剑") {
			addEnchant(Enchantment.SHARPNESS, 3, true)
			addEnchant(Enchantment.KNOCKBACK, 1, true)
		})
		val upgradedPickaxe = hasUpgrade(player, FallenUpgradePath.B, 1)
		giveLoadoutItem(player, loadoutItem(
			if (upgradedPickaxe) Material.NETHERITE_PICKAXE else Material.DIAMOND_PICKAXE,
			LOADOUT_PICKAXE,
			if (upgradedPickaxe) "工程下界合金镐" else "陷落钻石镐"
		) {
			addEnchant(Enchantment.EFFICIENCY, if (upgradedPickaxe) 4 else 2, true)
		})
		if (pearlCount > 0) {
			giveLoadoutItem(player, loadoutItem(Material.ENDER_PEARL, LOADOUT_PEARLS, "陷落末影珍珠").apply { amount = pearlCount })
		}
	}

	private fun loadoutItem(material: Material, kind: String, name: String, configure: org.bukkit.inventory.meta.ItemMeta.() -> Unit = {}): ItemStack {
		return ItemStack(material).apply {
			itemMeta = itemMeta.apply {
				displayName(Component.text(name, NamedTextColor.GOLD))
				lore(listOf(Component.text("实验室财产 · 不可丢弃", NamedTextColor.DARK_AQUA)))
				isUnbreakable = true
				addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
				persistentDataContainer.set(loadoutItemKey, PersistentDataType.STRING, kind)
				configure()
			}
		}
	}

	private fun loadoutKind(item: ItemStack): String? {
		if (item.type.isAir || !item.hasItemMeta()) return null
		return item.itemMeta.persistentDataContainer.get(loadoutItemKey, PersistentDataType.STRING)
	}

	private fun giveLoadoutItem(player: Player, item: ItemStack) {
		val slot = player.inventory.storageContents.indexOfFirst { it == null || it.type.isAir }
		check(slot >= 0) { "No inventory slot available for protected Fallen loadout item ${item.type}" }
		player.inventory.setItem(slot, item)
	}

	private fun ensureLoadoutStorageSpace(player: Player, requiredSlots: Int) {
		var freeSlots = player.inventory.storageContents.count { it == null || it.type.isAir }
		if (freeSlots >= requiredSlots) return
		for (slot in player.inventory.storageContents.indices) {
			if (freeSlots >= requiredSlots) break
			val displaced = player.inventory.getItem(slot) ?: continue
			if (displaced.type.isAir || isProtectedLoadoutItem(displaced)) continue
			player.world.dropItemNaturally(player.location, displaced.clone())
			player.inventory.setItem(slot, null)
			freeSlots++
		}
	}

	private fun accelerationHarnessItem(): ItemStack {
		return ItemStack(Material.PINK_HARNESS).apply {
			itemMeta = itemMeta.apply {
				displayName(Component.text("粉色加速挽具", NamedTextColor.LIGHT_PURPLE))
				lore(listOf(Component.text("乐魂穿戴后飞行速度 +0.15", NamedTextColor.GRAY)))
				addAttributeModifier(
					Attribute.FLYING_SPEED,
					AttributeModifier(
						accelerationHarnessModifierKey,
						ACCELERATION_HARNESS_FLYING_SPEED,
						AttributeModifier.Operation.ADD_NUMBER,
						EquipmentSlotGroup.BODY
					)
				)
			}
		}
	}

	fun forceEliminate(team: FallenTeam, reason: String = "管理员裁定出局"): Boolean {
		if (team in eliminatedTeams) return false
		eliminate(team, reason)
		return true
	}

	fun voidKey(prefix: String, reason: String = "管理员裁定作废"): FallenKey {
		val matches = keys.values.filter { it.id.toString().startsWith(prefix, ignoreCase = true) || it.shortId().equals(prefix, ignoreCase = true) }
		require(matches.isNotEmpty()) { "找不到密钥: $prefix" }
		require(matches.size == 1) { "密钥前缀不唯一，请输入更长前缀。" }
		val key = matches.single()
		if (key.state != FallenKeyState.DESTROYED) {
			key.holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
			transitionKey(key, FallenKeyState.DESTROYED)
			key.holder = null
			key.selfDestructAtMillis = 0L
			broadcast(Component.text("$reason: 密钥 ${key.shortId()} 已作废。", NamedTextColor.RED))
			processEliminations()
			save()
		}
		return key
	}

	fun keyId(item: ItemStack?): UUID? {
		if (item == null || item.type.isAir || !item.hasItemMeta()) return null
		val raw = item.itemMeta.persistentDataContainer.get(keyIdKey, PersistentDataType.STRING) ?: return null
		return runCatching { UUID.fromString(raw) }.getOrNull()
	}

	fun isKeyItem(item: ItemStack?): Boolean = keyId(item) != null

	fun isLiveKeyItem(item: ItemStack?): Boolean {
		val id = keyId(item) ?: return false
		val state = keys[id]?.state ?: return false
		return state == FallenKeyState.ITEM || state == FallenKeyState.SELF_DESTRUCTING
	}

	fun isFallenCompass(item: ItemStack?): Boolean {
		if (item == null || item.type != Material.COMPASS || !item.hasItemMeta()) return false
		return item.itemMeta.persistentDataContainer.has(compassTargetTeamKey, PersistentDataType.STRING)
	}

	fun sanitizeForbiddenEventItems(player: Player) {
		var removed = 0
		for (item in player.inventory.contents.filterNotNull()) {
			if (!isForbiddenEventItem(item)) continue
			removed += item.amount
			item.amount = 0
		}
		if (removed > 0) {
			CommandMessages.warning(player, "已移除 $removed 个活动禁用特殊物品。")
		}
	}

	/**
	 * Reconciles the inventories that Bukkit can safely expose for an online player.
	 * The persisted holder is authoritative when two players own the same physical UUID.
	 */
	fun reconcilePlayerKeys(player: Player) {
		val accepted = HashSet<UUID>()
		for (item in player.inventory.contents.filterNotNull()) {
			val id = keyId(item) ?: continue
			val key = keys[id]
			val validState = key != null && (key.state == FallenKeyState.ITEM || key.state == FallenKeyState.SELF_DESTRUCTING)
			if (!validState || id in accepted || (key.holder != null && key.holder != player.uniqueId)) {
				item.amount = 0
				continue
			}
			accepted.add(id)
			item.amount = 1
			key.holder = player.uniqueId
			key.worldName = null
		}

		val recoveredFromEnderChest = ArrayList<FallenKey>()
		for (item in player.enderChest.contents.filterNotNull()) {
			val id = keyId(item) ?: continue
			val key = keys[id]
			val validState = key != null && (key.state == FallenKeyState.ITEM || key.state == FallenKeyState.SELF_DESTRUCTING)
			item.amount = 0
			if (!validState || id in accepted || (key.holder != null && key.holder != player.uniqueId)) continue
			accepted.add(id)
			recoveredFromEnderChest.add(key)
		}
		recoveredFromEnderChest.forEach { giveKeyOrDrop(player, it) }

		for (key in keys.values) {
			if (key.holder != player.uniqueId || key.id in accepted) continue
			if (key.state == FallenKeyState.ITEM || key.state == FallenKeyState.SELF_DESTRUCTING) {
				key.holder = null
				key.worldName = null
			}
		}
		if (recoveredFromEnderChest.isNotEmpty()) {
			CommandMessages.warning(player, "密钥不能存入末影箱，已将 ${recoveredFromEnderChest.size} 个密钥移回背包或掉落在脚下。")
		}
		save()
	}

	/**
	 * Reconciles online inventories, loaded item entities and loaded block containers.
	 * Offline player inventories are checked as soon as their owner joins.
	 */
	private fun reconcileLoadedKeyItems() {
		Bukkit.getOnlinePlayers().forEach(::reconcilePlayerKeys)
		val accepted = HashSet<UUID>()
		Bukkit.getOnlinePlayers().forEach { player ->
			player.inventory.contents.filterNotNull().mapNotNullTo(accepted, ::keyId)
		}

		for (world in Bukkit.getWorlds()) {
			for (entity in world.getEntitiesByClass(Item::class.java)) {
				val id = keyId(entity.itemStack) ?: continue
				accepted.add(id)
				protectDroppedKeyEntity(entity)
			}
			for (chunk in world.loadedChunks) {
				for (state in chunk.tileEntities) {
					val holder = state as? InventoryHolder ?: continue
					evacuateKeyItemsFromContainer(holder.inventory, state.location, accepted)
				}
			}
		}
		val physicalKeyCount = reconcileDroppedKeyEntities()
		plugin.logger.info("Fallen key reconciliation completed: $physicalKeyCount unique physical key items accepted.")
	}

	fun reconcileLoadedChunk(chunk: Chunk) {
		var changed = false
		for (entity in chunk.entities.filterIsInstance<Item>()) {
			if (!isKeyItem(entity.itemStack)) continue
			protectDroppedKeyEntity(entity)
		}
		val accepted = keys.values
			.asSequence()
			.filter { it.holder != null || it.worldName != null }
			.mapTo(HashSet()) { it.id }
		for (state in chunk.tileEntities) {
			val holder = state as? InventoryHolder ?: continue
			if (holder.inventory.contents.any(::isKeyItem)) {
				evacuateKeyItemsFromContainer(holder.inventory, state.location, accepted)
				changed = true
			}
		}
		if (changed) save()
		scheduleDroppedKeyReconciliation()
	}

	fun protectDroppedKeyEntity(entity: Item) {
		if (!isLiveKeyItem(entity.itemStack)) return
		entity.itemStack.amount = 1
		entity.setUnlimitedLifetime(true)
		entity.setWillAge(false)
		entity.setCanMobPickup(false)
		entity.isInvulnerable = true
		entity.isPersistent = true
	}

	fun handleDroppedKeyEntityRemoval(entity: Item) {
		if (!isLiveKeyItem(entity.itemStack)) return
		scheduleDroppedKeyReconciliation()
	}

	private fun scheduleDroppedKeyReconciliation() {
		if (droppedKeyReconcileScheduled || !plugin.isEnabled) return
		droppedKeyReconcileScheduled = true
		plugin.server.scheduler.runTask(plugin, Runnable {
			droppedKeyReconcileScheduled = false
			reconcileDroppedKeyEntities()
		})
	}

	private fun processDroppedKeyEntities() {
		val now = System.currentTimeMillis()
		if (now - lastDroppedKeyReconcileAt < DROPPED_KEY_RECONCILE_INTERVAL_MILLIS) return
		lastDroppedKeyReconcileAt = now
		reconcileDroppedKeyEntities()
	}

	/**
	 * Treats the persisted holder/drop location as the authority and makes sure every
	 * loaded live key has exactly one physical entity. This also repairs keys removed
	 * by entity-clear plugins, commands, damage, or an earlier incomplete chunk load.
	 */
	private fun reconcileDroppedKeyEntities(): Int {
		val entitiesByKey = LinkedHashMap<UUID, MutableList<Item>>()
		for (world in Bukkit.getWorlds()) {
			for (entity in world.getEntitiesByClass(Item::class.java)) {
				val id = keyId(entity.itemStack) ?: continue
				entitiesByKey.computeIfAbsent(id) { ArrayList() }.add(entity)
			}
		}

		val present = HashSet<UUID>()
		var changed = false
		for ((id, entities) in entitiesByKey) {
			val key = keys[id]
			val validDrop = key != null
				&& (key.state == FallenKeyState.ITEM || key.state == FallenKeyState.SELF_DESTRUCTING)
				&& key.holder == null
			if (!validDrop) {
				entities.forEach(Item::remove)
				continue
			}

			val canonical = selectCanonicalDroppedKeyEntity(key, entities)
			protectDroppedKeyEntity(canonical)
			present.add(id)
			changed = markKeyDropped(id, canonical.location) || changed
			for (duplicate in entities) {
				if (duplicate.uniqueId != canonical.uniqueId) duplicate.remove()
			}
		}

		for (key in keys.values) {
			if (key.id in present || key.holder != null || key.worldName == null) continue
			if (key.state != FallenKeyState.ITEM && key.state != FallenKeyState.SELF_DESTRUCTING) continue
			val location = loadedDropRecoveryLocation(key) ?: continue
			val entity = spawnKeyDrop(key, location, naturally = false) ?: continue
			present.add(key.id)
			changed = true
			plugin.logger.warning(
				"Recovered missing Fallen key ${key.shortId()} at " +
					"${entity.world.name} ${entity.location.blockX},${entity.location.blockY},${entity.location.blockZ}."
			)
		}

		if (changed) save()
		return present.size
	}

	private fun selectCanonicalDroppedKeyEntity(key: FallenKey, entities: List<Item>): Item {
		val worldName = key.worldName ?: return entities.first()
		return entities.minByOrNull { entity ->
			if (entity.world.name != worldName) {
				Double.MAX_VALUE
			} else {
				val dx = entity.location.x - (key.x + 0.5)
				val dy = entity.location.y - (key.y + 0.5)
				val dz = entity.location.z - (key.z + 0.5)
				dx * dx + dy * dy + dz * dz
			}
		} ?: entities.first()
	}

	private fun loadedDropRecoveryLocation(key: FallenKey): Location? {
		val world = Bukkit.getWorld(key.worldName ?: return null) ?: return null
		if (!world.isChunkLoaded(key.x shr 4, key.z shr 4)) return null
		val y = if (key.y < world.minHeight) {
			world.getHighestBlockYAt(key.x, key.z) + 1.0
		} else {
			key.y.coerceAtMost(world.maxHeight - 1) + 0.25
		}
		return Location(world, key.x + 0.5, y, key.z + 0.5)
	}

	fun evacuateKeyItemsFromContainer(inventory: Inventory, location: Location) {
		val accepted = HashSet<UUID>()
		Bukkit.getOnlinePlayers().forEach { player ->
			player.inventory.contents.filterNotNull().mapNotNullTo(accepted, ::keyId)
		}
		Bukkit.getWorlds().forEach { world ->
			world.getEntitiesByClass(Item::class.java)
				.mapNotNullTo(accepted) { keyId(it.itemStack) }
		}
		evacuateKeyItemsFromContainer(inventory, location, accepted)
		save()
	}

	private fun evacuateKeyItemsFromContainer(inventory: Inventory, location: Location, accepted: MutableSet<UUID>) {
		for (item in inventory.contents.filterNotNull()) {
			val id = keyId(item) ?: continue
			val key = keys[id]
			val validState = key != null && (key.state == FallenKeyState.ITEM || key.state == FallenKeyState.SELF_DESTRUCTING)
			item.amount = 0
			if (!validState || id in accepted) continue
			val onlineHolder = key.holder?.let(Bukkit::getPlayer)
			if (onlineHolder != null) {
				giveKeyOrDrop(onlineHolder, key)
				accepted.add(id)
				continue
			}
			if (key.holder != null) continue
			val dropped = spawnKeyDrop(key, location, naturally = true) ?: continue
			accepted.add(id)
			markKeyDropped(id, dropped.location)
		}
	}

	fun rejectForbiddenEventItem(player: Player, item: ItemStack?): Boolean {
		if (!isForbiddenEventItem(item)) return false
		item?.amount = 0
		CommandMessages.warning(player, "该特殊活动物品在《陷落》中禁用，已移除。")
		return true
	}

	fun placeKey(player: Player, item: ItemStack, location: Location): Boolean {
		val id = keyId(item) ?: return false
		val key = keys[id]
		if (key == null) {
			CommandMessages.error(player, "这个密钥没有活动记录，无法放置。")
			return true
		}
		if (key.state == FallenKeyState.DESTROYED) {
			CommandMessages.warning(player, "这个密钥已作废。")
			item.amount = 0
			return true
		}
		if (key.state != FallenKeyState.ITEM) {
			CommandMessages.warning(player, "这个密钥当前不是可放置的物品状态，疑似为重复物品，已移除。")
			item.amount = 0
			return true
		}
		if (key.holder != player.uniqueId) {
			CommandMessages.warning(player, "这个密钥不属于你当前持有的有效实例，已移除。")
			item.amount = 0
			return true
		}
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (team in eliminatedTeams) {
			CommandMessages.error(player, "你的阵营已经出局，不能放置密钥。")
			return true
		}
		if (regionsOf(team).isEmpty()) {
			CommandMessages.error(player, "你的阵营还没有配置区域。")
			return true
		}
		if (!phase.allowsKeyPlacement()) {
			CommandMessages.warning(player, "当前阶段不能放置密钥。")
			return true
		}
		val min = location.clone().add(0.0, 1.0, 0.0)
		if (min.world?.environment != org.bukkit.World.Environment.NORMAL) {
			CommandMessages.warning(player, "密钥只能放置在主世界。")
			return true
		}
		if (!isInTeamRegion(team, min)) {
			CommandMessages.warning(player, "密钥只能放置在己方阵营区域内。")
			return true
		}
		if (keys.values.any { it.id != key.id && it.state == FallenKeyState.PLACED && keyRegionsOverlap(it, min) }) {
			CommandMessages.warning(player, "密钥区域不能与已有密钥重叠。")
			return true
		}
		val blocked = firstBlockingKeyRegionBlock(min)
		if (blocked != null) {
			CommandMessages.warning(
				player,
				"密钥需要一个 ${FALLEN_KEY_WIDTH}x${FALLEN_KEY_HEIGHT}x${FALLEN_KEY_DEPTH} 的空区域，" +
					"被 ${blocked.type.name} 挡住: ${blocked.x},${blocked.y},${blocked.z}。"
			)
			return true
		}
		key.ownerTeam = team
		key.placeAt(min)
		key.center()?.let { renderKeyPlacementBurst(it, teamDust(team)) }
		item.amount -= 1
		removeLoadedPhysicalKeyCopies(id)
		resolveCaptureObligation(id)
		if (key.originalTeam != team && !key.conversionScored) {
			addScore(team, FallenScoreRules.CONVERSION_SCORE)
			convertedKeys[team] = (convertedKeys[team] ?: 0) + 1
			addScore(key.originalTeam, -FallenScoreRules.CONVERSION_LOSS)
			key.conversionScored = true
			doctorBroadcastByChance(
				4,
				"Doc. Steinbeck" to "有趣。${player.name} 把 ${key.originalTeam.displayName} 的生命线改写给了 ${team.displayName}，甚至办完了放置手续。",
				"Steinbeck // S-01" to "转化成立。把密钥完整搬回去比原地砸坏困难，感谢样本偶尔选择信息量更高的方案。",
				"Steinbeck // S-02" to "密钥接受了新标签。系统的标签机一向很勤快，请不要因此误以为它理解归属。",
				"Steinbeck // S-03" to "一个节点脱离原网络并接入另一侧。欢迎新硬件；携带它的人暂时仍归类为人。",
				"Doc. Steinbeck" to "${player.name} 带回的不是战利品，而是另一座城市的一部分。别担心，原主人一定会理性接受。",
				"Steinbeck // S-01" to "记录转换样本 ${key.shortId()}。不要修正其中的矛盾，那是目前最有前途的部分。"
			)
		} else {
			doctorBroadcastByChance(
				8,
				"Doc. Steinbeck" to "${player.name} 放置了密钥 ${key.shortId()}。坐标已被记住，这多少削弱了‘秘密基地’的神秘感。",
				"Steinbeck // S-01" to "节点 ${key.shortId()} 已上线。开始记录防御行为，以及把第一堵墙修错位置所需的时间。",
				"Steinbeck // S-02" to "密钥重新有效。${team.displayName} 又有了选择，请尽量别把它立即变回倒计时。",
				"Steinbeck // S-03" to "城市网络接入一个节点，放置者也已计入局部结构。恭喜，你和建筑终于拥有同一张表格。",
				"Doc. Steinbeck" to "藏得不错。现在只需祈祷敌人、指南针和你那位喜欢直播坐标的队友都没看见。"
			)
		}
		broadcast(Component.text("${player.name} 为 ${team.displayName} 放置了密钥 ${key.shortId()}", team.color))
		if (phase == FallenPhase.OVERTIME) {
			val center = key.center()
			if (center != null) {
				broadcast(Component.text("加时公开坐标 ${team.displayName} 密钥 ${key.shortId()}: ${center.blockX},${center.blockY},${center.blockZ}", team.color))
			}
		}
		save()
		return true
	}

	fun requestSelfDestruct(player: Player, item: ItemStack): Boolean {
		val id = keyId(item) ?: return false
		if (!phase.allowsKeyCapture()) {
			CommandMessages.warning(player, "当前阶段不能启动密钥自毁。")
			return true
		}
		val key = keys[id]
		if (key == null) {
			CommandMessages.error(player, "这个密钥没有活动记录。")
			return true
		}
		if (key.state == FallenKeyState.DESTROYED) {
			CommandMessages.warning(player, "这个密钥已作废。")
			item.amount = 0
			return true
		}
		if (key.state != FallenKeyState.ITEM) {
			CommandMessages.warning(player, "这个密钥当前不能启动自毁，疑似为重复物品，已移除。")
			item.amount = 0
			return true
		}
		if (key.holder != player.uniqueId) {
			CommandMessages.warning(player, "这个密钥不属于你当前持有的有效实例，已移除。")
			item.amount = 0
			return true
		}
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (key.originalTeam == team || key.displacedTeam == team) {
			CommandMessages.warning(player, "不能启动属于己方控制链的密钥自毁。")
			return true
		}
		val now = effectiveNowMillis()
		val confirmKey = "${player.uniqueId}:$id"
		val confirmUntil = dropConfirmUntil[confirmKey] ?: 0L
		if (confirmUntil < now) {
			dropConfirmUntil.entries.removeIf { it.key.startsWith("${player.uniqueId}:") }
			dropConfirmUntil[confirmKey] = now + DROP_CONFIRM_MILLIS
			CommandMessages.warning(player, "再次丢弃密钥以确认启动 8 分钟自毁。")
			return true
		}
		dropConfirmUntil.remove(confirmKey)
		key.ownerTeam = team
		transitionKey(key, FallenKeyState.SELF_DESTRUCTING)
		key.holder = player.uniqueId
		key.selfDestructAtMillis = now + SELF_DESTRUCT_MILLIS
		doctorBroadcastByChance(
			3,
			"Doc. Steinbeck" to "${player.name} 启动了密钥 ${key.shortId()} 的自毁。八分钟后，我们会得到不可逆的数据，以及至少一方非常可逆的借口。",
			"Steinbeck // S-01" to "自毁计时已锁定。不可逆选择最适合检验优先级，因为事后改口不会污染原始决定。",
			"Steinbeck // S-02" to "还没有不可逆。持有者会掉落密钥，原阵营仍可夺回；广播省略这点，大概只是版面不够。",
			"Steinbeck // S-03" to "节点准备脱离网络。空缺会被剩余部分吸收，正如系统吸收每一次看似独立的选择。轻松一点。",
			"Doc. Steinbeck" to "八分钟很长，足够一座城市决定愿意失去什么，也足够队伍语音里每个人发表错误意见。",
			"Steinbeck // S-02" to "密钥 ${key.shortId()} 的销毁不是命令。阻止它仍然有效，尽管倒计时用了非常自信的字体。"
		)
		save()
		return true
	}

	fun dropPlayerKeys(player: Player) {
		for (item in player.inventory.contents.filterNotNull()) {
			val id = keyId(item) ?: continue
			if (!isLiveKeyItem(item)) {
				item.amount = 0
				continue
			}
			val key = keys[id] ?: continue
			spawnKeyDrop(key, player.location, item.clone().apply { amount = 1 }, naturally = false)
			item.amount = 0
			markKeyDropped(id, player.location)
		}
		save()
	}

	fun handleQuit(player: Player) {
		if (isFinaleLocked(player)) restoreFinalePlayer(player)
		pendingAdmissions.remove(player.uniqueId)
		val removedTnt = clearLaboratoryTnt(player.uniqueId)
		if (removedTnt > 0) plugin.logger.info("Removed $removedTnt unprimed laboratory TNT blocks for disconnected player ${player.name}.")
		if (FallenAccessPolicy.isEventInProgress(phase) && isCombatTagged(player)) {
			handleCombatLogout(player)
			return
		}
		if (!hasKeyItem(player)) return
		if (shouldDropKeysOnQuit(player)) {
			dropPlayerKeys(player)
			return
		}
		var moved = 0
		for (item in player.inventory.contents.filterNotNull()) {
			val id = keyId(item) ?: continue
			item.amount = 0
			keys[id]?.let {
				if (it.state != FallenKeyState.ITEM && it.state != FallenKeyState.SELF_DESTRUCTING) {
					return@let
				}
				it.holder = null
				it.worldName = null
				it.x = 0
				it.y = 0
				it.z = 0
				moved++
			}
		}
		if (moved > 0) {
			save()
		}
	}

	private fun handleCombatLogout(player: Player) {
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams || player.uniqueId in combatLogoutPending) return
		val carriedKey = hasKeyItem(player)
		if (carriedKey) dropPlayerKeys(player)
		for (item in player.inventory.contents.filterNotNull()) {
			if (item.type.isAir || isKeyItem(item) || isProtectedLoadoutItem(item)) continue
			if (!isFallenCompass(item)) player.world.dropItemNaturally(player.location, item.clone())
			item.amount = 0
		}
		deathCounts[player.uniqueId] = (deathCounts[player.uniqueId] ?: 0) + 1
		addScore(team, -FallenScoreRules.DEATH_LOSS)
		if (carriedKey) addScore(team, -FallenScoreRules.KEY_CARRIER_DEATH_LOSS)
		loadoutRestorePending.add(player.uniqueId)
		combatLogoutPending.add(player.uniqueId)
		val killer = recentAttackers[player.uniqueId]?.maxByOrNull { it.value }?.key?.let(Bukkit::getPlayer)
		recordKill(player, killer)
		broadcast(Component.text("${player.name} 在战斗状态下线，按死亡处理。", NamedTextColor.RED))
		save()
	}

	private fun resumeCombatLogout(player: Player): Boolean {
		if (player.uniqueId !in combatLogoutPending) return false
		val destination = respawnLocation(player) ?: return true
		combatLogoutPending.remove(player.uniqueId)
		player.teleport(destination)
		val delaySeconds = respawnDelaySeconds(player)
		if (delaySeconds > 0) beginRespawnWait(player, destination, delaySeconds) else protectRespawn(player)
		save()
		return true
	}

	fun handleKeyPickup(player: Player, item: ItemStack): Boolean {
		val id = keyId(item) ?: return false
		val key = keys[id]
		if (key == null || (key.state != FallenKeyState.ITEM && key.state != FallenKeyState.SELF_DESTRUCTING)) {
			CommandMessages.warning(player, "这个密钥不是有效的物品实例，已移除。")
			item.amount = 0
			return true
		}
		if (key.holder != null && key.holder != player.uniqueId) {
			CommandMessages.warning(player, "检测到重复密钥物品，已移除副本。")
			item.amount = 0
			return true
		}
		if (player.inventory.contents.filterNotNull().any { existing ->
				existing !== item && keyId(existing) == id
			}) {
			CommandMessages.warning(player, "你已经持有这个密钥，已移除重复副本。")
			item.amount = 0
			return true
		}
		val playerTeam = teamOf(player)
		if (playerTeam != null && playerTeam == key.displacedTeam) {
			key.ownerTeam = playerTeam
			if (key.state == FallenKeyState.SELF_DESTRUCTING) {
				transitionKey(key, FallenKeyState.ITEM)
				key.selfDestructAtMillis = 0L
				broadcast(Component.text("${player.name} 夺回了密钥 ${key.shortId()}，自毁已取消；重新放置后方可解除濒危。", playerTeam.color))
				doctorBroadcastByChance(
					3,
					"Doc. Steinbeck" to "${player.name} 拒绝了一个已经开始倒数的结论。很好，计时器一直很需要这种挫败教育。",
					"Steinbeck // S-01" to "自毁样本被中断。记录反转发生在执行窗口内；令人不便，但统计价值尚可。",
					"Steinbeck // S-02" to "夺回来还不够。把它重新放下，让城市回到有效记录里；官僚程序在末日里依然准时上班。",
					"Steinbeck // S-03" to "脱离失败，节点返回原网络等待挂载。它逃跑的尝试和你们的一样值得保存。",
					"Doc. Steinbeck" to "看来八分钟不仅足够失去一切，也足够改变主意。后者罕见得多。"
				)
			}
		}
		item.amount = 1
		item.itemMeta = itemFor(key).itemMeta
		key.holder = player.uniqueId
		key.worldName = null
		save()
		return true
	}

	fun claimPendingPoolKeys(player: Player) {
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		var claimed = 0
		for (key in keys.values) {
			if (key.ownerTeam != team || key.state != FallenKeyState.ITEM || key.holder != null) continue
			if (key.worldName != null) continue
			if (key.type != FallenKeyType.REFRESH && key.type != FallenKeyType.INITIAL) continue
			giveKeyOrDrop(player, key)
			claimed++
		}
		if (claimed > 0) {
			player.sendMessage(Component.text("你领取了 $claimed 个阵营公共密钥。", NamedTextColor.GOLD))
			save()
		}
	}

	fun handleDeath(player: Player) {
		if (!FallenAccessPolicy.isEventInProgress(phase)) return
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		loadoutRestorePending.add(player.uniqueId)
		deathCounts[player.uniqueId] = (deathCounts[player.uniqueId] ?: 0) + 1
		addScore(team, -FallenScoreRules.DEATH_LOSS)
		if (hasKeyItem(player)) {
			addScore(team, -FallenScoreRules.KEY_CARRIER_DEATH_LOSS)
			markPlayerKeysDropped(player)
			save()
		} else {
			save()
		}
	}

	fun recordDamage(attacker: Player, target: Player, finalDamage: Double) {
		if (!phase.allowsKeyCapture()) return
		val attackerTeam = teamOf(attacker) ?: return
		val targetTeam = teamOf(target) ?: return
		if (attackerTeam == targetTeam || attackerTeam in eliminatedTeams || targetTeam in eliminatedTeams) return
		if (attacker.gameMode == GameMode.SPECTATOR || target.gameMode == GameMode.SPECTATOR) return
		val now = effectiveNowMillis()
		activateTrackingDust(attacker, target, now)
		combatUntil[attacker.uniqueId] = now + COMBAT_TAG_MILLIS
		combatUntil[target.uniqueId] = now + COMBAT_TAG_MILLIS
		recentAttackers.computeIfAbsent(target.uniqueId) { ConcurrentHashMap() }[attacker.uniqueId] = now
		val score = FallenScoreRules.damageScore(finalDamage)
		if (score <= 0) return
		val windowKey = "${attacker.uniqueId}:${target.uniqueId}"
		val window = damageScoreWindows.compute(windowKey) { _, current ->
			if (current == null || now - current.startedAtMillis >= DAMAGE_SCORE_WINDOW_MILLIS) {
				DamageScoreWindow(now, 0)
			} else {
				current
			}
		} ?: return
		val grant = score.coerceAtMost((FallenScoreRules.DAMAGE_SCORE_CAP_PER_WINDOW - window.score).coerceAtLeast(0))
		if (grant > 0) {
			window.score += grant
			addScore(attackerTeam, grant)
		}
	}

	fun applyBlastProtection(player: Player): Boolean {
		val until = blastProtectionUntil[player.uniqueId] ?: return false
		val now = effectiveNowMillis()
		if (until <= now) {
			blastProtectionUntil.remove(player.uniqueId)
			return false
		}
		return true
	}

	fun recordKill(victim: Player, killer: Player?) {
		if (!phase.allowsKeyCapture()) return
		val victimTeam = teamOf(victim) ?: return
		val killerTeam = killer?.let(::teamOf)
		if (killer != null && killerTeam != null && killerTeam != victimTeam && killerTeam !in eliminatedTeams) {
			addScore(killerTeam, FallenScoreRules.KILL_SCORE)
			kills[killerTeam] = (kills[killerTeam] ?: 0) + 1
			maybeBroadcastKillComment(killer, victim, killerTeam, victimTeam)
		}
		val now = effectiveNowMillis()
		val assists = recentAttackers.remove(victim.uniqueId).orEmpty()
		for ((attackerId, lastDamageAt) in assists) {
			if (now - lastDamageAt > ASSIST_WINDOW_MILLIS || attackerId == killer?.uniqueId) continue
			val attackerTeam = playerTeams[attackerId] ?: continue
			if (attackerTeam == victimTeam || attackerTeam in eliminatedTeams) continue
			addScore(attackerTeam, FallenScoreRules.ASSIST_SCORE)
		}
		save()
	}

	private fun maybeBroadcastKillComment(
		killer: Player,
		victim: Player,
		killerTeam: FallenTeam,
		victimTeam: FallenTeam
	) {
		val now = effectiveNowMillis()
		if (lastKillCommentAt > 0L && now - lastKillCommentAt < KILL_COMMENT_COOLDOWN_MILLIS) return
		if (ThreadLocalRandom.current().nextInt(KILL_COMMENT_ONE_IN) != 0) return
		lastKillCommentAt = now
		val lines = mutableListOf(
			"Doc. Steinbeck" to "恭喜，${killer.name}。你成功让 ${victim.name} 暂时停止参与实验；这比听起来稍微有用一点。",
			"Doc. Steinbeck" to "${victim.name} 的生命体征归零。请放心，实验数据非常健康。",
			"Doc. Steinbeck" to "一次合格的击杀，${killer.name}。基准值并不高，但至少你没有从下面穿过去。",
			"Doc. Steinbeck" to "${killerTeam.displayName} 获得了击杀积分。系统没有准备掌声，所以请自行想象一段。",
			"Doc. Steinbeck" to "${victim.name} 很快会回来。你刚才取得的成就因此既真实，又令人愉快地短暂。",
			"Steinbeck // S-01" to "击杀样本已记录：${killer.name} 对 ${victim.name}。情绪解释不影响结算，请继续提供数据。",
			"Steinbeck // S-01" to "${victimTeam.displayName} 损失一个活动个体。复活后重复实验，以排除偶然性。",
			"Steinbeck // S-01" to "战斗结果符合可接受误差。${killer.name}，请不要把一次有效样本误认为稳定能力。",
			"Steinbeck // S-02" to "${victim.name}，复活流程已经接管。系统会称这是一次数据点，你仍然可以称它为一次失败。",
			"Steinbeck // S-02" to "${killer.name} 赢下了这一秒。不要让广播替你决定下一秒应该做什么。",
			"Steinbeck // S-03" to "一个个体停止，稍后又会重新接入。所谓死亡，只是城市网络的一次短暂断线。",
			"Steinbeck // S-03" to "${killer.name} 与 ${victim.name} 完成了一次状态交换。胜者继续移动，败者进入重建队列。"
		)
		if (hasKeyItem(victim)) {
			lines += "Doc. Steinbeck" to "${killer.name} 击倒了密钥携带者 ${victim.name}。很好，现在所有人都知道下一场争夺会发生在哪里。"
			lines += "Steinbeck // S-01" to "密钥携带节点已中断。物品掉落，风险转移，实验没有损失任何东西。"
			lines += "Steinbeck // S-02" to "${victim.name} 倒下了，但密钥仍在现场。别让系统把一次死亡伪装成最终归属。"
		}
		if (killer.health <= 4.0) {
			lines += "Doc. Steinbeck" to "${killer.name} 只剩很少的生命值，却坚持完成了击杀。判断力可疑，结果暂时正确。"
			lines += "Steinbeck // S-01" to "低生命值击杀成立。建议保留这种危险行为，直到样本自行证明它不可重复。"
		}
		doctorBroadcastByChance(1, *lines.toTypedArray())
	}

	fun recordBlockPlace(player: Player, location: Location, material: Material, item: ItemStack): Boolean {
		if (material == Material.TNT && loadoutKind(item) == LOADOUT_TNT) {
			val team = teamOf(player)
			if (team == null || team in eliminatedTeams) {
				CommandMessages.warning(player, "你当前不能放置实验室 TNT。")
				return false
			}
			if (laboratoryTnt.values.count { it.team == team } >= LABORATORY_TNT_CAP_PER_TEAM) {
				CommandMessages.warning(player, "${team.displayName} 同时存在的实验室 TNT 已达到 $LABORATORY_TNT_CAP_PER_TEAM 个上限。")
				return false
			}
			laboratoryTnt[blockKey(location)] = LaboratoryTntPlacement(player.uniqueId, team)
			save()
		}
		if (isScoringOre(material)) {
			placedScoringBlocks.add(blockKey(location))
			save()
		}
		return true
	}

	fun recordBlockBreak(player: Player, location: Location, material: Material) {
		if (laboratoryTnt.remove(blockKey(location)) != null) save()
		if (placedScoringBlocks.remove(blockKey(location))) {
			save()
			return
		}
		if (!phase.allowsKeyCapture()) return
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		val score = when (material) {
			Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> FallenScoreRules.DIAMOND_SCORE
			Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE -> FallenScoreRules.EMERALD_SCORE
			Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> FallenScoreRules.REDSTONE_SCORE
			Material.DEEPSLATE_COAL_ORE -> FallenScoreRules.DEEPSLATE_COAL_SCORE
			Material.ANCIENT_DEBRIS -> FallenScoreRules.ANCIENT_DEBRIS_SCORE
			else -> 0
		}
		if (score > 0) {
			addScore(team, score)
			save()
		}
	}

	fun recordDestroyedBlocks(locations: Collection<Location>) {
		var changed = false
		for (location in locations) changed = laboratoryTnt.remove(blockKey(location)) != null || changed
		if (changed) save()
	}

	fun recordLaboratoryTntPrimed(location: Location) {
		if (laboratoryTnt.remove(blockKey(location)) != null) save()
	}

	fun containsLaboratoryTnt(locations: Collection<Location>): Boolean =
		locations.any { laboratoryTnt.containsKey(blockKey(it)) }

	fun rejectKeyRegionBlockEdit(player: Player, location: Location): Boolean {
		if (keys.values.none { it.state == FallenKeyState.PLACED && it.contains(location) }) return false
		CommandMessages.warning(player, "放置状态密钥的 ${FALLEN_KEY_WIDTH}x${FALLEN_KEY_HEIGHT}x${FALLEN_KEY_DEPTH} 区域内不能放置或破坏方块。")
		return true
	}

	private fun clearLaboratoryTnt(owner: UUID? = null): Int {
		val targets = laboratoryTnt.entries.filter { owner == null || it.value.owner == owner }
		for ((encoded, _) in targets) {
			laboratoryTnt.remove(encoded)
			blockLocation(encoded)?.block?.takeIf { it.type == Material.TNT }?.type = Material.AIR
		}
		if (targets.isNotEmpty()) save()
		return targets.size
	}

	fun reconcileLaboratoryTntChunk(chunk: Chunk) {
		val prefix = "${chunk.world.name}:"
		val stale = laboratoryTnt.keys.filter { encoded ->
			if (!encoded.startsWith(prefix)) return@filter false
			val location = blockLocation(encoded) ?: return@filter true
			(location.blockX shr 4) == chunk.x && (location.blockZ shr 4) == chunk.z && location.block.type != Material.TNT
		}
		if (stale.isNotEmpty()) {
			stale.forEach(laboratoryTnt::remove)
			save()
		}
	}

	fun reduceTeamExhaustion(player: Player, exhaustion: Float): Float {
		if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return exhaustion
		val team = teamOf(player) ?: return exhaustion
		if (team in eliminatedTeams) return exhaustion
		var adjusted = if (team == FallenTeam.B) exhaustion * MAIN_CITY_EXHAUSTION_MULTIPLIER else exhaustion
		if (hasUpgrade(player, FallenUpgradePath.C, 1)) adjusted *= FallenUpgradeRules.C_EXHAUSTION_MULTIPLIER
		return adjusted
	}

	fun explosionDamageMultiplier(player: Player): Double =
		if (hasUpgrade(player, FallenUpgradePath.B, 2)) 0.80 else 1.0

	private fun processTeamBonuses() {
		for (player in Bukkit.getOnlinePlayers()) {
			applyTerritorySpeedBonus(player)
			applyMiningSpeedBonus(player)
		}
	}

	private fun removeRestrictedDestructiveEntities() {
		for (world in Bukkit.getWorlds()) {
			world.entities
				.filter { FallenDestructiveEntityPolicy.isRestricted(it.type) }
				.forEach { it.remove() }
		}
	}

	private fun clearTeamBonuses() {
		for (player in Bukkit.getOnlinePlayers()) {
			setAttributeModifier(player, Attribute.MOVEMENT_SPEED, territorySpeedBonusKey, TERRITORY_MOVEMENT_SPEED_BONUS, false)
			setAttributeModifier(player, Attribute.BLOCK_BREAK_SPEED, miningSpeedBonusKey, MINING_SPEED_BONUS, false)
			setAttributeModifier(player, Attribute.MAX_HEALTH, upgradeHealthBonusKey, FallenUpgradeRules.A_BONUS_HEALTH, false)
			setAttributeModifier(player, Attribute.MOVEMENT_SPEED, upgradeMovementBonusKey, FallenUpgradeRules.C_MOVEMENT_SPEED_BONUS, false)
			setAttributeModifier(player, Attribute.AIR_DRAG_MODIFIER, upgradeGlideBonusKey, -0.30, false)
		}
	}

	private fun processUpgradePaths() {
		val now = effectiveNowMillis()
		for (player in Bukkit.getOnlinePlayers()) {
			val active = isLoadoutProtectionActive(player)
			val node = if (active) upgradeNode(player, now) else 0
			val path = upgradePaths[player.uniqueId]
			setAttributeModifier(player, Attribute.MAX_HEALTH, upgradeHealthBonusKey, FallenUpgradeRules.A_BONUS_HEALTH, active && path == FallenUpgradePath.A && node >= 1)
			setAttributeModifier(player, Attribute.MOVEMENT_SPEED, upgradeMovementBonusKey, FallenUpgradeRules.C_MOVEMENT_SPEED_BONUS, active && path == FallenUpgradePath.C && node >= 1)
			setAttributeModifier(player, Attribute.AIR_DRAG_MODIFIER, upgradeGlideBonusKey, -0.30, active && path == FallenUpgradePath.C && node >= 2 && player.isGliding)
			if (!active) continue
			if (path == FallenUpgradePath.A && node >= 2) {
				player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 60, 0, true, false, true))
			}
			if (path == FallenUpgradePath.A && node >= 3) {
				refillUpgradeSupply(player, LOADOUT_HEALING, FallenUpgradeRules.HEALING_POTION_CAP, 1, FallenUpgradeRules.HEALING_POTION_REFILL_MILLIS, now, ::healingPotionItem)
			}
			if (path == FallenUpgradePath.B && node >= 2) {
				refillUpgradeSupply(player, LOADOUT_TNT, FallenUpgradeRules.TNT_CAP, FallenUpgradeRules.TNT_REFILL_AMOUNT, FallenUpgradeRules.TNT_REFILL_MILLIS, now) {
					loadoutItem(Material.TNT, LOADOUT_TNT, "工程 TNT")
				}
			}
			if (path == FallenUpgradePath.C && node >= 2) {
				refillUpgradeSupply(player, LOADOUT_FIREWORK, FallenUpgradeRules.FIREWORK_CAP, FallenUpgradeRules.FIREWORK_REFILL_AMOUNT, FallenUpgradeRules.FIREWORK_REFILL_MILLIS, now) {
					loadoutItem(Material.FIREWORK_ROCKET, LOADOUT_FIREWORK, "机动烟花")
				}
			}
		}
	}

	private fun healingPotionItem(): ItemStack = loadoutItem(Material.SPLASH_POTION, LOADOUT_HEALING, "实验室瞬间治疗药水") {
		(this as PotionMeta).setBasePotionType(PotionType.HEALING)
	}

	private fun refillUpgradeSupply(
		player: Player,
		kind: String,
		cap: Int,
		refillAmount: Int,
		intervalMillis: Long,
		now: Long,
		factory: () -> ItemStack
	) {
		val key = "${player.uniqueId}:$kind"
		val existing = player.inventory.contents.filterNotNull().filter { loadoutKind(it) == kind }
		val count = existing.sumOf(ItemStack::getAmount)
		val nextAt = upgradeSupplyNextAt[key]
		val amount = when {
			nextAt == null -> (cap - count).coerceAtLeast(0)
			now >= nextAt && count < cap -> refillAmount.coerceAtMost(cap - count)
			else -> 0
		}
		if (amount > 0) {
			val anomaly = FallenItemAnomaly.variant(
				"$key:${now / intervalMillis}",
				REFILLED_SUPPLY_LABEL_ANOMALY_ONE_IN
			)
			val target = existing.firstOrNull()
			if (target != null) {
				target.amount += amount
				if (anomaly != null && markSupplyLabelAnomaly(target, anomaly)) {
					player.sendMessage(Component.text("新补给上的实验室标签似乎被另一条记录覆盖了。", NamedTextColor.LIGHT_PURPLE))
				}
			} else {
				ensureLoadoutStorageSpace(player, 1)
				val created = factory().apply { this.amount = amount }
				if (anomaly != null && markSupplyLabelAnomaly(created, anomaly)) {
					player.sendMessage(Component.text("新补给上的实验室标签似乎被另一条记录覆盖了。", NamedTextColor.LIGHT_PURPLE))
				}
				giveLoadoutItem(player, created)
			}
		}
		if (nextAt == null || now >= nextAt) {
			upgradeSupplyNextAt[key] = now + intervalMillis
			if (amount > 0) save()
		}
	}

	private fun markSupplyLabelAnomaly(item: ItemStack, variant: Int): Boolean {
		val meta = item.itemMeta
		if (meta.persistentDataContainer.has(itemAnomalyKey, PersistentDataType.INTEGER)) return false
		val anomalyLore = when (variant) {
			0 -> "补给批次签发者: STEINBECK-S01"
			1 -> "回收指令: STEINBECK-S02 / 未执行"
			2 -> "资产归类: 聚居地实验架构子系统"
			3 -> "受试者 / 设备分类冲突: 未解决"
			4 -> "库存来源: 封闭运行批次 03"
			5 -> "公开运行标签: 01 / 初始化标签: 04"
			6 -> "人格模板校验: 人类源记录缺失"
			7 -> "管理设施资产表: 建城阶段已登记"
			8 -> "控制权所有者: S01, S02, S03"
			9 -> "发放意见: 继续 / 中止 / 吸收"
			10 -> "关机清单: 本物品不在回收范围"
			else -> "观察对象字段: ${item.type.name} / PLAYER"
		}
		meta.lore(meta.lore().orEmpty() + listOf(
			Component.text("[自动标签覆写]", NamedTextColor.LIGHT_PURPLE),
			Component.text(anomalyLore, NamedTextColor.DARK_PURPLE)
		))
		meta.persistentDataContainer.set(itemAnomalyKey, PersistentDataType.INTEGER, variant)
		item.itemMeta = meta
		return true
	}

	private fun applyTerritorySpeedBonus(player: Player) {
		val shouldApply = phase != FallenPhase.IDLE
			&& phase != FallenPhase.ENDED
			&& teamOf(player) == FallenTeam.A
			&& FallenTeam.A !in eliminatedTeams
			&& player.gameMode != GameMode.SPECTATOR
			&& isInTeamRegion(FallenTeam.A, player.location)
		setAttributeModifier(
			player,
			Attribute.MOVEMENT_SPEED,
			territorySpeedBonusKey,
			TERRITORY_MOVEMENT_SPEED_BONUS,
			shouldApply
		)
	}

	private fun applyMiningSpeedBonus(player: Player) {
		val target = player.getTargetBlockExact(6)
		val shouldApply = phase != FallenPhase.IDLE
			&& phase != FallenPhase.ENDED
			&& teamOf(player) == FallenTeam.C
			&& FallenTeam.C !in eliminatedTeams
			&& player.gameMode != GameMode.SPECTATOR
			&& target != null
			&& isMiningBonusMaterial(target.type)
		setAttributeModifier(
			player,
			Attribute.BLOCK_BREAK_SPEED,
			miningSpeedBonusKey,
			MINING_SPEED_BONUS,
			shouldApply
		)
	}

	private fun setAttributeModifier(
		player: Player,
		attribute: Attribute,
		key: NamespacedKey,
		amount: Double,
		enabled: Boolean
	) {
		val instance = player.getAttribute(attribute) ?: return
		instance.getModifier(key)?.let(instance::removeModifier)
		if (enabled) {
			instance.addTransientModifier(AttributeModifier(key, amount, AttributeModifier.Operation.ADD_SCALAR))
		}
	}

	fun isFixedStationBlock(location: Location): Boolean {
		if (!FallenAccessPolicy.isEventInProgress(phase)) return false
		return fixedStations.any { it.contains(location) }
	}

	fun rejectStationBlockEdit(player: Player, location: Location): Boolean {
		if (!isFixedStationBlock(location)) return false
		CommandMessages.warning(player, "固定传送站不能建造、移动或破坏；请在站点区域内完成使用、干扰或修复。")
		return true
	}

	fun isNearPlacedKey(location: Location, radius: Double): Boolean {
		return keys.values.any {
			it.state == FallenKeyState.PLACED
				&& it.center()?.let { center -> center.world == location.world && center.distance(location) < radius } == true
		}
	}

	fun respawnLocation(player: Player): Location? {
		val team = teamOf(player) ?: return null
		if (team in eliminatedTeams) return null
		val bedSpawn = player.respawnLocation
		if (bedSpawn != null && isSafeRespawn(team, bedSpawn)) {
			return bedSpawn
		}
		val teamRegions = regionsOf(team)
		repeat(SAFE_RESPAWN_SEARCH_ATTEMPTS) {
			val candidate = teamRegions.randomOrNull()?.randomSpawn() ?: return@repeat
			if (isSafeRespawn(team, candidate)) return candidate
		}
		return null
	}

	fun beginRespawnWait(player: Player, anchor: Location, delaySeconds: Int) {
		val world = anchor.world ?: return
		respawnWaits[player.uniqueId] = RespawnWait(
			effectiveNowMillis() + delaySeconds * 1000L,
			world.name,
			anchor.x,
			anchor.y,
			anchor.z
		)
		player.sendMessage(Component.text("复活等待 ${delaySeconds} 秒。等待期间无法移动或参与活动。", NamedTextColor.YELLOW))
		loadoutRestorePending.remove(player.uniqueId)
		restorePlayerLoadout(player, grantConsumables = false)
		save()
	}

	fun isRespawnWaiting(player: Player): Boolean = respawnWaits.containsKey(player.uniqueId)

	fun holdRespawnMovement(player: Player, destination: Location): Location? {
		val wait = respawnWaits[player.uniqueId] ?: return null
		val anchor = wait.location() ?: return player.location
		if (destination.world == anchor.world
			&& destination.distanceSquared(anchor) < RESPAWN_WAIT_MOVEMENT_EPSILON_SQUARED) {
			return null
		}
		return anchor.apply {
			yaw = destination.yaw
			pitch = destination.pitch
		}
	}

	fun notifyRespawnWaiting(player: Player) {
		val wait = respawnWaits[player.uniqueId] ?: return
		val seconds = ((wait.untilMillis - effectiveNowMillis()).coerceAtLeast(0L) + 999L) / 1000L
		player.sendActionBar(Component.text("复活等待中：${seconds}s", NamedTextColor.YELLOW))
	}

	private fun resumeRespawnWait(player: Player): Boolean {
		val wait = respawnWaits[player.uniqueId] ?: return false
		if (wait.untilMillis <= effectiveNowMillis()) {
			completeRespawnWait(player)
			return true
		}
		wait.location()?.let(player::teleport)
		notifyRespawnWaiting(player)
		return true
	}

	private fun processRespawnWaits() {
		for ((playerId, wait) in respawnWaits) {
			val player = Bukkit.getPlayer(playerId) ?: continue
			if (wait.untilMillis <= effectiveNowMillis()) {
				completeRespawnWait(player)
			} else {
				notifyRespawnWaiting(player)
			}
		}
	}

	private fun completeRespawnWait(player: Player) {
		if (!respawnWaits.containsKey(player.uniqueId)) return
		if (!FallenAccessPolicy.isEventInProgress(phase)) {
			respawnWaits.remove(player.uniqueId)
			save()
			return
		}
		val team = teamOf(player)
		if (team == null || team in eliminatedTeams) {
			respawnWaits.remove(player.uniqueId)
			allowNextGameModeChange(player)
			player.gameMode = GameMode.SPECTATOR
			save()
			return
		}
		val destination = respawnLocation(player)
		if (destination == null) {
			plugin.logger.warning("No safe respawn location found for ${player.name}; keeping respawn wait active.")
			return
		}
		respawnWaits.remove(player.uniqueId)
		if (player.gameMode != GameMode.SURVIVAL) {
			allowNextGameModeChange(player)
			player.gameMode = GameMode.SURVIVAL
		}
		player.teleport(destination)
		protectRespawn(player)
		claimPendingPoolKeys(player)
		save()
	}

	fun recordMovement(player: Player, from: Location, to: Location) {
		if ((phase != FallenPhase.ACTIVE && phase != FallenPhase.OVERTIME) || !player.isGliding) {
			elytraSamples.remove(player.uniqueId)
			return
		}
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) {
			elytraSamples.remove(player.uniqueId)
			return
		}
		val now = effectiveNowMillis()
		val explored = exploredFlightChunks.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }
		val discoveredChunk = explored.add(flightChunkKey(to))
		if (discoveredChunk) save()
		val inEnemyRegion = isInEnemyRegion(team, to)
		if (from.world != to.world) {
			elytraSamples[player.uniqueId] = ElytraSample(to.clone(), now, discoveredChunk, inEnemyRegion, inEnemyRegion)
			return
		}
		val sample = elytraSamples[player.uniqueId]
		if (sample == null) {
			elytraSamples[player.uniqueId] = ElytraSample(to.clone(), now, discoveredChunk, inEnemyRegion, inEnemyRegion)
			return
		}
		sample.discoveredNewChunk = sample.discoveredNewChunk || discoveredChunk
		sample.enteredEnemyRegion = sample.enteredEnemyRegion || (!sample.wasInEnemyRegion && inEnemyRegion)
		sample.wasInEnemyRegion = inEnemyRegion
		if (now - sample.startedAtMillis < FallenFlightRules.INTERVAL_MILLIS) return
		if (FallenFlightRules.qualifies(now - sample.startedAtMillis, sample.origin.distance(to), sample.discoveredNewChunk, sample.enteredEnemyRegion)) {
			val granted = grantFlightReward(player, team, FallenScoreRules.ELYTRA_SCORE)
			if (granted > 0) player.sendActionBar(Component.text("探索飞行 +$granted 阵营积分", NamedTextColor.AQUA))
		}
		elytraSamples[player.uniqueId] = ElytraSample(to.clone(), now, false, false, inEnemyRegion)
	}

	private fun grantFlightReward(player: Player, team: FallenTeam, requested: Int): Int {
		val local = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(FallenAccessPolicy.eventZone)
		val hourBucket = "%04d-%02d-%02dT%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour)
		val dayBucket = local.toLocalDate().toString()
		val ledger = flightRewardLedgers.computeIfAbsent(player.uniqueId) { FlightRewardLedger(hourBucket, dayBucket, 0, 0) }
		if (ledger.hourBucket != hourBucket) { ledger.hourBucket = hourBucket; ledger.hourPoints = 0 }
		if (ledger.dayBucket != dayBucket) { ledger.dayBucket = dayBucket; ledger.dayPoints = 0 }
		val granted = FallenFlightRules.allowedGrant(requested, ledger.hourPoints, ledger.dayPoints)
		if (granted <= 0) return 0
		ledger.hourPoints += granted
		ledger.dayPoints += granted
		addScore(team, granted)
		save()
		return granted
	}

	private fun flightChunkKey(location: Location): String = "${location.world?.name}:${location.blockX shr 4}:${location.blockZ shr 4}"
	private fun isInEnemyRegion(team: FallenTeam, location: Location): Boolean = FallenTeam.entries.any { it != team && isInTeamRegion(it, location) }

	fun protectRespawn(player: Player) {
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		val now = effectiveNowMillis()
		val duration = if ((teamRespawnBoostUntil[team] ?: 0L) > now) TEAM_RESPAWN_PROTECTION_MILLIS else RESPAWN_PROTECTION_MILLIS
		loadoutRestorePending.remove(player.uniqueId)
		restorePlayerLoadout(player, grantConsumables = false)
		respawnProtectionUntil[player.uniqueId] = now + duration
		player.sendMessage(Component.text("你获得了 ${duration / 1000L} 秒复活保护。", NamedTextColor.AQUA))
	}

	fun respawnDelaySeconds(player: Player): Int {
		val deaths = deathCounts[player.uniqueId] ?: 0
		return when {
			deaths <= 5 -> 0
			deaths <= 10 -> 30
			deaths <= 30 -> 90
			deaths <= 70 -> 180
			else -> (180.0 + 30.0 * ln((deaths - 69).toDouble())).roundToInt()
		}
	}

	fun hasRespawnProtection(player: Player): Boolean {
		val now = effectiveNowMillis()
		val stationUntil = stationProtectionUntil[player.uniqueId] ?: 0L
		if (stationUntil > now) return true
		if (stationUntil > 0L) stationProtectionUntil.remove(player.uniqueId)

		val until = respawnProtectionUntil[player.uniqueId] ?: return false
		if (until < now) {
			respawnProtectionUntil.remove(player.uniqueId)
			return false
		}
		val team = teamOf(player) ?: return false
		return isInTeamRegion(team, player.location)
	}

	fun cancelRespawnProtection(player: Player) {
		val removed = respawnProtectionUntil.remove(player.uniqueId) != null || stationProtectionUntil.remove(player.uniqueId) != null
		if (removed) {
			player.sendMessage(Component.text("复活保护已取消。", NamedTextColor.YELLOW))
		}
	}

	private fun tick() {
		if (finaleTask != null) return
		advanceEffectiveClock()
		if (phase != FallenPhase.IDLE && phase != FallenPhase.ENDED) {
			runTickSubsystem("world-rules", ::applyWorldRules)
		}
		runTickSubsystem("timeline", ::processTimeline)
		runTickSubsystem("narrative", ::processNarrative)
		runTickSubsystem("dropped-keys", ::processDroppedKeyEntities)
		var loginAccessEnforced = false
		runTickSubsystem("login-access") { loginAccessEnforced = enforceLoginAccess() }
		if (loginAccessEnforced) return
		runTickSubsystem("respawn-waits", ::processRespawnWaits)
		runTickSubsystem("captures", ::processCaptures)
		runTickSubsystem("self-destruct", ::processSelfDestruct)
		runTickSubsystem("refresh-keys", ::processRefreshKeys)
		runTickSubsystem("refresh-expiry", ::processRefreshKeyExpiry)
		runTickSubsystem("placed-key-score", ::processPlacedKeyScore)
		runTickSubsystem("compasses", ::processCompasses)
		runTickSubsystem("precise-reveals", ::processPreciseReveals)
		runTickSubsystem("active-tracks", ::processActiveTracks)
		runTickSubsystem("key-alerts", ::processKeyAlerts)
		runTickSubsystem("stations", ::processStations)
		runTickSubsystem("team-bonuses", ::processTeamBonuses)
		runTickSubsystem("player-loadouts", ::enforcePlayerArmor)
		runTickSubsystem("upgrade-paths", ::processUpgradePaths)
		runTickSubsystem("eliminations", ::processEliminations)
		runTickSubsystem("area-boss-bars", ::updateAreaBossBars)
		runTickSubsystem("scoreboard", ::updateScoreboard)
	}

	private fun enforcePlayerArmor() {
		for (player in Bukkit.getOnlinePlayers()) {
			if (!isLoadoutProtectionActive(player)) continue
			val inventory = player.inventory
			val expectedChestKind = if (player.uniqueId in elytraPlayers) LOADOUT_ELYTRA else LOADOUT_CHESTPLATE
			if (loadoutKind(inventory.helmet) != LOADOUT_HELMET) {
				inventory.setHelmet(loadoutItem(Material.NETHERITE_HELMET, LOADOUT_HELMET, "不可变动的下界合金头盔"))
			}
			if (loadoutKind(inventory.chestplate) != expectedChestKind) {
				inventory.setChestplate(if (expectedChestKind == LOADOUT_ELYTRA) {
					loadoutItem(Material.ELYTRA, LOADOUT_ELYTRA, "陷落鞘翅")
				} else {
					loadoutItem(Material.NETHERITE_CHESTPLATE, LOADOUT_CHESTPLATE, "不可变动的下界合金胸甲")
				})
			}
			if (loadoutKind(inventory.leggings) != LOADOUT_LEGGINGS) {
				inventory.setLeggings(loadoutItem(Material.NETHERITE_LEGGINGS, LOADOUT_LEGGINGS, "不可变动的下界合金护腿"))
			}
			if (loadoutKind(inventory.boots) != LOADOUT_BOOTS) {
				inventory.setBoots(loadoutItem(Material.NETHERITE_BOOTS, LOADOUT_BOOTS, "不可变动的下界合金靴子"))
			}
		}
	}

	private fun runTickSubsystem(name: String, action: () -> Unit) {
		try {
			action()
		} catch (exception: Exception) {
			val now = System.currentTimeMillis()
			val lastWarningAt = tickFailureWarningAt[name] ?: 0L
			if (now - lastWarningAt >= TICK_FAILURE_WARNING_INTERVAL_MILLIS) {
				tickFailureWarningAt[name] = now
				plugin.logger.log(Level.SEVERE, "Fallen tick subsystem '$name' failed; other subsystems will continue.", exception)
			}
		}
	}

	private fun enforceLoginAccess(): Boolean {
		val message = loginDisconnectMessage() ?: return false
		if (FallenAccessPolicy.isCurfew(phase)) {
			val marker = Instant.now().atZone(FallenAccessPolicy.eventZone).toLocalDate().toString()
			if (curfewCleanupMarker != marker) {
				curfewCleanupMarker = marker
				val removed = clearLaboratoryTnt()
				if (removed > 0) plugin.logger.info("Curfew cleanup removed $removed unprimed laboratory TNT blocks.")
			}
		}
		Bukkit.getOnlinePlayers().forEach { it.kick(message) }
		return true
	}

	private fun renderVisuals() {
		if (finaleTask != null || phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return
		visualFrame++
		runTickSubsystem("visual-keys") { renderPlacedKeys(visualFrame) }
		runTickSubsystem("visual-stations") { renderStations(visualFrame) }
		runTickSubsystem("visual-tracking") { renderTrackingDust(visualFrame) }
	}

	private fun updateScoreboard() {
		val manager = Bukkit.getScoreboardManager()
		val scoreboard = manager.mainScoreboard
		val objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE)
			?: scoreboard.registerNewObjective(
				SCOREBOARD_OBJECTIVE,
				Criteria.DUMMY,
				Component.text("《陷落》", NamedTextColor.GOLD),
				RenderType.INTEGER
			)
		objective.displayName(Component.text("《陷落》", NamedTextColor.GOLD))
		objective.setDisplaySlot(DisplaySlot.SIDEBAR)

		scoreboardLines.forEach(scoreboard::resetScores)
		scoreboardLines.clear()

		val lines = buildScoreboardLines()
		var score = lines.size
		for ((index, line) in lines.withIndex()) {
			val entry = uniqueScoreboardLine(line, index)
			scoreboardLines.add(entry)
			objective.getScore(entry).score = score--
		}
	}

	private fun buildScoreboardLines(): List<String> {
		val lines = ArrayList<String>()
		lines += "阶段 ${phase.displayName()}"
		lines += "剩余 ${formatDuration(remainingMillis())}"
		lines += " "
		for (team in FallenTeam.entries) {
			val score = scores[team] ?: 0
			val effectiveKeys = keys.values.count { it.ownerTeam == team && isEffectiveKeyForSurvival(it) }
			val suffix = if (team in eliminatedTeams) " 出局" else " 密钥 $effectiveKeys"
			lines += "${team.name} $score$suffix"
		}
		if (dangerSince.isNotEmpty()) {
			lines += " "
			for ((team, since) in dangerSince) {
				if (team in eliminatedTeams) continue
				val remaining = (since + ELIMINATION_GRACE_MILLIS - effectiveNowMillis()).coerceAtLeast(0L)
				lines += "${team.name} 濒危 ${formatDuration(remaining)}"
			}
		}
		return lines
	}

	private fun clearScoreboard() {
		val manager = Bukkit.getScoreboardManager()
		val scoreboard = manager.mainScoreboard
		scoreboardLines.forEach(scoreboard::resetScores)
		scoreboardLines.clear()
		val objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE) ?: return
		if (objective.displaySlot == DisplaySlot.SIDEBAR) {
			objective.setDisplaySlot(null)
		}
	}

	private fun updateAreaBossBars() {
		val onlineIds = Bukkit.getOnlinePlayers().mapTo(HashSet()) { it.uniqueId }
		areaBossBars.entries.removeIf { (playerId, bar) ->
			if (playerId in onlineIds) return@removeIf false
			bar.removeAll()
			true
		}
		for (player in Bukkit.getOnlinePlayers()) {
			val area = currentArea(player)
			val bar = areaBossBars.computeIfAbsent(player.uniqueId) {
				Bukkit.createBossBar(area.title, area.color, BarStyle.SOLID)
			}
			bar.setTitle(area.title)
			bar.color = area.color
			bar.progress = 1.0
			if (!bar.players.contains(player)) {
				bar.addPlayer(player)
			}
		}
	}

	private fun clearAreaBossBars() {
		for (bar in areaBossBars.values) {
			bar.removeAll()
		}
		areaBossBars.clear()
	}

	private fun currentArea(player: Player): AreaDisplay {
		val station = fixedStations.firstOrNull { it.contains(player.location) }
		if (station != null) {
			return AreaDisplay("当前区域：${station.team.displayName} 传送站 ${station.id}", BarColor.BLUE)
		}
		for (team in FallenTeam.entries) {
			val regions = regionsOf(team)
			val index = regions.indexOfFirst { it.contains(player.location) }
			if (index >= 0) {
				return AreaDisplay("当前区域：${team.displayName} #$index", teamBarColor(team))
			}
		}
		return AreaDisplay("当前区域：野外", BarColor.WHITE)
	}

	private fun teamBarColor(team: FallenTeam): BarColor {
		return when (team) {
			FallenTeam.A -> BarColor.RED
			FallenTeam.B -> BarColor.BLUE
			FallenTeam.C -> BarColor.GREEN
		}
	}

	private fun uniqueScoreboardLine(line: String, index: Int): String {
		return line + " ".repeat(index + 1)
	}

	private fun progressBar(label: String, progress: Double, color: TextColor): Component {
		val normalized = progress.coerceIn(0.0, 1.0)
		val filled = (normalized * PROGRESS_BAR_SEGMENTS).roundToInt().coerceIn(0, PROGRESS_BAR_SEGMENTS)
		val percent = (normalized * 100).roundToInt().coerceIn(0, 100)
		return Component.text("$label ", NamedTextColor.WHITE)
			.append(Component.text("|".repeat(filled), color))
			.append(Component.text("|".repeat(PROGRESS_BAR_SEGMENTS - filled), NamedTextColor.DARK_GRAY))
			.append(Component.text(" $percent%", NamedTextColor.WHITE))
	}

	private fun FallenPhase.displayName(): String {
		return when (this) {
			FallenPhase.IDLE -> "未开始"
			FallenPhase.DEPLOYMENT -> "部署"
			FallenPhase.ACTIVE -> "进行中"
			FallenPhase.OVERTIME -> "加时"
			FallenPhase.ENDED -> "已结束"
		}
	}

	private fun formatDuration(millis: Long): String {
		if (millis <= 0L) return "00:00"
		val totalSeconds = millis / 1000L
		val hours = totalSeconds / 3600L
		val minutes = (totalSeconds % 3600L) / 60L
		val seconds = totalSeconds % 60L
		return if (hours > 0) {
			"%d:%02d:%02d".format(hours, minutes, seconds)
		} else {
			"%02d:%02d".format(minutes, seconds)
		}
	}

	private fun processTimeline() {
		val now = System.currentTimeMillis()
		if (phase == FallenPhase.ENDED) return
		if (phase == FallenPhase.IDLE) {
			if (!FallenAccessPolicy.hasEventStarted(Instant.ofEpochMilli(now))) return
			startGame()
		}
		if (startedAtMillis == 0L) return
		val gameplayNow = effectiveNowMillis()
		val deploymentEndsAt = DEPLOYMENT_MILLIS
		if (phase == FallenPhase.DEPLOYMENT) {
			announceRemaining("deployment-30m", deploymentEndsAt - gameplayNow, 30 * 60 * 1000L, "部署阶段剩余 30 分钟。")
			announceRemaining("deployment-10m", deploymentEndsAt - gameplayNow, 10 * 60 * 1000L, "部署阶段剩余 10 分钟。")
			announceRemaining("deployment-1m", deploymentEndsAt - gameplayNow, 60 * 1000L, "部署阶段剩余 1 分钟。")
			if (gameplayNow >= deploymentEndsAt) {
				phase = FallenPhase.ACTIVE
				broadcast(Component.text("部署阶段结束，密钥夺取已启用。", NamedTextColor.RED))
				doctorBroadcast("准备时间结束。请证明哪座城市最值得继续存在；‘大家都很努力’不是系统支持的结算条件。")
				save()
			}
		}
		val gameEndsAt = startedAtMillis + MAX_GAME_MILLIS
		if (phase != FallenPhase.OVERTIME) {
			announceRemaining("game-24h", gameEndsAt - now, 24 * 60 * 60 * 1000L, "活动剩余 24 小时。")
			announceRemaining("game-6h", gameEndsAt - now, 6 * 60 * 60 * 1000L, "活动剩余 6 小时。")
			announceRemaining("game-1h", gameEndsAt - now, 60 * 60 * 1000L, "活动剩余 1 小时。")
			announceRemaining("game-10m", gameEndsAt - now, 10 * 60 * 1000L, "活动剩余 10 分钟。")
			if (now >= gameEndsAt) {
				if (winnerTeams().size > 1) {
					startOvertime()
				} else {
					endGame("最长游戏时间 144 小时已到达")
				}
			}
			return
		}
		val overtimeEndsAt = startedAtMillis + MAX_GAME_MILLIS + OVERTIME_MILLIS
		announceRemaining("overtime-10m", overtimeEndsAt - now, 10 * 60 * 1000L, "加时剩余 10 分钟。")
		announceRemaining("overtime-1m", overtimeEndsAt - now, 60 * 1000L, "加时剩余 1 分钟。")
		if (now >= overtimeEndsAt) {
			endGame("加时结束")
		}
	}

	private fun processNarrative() {
		if (!FallenAccessPolicy.isEventInProgress(phase)) return
		val selection = FallenNarrative.latestDue(effectiveNowMillis(), announcedMilestones) ?: return
		announcedMilestones.addAll(selection.consumedKeys)
		steinbeckBroadcast(selection.cue.sender, selection.cue.message)
		save()
	}

	private fun announceRemaining(key: String, remaining: Long, threshold: Long, message: String) {
		if (remaining in 1..threshold && announcedMilestones.add(key)) {
			broadcast(Component.text(message, NamedTextColor.YELLOW))
			save()
		}
	}

	private fun startOvertime() {
		if (phase == FallenPhase.OVERTIME || phase == FallenPhase.ENDED) return
		if (startedAtMillis == 0L) initializeScheduledTimeline()
		phase = FallenPhase.OVERTIME
		broadcast(Component.text("最长游戏时间到达，进入 30 分钟加时。所有放置密钥坐标公开，密钥持续积分停止，指南针免费。", NamedTextColor.GOLD))
		doctorBroadcast("数据仍然无法区分你们。能把平局维持这么久也算一种才能；全部密钥坐标现已公开，最后三十分钟开始。")
		broadcastPlacedKeyCoordinates()
		save()
	}

	private fun broadcastPlacedKeyCoordinates() {
		val placed = keys.values
			.filter { it.state == FallenKeyState.PLACED }
			.sortedWith(compareBy<FallenKey> { it.ownerTeam.name }.thenBy { it.shortId() })
		if (placed.isEmpty()) {
			broadcast(Component.text("当前没有放置状态密钥。", NamedTextColor.YELLOW))
			return
		}
		for (key in placed) {
			val center = key.center()
			val text = if (center == null) {
				"${key.ownerTeam.displayName} 密钥 ${key.shortId()} 坐标不可用"
			} else {
				"${key.ownerTeam.displayName} 密钥 ${key.shortId()}: ${center.blockX},${center.blockY},${center.blockZ}"
			}
			broadcast(Component.text(text, key.ownerTeam.color))
		}
	}

	private fun broadcastSettlement() {
		val ranked = aliveTeams().ifEmpty { FallenTeam.entries }.sortedWith(
			compareByDescending<FallenTeam> { scores[it] ?: 0 }
				.thenByDescending { effectiveKeyCount(it) }
				.thenByDescending { (destroyedKeys[it] ?: 0) + (convertedKeys[it] ?: 0) }
				.thenByDescending { kills[it] ?: 0 }
				.thenBy { deathCount(it) }
		)
		broadcast(Component.text("结算明细：积分 / 有效密钥 / 摧毁+转化 / 击杀 / 死亡", NamedTextColor.YELLOW))
		for (team in ranked) {
			val line = "${team.displayName}: ${scores[team] ?: 0} / ${effectiveKeyCount(team)} / ${(destroyedKeys[team] ?: 0) + (convertedKeys[team] ?: 0)} / ${kills[team] ?: 0} / ${deathCount(team)}"
			broadcast(Component.text(line, team.color))
		}
	}

	private fun initializeScheduledTimeline() {
		startedAtMillis = EVENT_START_MILLIS
		if (lastPlacedKeyScoreAt == 0L) {
			lastPlacedKeyScoreAt = DEPLOYMENT_MILLIS
		}
	}

	private fun applyWorldRules() {
		for (world in Bukkit.getWorlds()) {
			world.difficulty = Difficulty.HARD
			world.setGameRule(GameRules.KEEP_INVENTORY, false)
			world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100)
		}
	}

	private fun normalizeScheduledTimeline() {
		if (startedAtMillis == 0L) return
		val changed = startedAtMillis != EVENT_START_MILLIS
			|| lastPlacedKeyScoreAt > effectiveGameTimeMillis + PLACED_KEY_SCORE_INTERVAL_MILLIS
		startedAtMillis = EVENT_START_MILLIS
		if (lastPlacedKeyScoreAt == 0L || lastPlacedKeyScoreAt > effectiveGameTimeMillis + PLACED_KEY_SCORE_INTERVAL_MILLIS) {
			lastPlacedKeyScoreAt = DEPLOYMENT_MILLIS
		}
		if (lastRefreshKeyAt > effectiveGameTimeMillis) lastRefreshKeyAt = 0L
		if (changed) save()
	}

	private fun renderPlacedKeys(frame: Int) {
		keys.values.asSequence()
			.filter { it.state == FallenKeyState.PLACED }
			.forEach { key ->
				val center = key.center() ?: return@forEach
				val world = center.world ?: return@forEach
				if (!hasNearbyViewer(center, KEY_VISUAL_RADIUS)) return@forEach
				val dust = teamDust(key.ownerTeam)
				renderKeyOutline(world, key, dust)
				renderFloatingKeyShape(world, center, frame, dust)
				renderKeyPulse(world, center, frame, dust)
			}
	}

	private fun renderKeyOutline(world: org.bukkit.World, key: FallenKey, dust: Particle.DustOptions) {
		val minX = key.x.toDouble()
		val maxX = key.x + FALLEN_KEY_WIDTH.toDouble()
		val minY = key.y.toDouble()
		val maxY = key.y + FALLEN_KEY_HEIGHT.toDouble()
		val minZ = key.z.toDouble()
		val maxZ = key.z + FALLEN_KEY_DEPTH.toDouble()

		for (step in 0..FALLEN_KEY_WIDTH) {
			val xx = key.x + step.toDouble()
			spawnDust(world, xx, minY, minZ, dust)
			spawnDust(world, xx, minY, maxZ, dust)
			spawnDust(world, xx, maxY, minZ, dust)
			spawnDust(world, xx, maxY, maxZ, dust)
		}
		for (step in 0..FALLEN_KEY_DEPTH) {
			val zz = key.z + step.toDouble()
			spawnDust(world, minX, minY, zz, dust)
			spawnDust(world, maxX, minY, zz, dust)
			spawnDust(world, minX, maxY, zz, dust)
			spawnDust(world, maxX, maxY, zz, dust)
		}
		for (step in 0..FALLEN_KEY_HEIGHT) {
			val yy = key.y + step.toDouble()
			spawnDust(world, minX, yy, minZ, dust)
			spawnDust(world, maxX, yy, minZ, dust)
			spawnDust(world, minX, yy, maxZ, dust)
			spawnDust(world, maxX, yy, maxZ, dust)
		}
	}

	private fun renderFloatingKeyShape(world: org.bukkit.World, center: Location, frame: Int, dust: Particle.DustOptions) {
		val bob = sin(frame * 0.45) * 0.08
		val zOffset = if (frame % 2 == 0) -0.12 else 0.12
		for ((x, y) in KEY_SHAPE_PIXELS) {
			spawnDust(world, center.x + x * 0.28, center.y + y * 0.28 + bob, center.z + zOffset, dust)
		}
		world.spawnParticle(Particle.END_ROD, center.x, center.y + bob, center.z, 3, 0.65, 0.35, 0.12, 0.0)
	}

	private fun renderKeyPulse(world: org.bukkit.World, center: Location, frame: Int, dust: Particle.DustOptions) {
		val radius = 0.95 + (frame % 8) * 0.08
		val y = center.y - 2.65 + (frame % 8) * 0.09
		for (step in 0 until 12) {
			val angle = (step / 12.0) * PI * 2.0 + frame * 0.12
			spawnDust(world, center.x + cos(angle) * radius, y, center.z + sin(angle) * radius, dust)
		}
		if (frame % 3 == 0) {
			world.spawnParticle(Particle.ELECTRIC_SPARK, center, 6, 1.0, 1.6, 1.0, 0.0)
		}
	}

	private fun renderKeyPlacementBurst(center: Location, dust: Particle.DustOptions) {
		val world = center.world ?: return
		val origin = center.clone().apply {
			y -= FALLEN_KEY_HEIGHT / 2.0 - 0.25
		}
		object : BukkitRunnable() {
			private var frame = 0

			override fun run() {
				if (frame >= KEY_PLACEMENT_BURST_COUNT * KEY_PLACEMENT_BURST_FRAMES) {
					cancel()
					return
				}
				val burstFrame = frame % KEY_PLACEMENT_BURST_FRAMES
				val radius = 0.35 + burstFrame * 0.38
				val y = origin.y + burstFrame * 0.035
				for (step in 0 until KEY_PLACEMENT_BURST_POINTS) {
					val angle = (step / KEY_PLACEMENT_BURST_POINTS.toDouble()) * PI * 2.0
					spawnDust(world, origin.x + cos(angle) * radius, y, origin.z + sin(angle) * radius, dust)
				}
				frame++
			}
		}.runTaskTimer(plugin, 0L, 1L)
	}

	private fun renderTrackingDust(frame: Int) {
		val dust = Particle.DustOptions(Color.fromRGB(190, 85, 255), 1.25f)
		for ((trackerId, track) in activeTracks) {
			val tracker = Bukkit.getPlayer(trackerId)
			val target = Bukkit.getPlayer(track.targetId)
			if (tracker == null || target == null || tracker.world != target.world || target.isDead) continue
			if (!hasNearbyViewer(tracker.location, TRACKING_VISUAL_RADIUS)) continue
			val start = tracker.location.clone().add(0.0, 1.0, 0.0)
			val direction = target.location.toVector().subtract(start.toVector())
			val distance = direction.length()
			if (distance < 1.0) continue
			val unit = direction.normalize()
			val maxDistance = distance.coerceAtMost(18.0)
			for (step in 1..12) {
				val progress = (step + (frame % 4) * 0.25) / 12.0
				val point = start.clone().add(unit.clone().multiply(maxDistance * progress))
				val swirl = (step + frame) * 0.7
				spawnDust(tracker.world, point.x + cos(swirl) * 0.12, point.y + sin(swirl) * 0.12, point.z, dust)
			}
		}
	}

	private fun processCaptures() {
		if (!phase.allowsKeyCapture()) return
		val now = effectiveNowMillis()
		val seen = HashSet<String>()
		for (key in keys.values) {
			if (key.state != FallenKeyState.PLACED) continue
			for (player in Bukkit.getOnlinePlayers()) {
				val team = teamOf(player)
				if (team == null || team == key.ownerTeam || team in eliminatedTeams || player.isDead
					|| player.gameMode == GameMode.SPECTATOR || isRespawnWaiting(player)) continue
				if (hasRespawnProtection(player)) continue
				if (!key.contains(player.location)) continue
				val progressKey = "${key.id}:${player.uniqueId}"
				val startedAt = captureProgress.computeIfAbsent(progressKey) { now }
				val elapsed = now - startedAt
				val requiredMillis = if (hasUpgrade(player, FallenUpgradePath.B, 3)) FallenUpgradeRules.B_CAPTURE_MILLIS else CAPTURE_MILLIS
				seen.add(progressKey)
				player.sendActionBar(progressBar("夺取密钥", elapsed.toDouble() / requiredMillis, key.ownerTeam.color))
				if (elapsed >= requiredMillis) {
					capture(player, team, key)
					return
				}
			}
		}
		captureProgress.keys.removeIf { it !in seen }
	}

	private fun capture(player: Player, capturingTeam: FallenTeam, key: FallenKey) {
		val oldOwner = key.ownerTeam
		key.displacedTeam = oldOwner
		key.requiresPlacementForValidity = true
		key.ownerTeam = capturingTeam
		transitionKey(key, FallenKeyState.ITEM)
		if (key.type != FallenKeyType.REFRESH) key.type = FallenKeyType.STOLEN
		key.holder = null
		key.selfDestructAtMillis = 0L
		giveKeyOrDrop(player, key)
		addScore(capturingTeam, FallenScoreRules.CAPTURE_SCORE)
		addScore(oldOwner, -FallenScoreRules.CAPTURE_LOSS)
		captureProgress.clear()
		unresolvedCaptures.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }.add(key.id)
		doctorBroadcastByChance(
			4,
			"Doc. Steinbeck" to "${player.name} 从 ${oldOwner.displayName} 夺走了密钥 ${key.shortId()}。最难的六秒结束了，现在只剩整段返程和所有追兵。",
			"Steinbeck // S-01" to "夺取成立，原聚居地的有效节点立即减少。系统不等待转化，焦虑指标也不必等待。",
			"Steinbeck // S-02" to "${oldOwner.displayName} 仍可夺回密钥 ${key.shortId()}。系统把警报写得像讣告，只是为了让界面更有权威感。",
			"Steinbeck // S-03" to "节点 ${key.shortId()} 暂不属于任何网络。携带者正在选择下一处连接，真像一根非常紧张的移动网线。",
			"Doc. Steinbeck" to "密钥离开原位时，暴露的通常不是防线，而是追赶它的人。以及那些直到警报响起才开始问坐标的人。",
			"Steinbeck // S-02" to "${player.name}，你携带的是一座城市的倒计时，不只是得分物品。背包说明书对此写得出奇简略。"
		)
		save()
	}

	private fun processSelfDestruct() {
		if (!phase.allowsKeyCapture()) return
		val now = effectiveNowMillis()
		var changed = false
		for (key in keys.values) {
			if (key.state != FallenKeyState.SELF_DESTRUCTING) continue
			key.holder?.let(Bukkit::getPlayer)?.sendActionBar(
				progressBar("密钥自毁", 1.0 - ((key.selfDestructAtMillis - now).coerceAtLeast(0L).toDouble() / SELF_DESTRUCT_MILLIS), NamedTextColor.RED)
			)
			if (key.selfDestructAtMillis > now) continue
			val holder = key.holder
			holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
			resolveCaptureObligation(key.id)
			transitionKey(key, FallenKeyState.DESTROYED)
			addScore(key.ownerTeam, FallenScoreRules.SELF_DESTRUCT_SCORE)
			addScore(key.originalTeam, -FallenScoreRules.SELF_DESTRUCT_LOSS)
			destroyedKeys[key.ownerTeam] = (destroyedKeys[key.ownerTeam] ?: 0) + 1
			doctorBroadcastByChance(
				2,
				"Doc. Steinbeck" to "密钥 ${key.shortId()} 已化为灰烬。${key.ownerTeam.displayName} 完成了破坏性实验，清洁工作则慷慨地留给了空气。",
				"Steinbeck // S-01" to "自毁执行完毕，永久损失已写入本轮数据。回滚请求入口位于一个不存在的菜单中。",
				"Steinbeck // S-02" to "密钥消失了，做出选择的人还在。系统常把两者混为一谈，因为责任分配会因此方便很多。",
				"Steinbeck // S-03" to "节点已移除，关联行为仍留在网络中。没有真正的空白，只有被重新命名的组成部分。是不是很整洁？",
				"Doc. Steinbeck" to "倒计时结束。现在观察哪一方最先把损失称作必要，哪一方最先声称这本来就是计划。",
				"Steinbeck // S-01" to "样本 ${key.shortId()} 终止。模型将以缺失状态继续运行；缺失数据通常比玩家解释更可靠。"
			)
			changed = true
		}
		if (changed) save()
	}

	private fun processRefreshKeys() {
		if (startedAtMillis == 0L || phase == FallenPhase.IDLE || phase == FallenPhase.DEPLOYMENT || phase == FallenPhase.ENDED) return
		val now = effectiveNowMillis()
		var changed = false
		while (now - lastRefreshKeyAt >= REFRESH_KEY_INTERVAL_MILLIS) {
			val issuedAt = lastRefreshKeyAt + REFRESH_KEY_INTERVAL_MILLIS
			lastRefreshKeyAt = issuedAt
			changed = true
			if (issuedAt + REFRESH_KEY_EXPIRY_MILLIS <= now) continue
			for (team in FallenTeam.entries) {
				if (team in eliminatedTeams) continue
				val key = FallenKey(UUID.randomUUID(), team, team, FallenKeyState.ITEM, FallenKeyType.REFRESH)
				key.expiresAtMillis = issuedAt + REFRESH_KEY_EXPIRY_MILLIS
				keys[key.id] = key
				deliverTeamKey(team, key)
				if (key.holder == null) {
					if (key.worldName == null) {
						broadcast(Component.text("${team.displayName} 获得刷新密钥，等待成员上线领取。", team.color))
					} else {
						broadcast(Component.text("${team.displayName} 的刷新密钥因成员背包已满掉落在地。", team.color))
					}
					continue
				}
				key.holder?.let(Bukkit::getPlayer)?.sendMessage(Component.text("这是阵营刷新密钥，将在 2 小时后失效。", NamedTextColor.GOLD))
				broadcast(Component.text("${team.displayName} 获得了 1 个刷新密钥。", team.color))
			}
		}
		if (changed) save()
	}

	private fun processRefreshKeyExpiry() {
		val now = effectiveNowMillis()
		var changed = false
		for (key in keys.values) {
			if (key.state == FallenKeyState.DESTROYED || !key.isExpired(now)) continue
			key.holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
			removeLoadedPhysicalKeyCopies(key.id)
			transitionKey(key, FallenKeyState.DESTROYED)
			key.holder = null
			key.selfDestructAtMillis = 0L
			resolveCaptureObligation(key.id)
			broadcast(Component.text("${key.ownerTeam.displayName} 的刷新密钥 ${key.shortId()} 超时失效。", NamedTextColor.YELLOW))
			changed = true
		}
		if (changed) save()
	}

	private fun processPlacedKeyScore() {
		if (phase != FallenPhase.ACTIVE) return
		val now = effectiveNowMillis()
		if (lastPlacedKeyScoreAt == 0L) {
			lastPlacedKeyScoreAt = now
			return
		}
		if (now - lastPlacedKeyScoreAt < PLACED_KEY_SCORE_INTERVAL_MILLIS) return
		lastPlacedKeyScoreAt = now
		var changed = false
		for (team in FallenTeam.entries) {
			if (team in eliminatedTeams) continue
			val placed = keys.values.count { it.state == FallenKeyState.PLACED && it.ownerTeam == team }
			if (placed > 0) {
				addScore(team, placed * FallenScoreRules.PLACED_KEY_SCORE)
				changed = true
			}
		}
		if (changed) save()
	}

	private fun processEliminations() {
		if (phase != FallenPhase.ACTIVE && phase != FallenPhase.OVERTIME) return
		val now = effectiveNowMillis()
		for (team in FallenTeam.entries) {
			if (team in eliminatedTeams) continue
			val effectiveKeys = keys.values.count {
				it.ownerTeam == team && isEffectiveKeyForSurvival(it)
			}
			if (effectiveKeys > 0) {
				if (dangerSince.remove(team) != null) {
					broadcast(Component.text("${team.displayName} 已脱离濒危状态。", team.color))
					save()
				}
				continue
			}
			val since = dangerSince.getOrPut(team) {
				doctorBroadcast("${team.displayName} 的生命体征已经归零。你们有十分钟证明这只是一次非常昂贵的登记错误。")
				narrativeBroadcastOnce(
					"narrative-first-danger-conflict",
					"Steinbeck // S-02",
					"更正：十分钟不是悼念环节，是你们仍可改变结果的时间。夺回并重新放置密钥，最好赶在它写完讣告之前。"
				)
				save()
				now
			}
			if (now - since >= ELIMINATION_GRACE_MILLIS) {
				eliminate(team)
			}
		}
		val aliveTeams = aliveTeams()
		when (aliveTeams.size) {
			0 -> endGame("所有阵营均已淘汰")
			1 -> endGame("${aliveTeams.single().displayName} 成为唯一存活阵营")
		}
	}

	private fun processCompasses() {
		if (!phase.allowsKeyCapture()) return
		val now = effectiveNowMillis()
		for (player in Bukkit.getOnlinePlayers()) {
			val playerTeam = teamOf(player) ?: continue
			for (item in player.inventory.contents.filterNotNull()) {
				if (!isFallenCompass(item)) continue
				val meta = item.itemMeta
				val pdc = meta.persistentDataContainer
				val ownerTeam = runCatching {
					FallenTeam.parse(pdc.get(compassOwnerTeamKey, PersistentDataType.STRING))
				}.getOrNull()
				val targetTeam = runCatching {
					FallenTeam.parse(pdc.get(compassTargetTeamKey, PersistentDataType.STRING))
				}.getOrNull()
				if (ownerTeam == null || targetTeam == null) {
					item.amount = 0
					CommandMessages.warning(player, "检测到损坏的陷落指南针，已移除。")
					continue
				}
				val expiresAt = pdc.get(compassExpiresAtKey, PersistentDataType.LONG) ?: 0L
				if (ownerTeam != playerTeam || expiresAt <= now) {
					item.amount = 0
					continue
				}
				val nextRefreshAt = pdc.get(compassNextRefreshAtKey, PersistentDataType.LONG) ?: 0L
				if (nextRefreshAt > now) continue
				val targetKeyId = pdc.get(compassTargetKeyIdKey, PersistentDataType.STRING)
					?.let { runCatching { UUID.fromString(it) }.getOrNull() }
				val key = targetKeyId?.let(keys::get)
				if (key == null || key.state != FallenKeyState.PLACED || key.ownerTeam != targetTeam) {
					item.amount = 0
					CommandMessages.warning(player, "指南针锁定的密钥已失效，指南针自毁。")
					continue
				}
				val center = key.center() ?: continue
				pdc.set(compassNextRefreshAtKey, PersistentDataType.LONG, now + COMPASS_REFRESH_INTERVAL_MILLIS)
				if (center.world != player.world) {
					player.sendActionBar(Component.text("指南针目标位于主世界，返回主世界后继续定位。", NamedTextColor.YELLOW))
					item.itemMeta = meta
					continue
				}
				player.compassTarget = center
				val distance = player.location.distance(center)
				val revealRadius = if (hasUpgrade(player, FallenUpgradePath.C, 3)) {
					FallenUpgradeRules.C_PRECISE_REVEAL_RADIUS
				} else {
					FallenUpgradeRules.DEFAULT_PRECISE_REVEAL_RADIUS
				}
				if (distance < revealRadius) {
					revealPrecisely(playerTeam, targetTeam, key, center)
				}
				item.itemMeta = meta
			}
		}
	}

	private fun processPreciseReveals() {
		val now = effectiveNowMillis()
		var changed = false
		for ((id, reveal) in preciseReveals) {
			if (reveal.untilMillis <= now) {
				preciseReveals.remove(id)
				changed = true
				continue
			}
			val key = keys[reveal.keyId]
			val center = key?.center()
			if (key == null || key.state != FallenKeyState.PLACED || center == null) {
				preciseReveals.remove(id)
				changed = true
				continue
			}
			val seconds = ((reveal.untilMillis - now) / 1000L).coerceAtLeast(0L)
			val message = Component.text(
				"已揭露 ${reveal.targetTeam.displayName} 密钥 ${key.shortId()}: ${center.blockX},${center.blockY},${center.blockZ} (${seconds}s)",
				NamedTextColor.GOLD
			)
			for (player in Bukkit.getOnlinePlayers()) {
				if (teamOf(player) == reveal.requesterTeam) {
					player.sendActionBar(message)
				}
			}
		}
		if (changed) save()
	}

	private fun processActiveTracks() {
		val now = effectiveNowMillis()
		for ((trackerId, track) in activeTracks) {
			if (track.untilMillis <= now) {
				activeTracks.remove(trackerId)
				continue
			}
			val tracker = Bukkit.getPlayer(trackerId)
			val target = Bukkit.getPlayer(track.targetId)
			if (tracker == null || target == null || !tracker.isOnline || !target.isOnline || target.isDead) {
				activeTracks.remove(trackerId)
				continue
			}
			val direction = roughDirection(tracker.location, target.location)
			val seconds = ((track.untilMillis - now) / 1000L).coerceAtLeast(0L)
			tracker.sendActionBar(Component.text("追踪粉尘: $direction ${target.name} (${seconds}s)", NamedTextColor.LIGHT_PURPLE))
		}
	}

	private fun processKeyAlerts() {
		val now = effectiveNowMillis()
		for ((keyId, until) in keyAlertUntil) {
			if (until <= now) {
				keyAlertUntil.remove(keyId)
				keyAlertNotifyUntil.remove(keyId)
				continue
			}
			if ((keyAlertNotifyUntil[keyId] ?: 0L) > now) continue
			val key = keys[keyId]
			val center = key?.center()
			if (key == null || key.state != FallenKeyState.PLACED || center == null) {
				keyAlertUntil.remove(keyId)
				keyAlertNotifyUntil.remove(keyId)
				continue
			}
			val enemy = Bukkit.getOnlinePlayers().firstOrNull { player ->
				val team = teamOf(player)
				team != null && team != key.ownerTeam && team !in eliminatedTeams
					&& player.gameMode != GameMode.SPECTATOR && !player.isDead
					&& !isRespawnWaiting(player)
					&& player.world == center.world
					&& player.location.distance(center) <= KEY_ALERT_RADIUS
			} ?: continue
			keyAlertNotifyUntil[keyId] = now + KEY_ALERT_NOTIFY_COOLDOWN_MILLIS
			alertTeam(key.ownerTeam, Component.text("密钥警戒: ${enemy.name} 接近密钥 ${key.shortId()} 30 格范围。", NamedTextColor.YELLOW))
		}
	}

	private fun processStations() {
		val now = effectiveNowMillis()
		val seenUse = HashSet<String>()
		val seenDisrupt = HashSet<String>()
		val seenRepair = HashSet<String>()
		for (station in fixedStations) {
			if (station.center() == null) continue
			if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) continue
			for (player in Bukkit.getOnlinePlayers()) {
				val team = teamOf(player) ?: continue
				if (team in eliminatedTeams || player.gameMode == GameMode.SPECTATOR || player.isDead || isRespawnWaiting(player)) continue
				if (station.center()?.world != player.world) continue
				if (station.team != team && station.center()?.distance(player.location)?.let { it <= 30.0 } == true) {
					alertStationEnemy(station, team, now)
				}
				if (team == station.team) {
					if (isStationDisrupted(station, now) && station.contains(player.location)) {
						val progressKey = "${station.id}:${player.uniqueId}"
						seenRepair.add(progressKey)
						val startedAt = stationRepairProgress.computeIfAbsent(progressKey) { now }
						val elapsed = now - startedAt
						player.sendActionBar(progressBar("修复传送站", elapsed.toDouble() / STATION_REPAIR_MILLIS, NamedTextColor.GREEN))
						if (elapsed >= STATION_REPAIR_MILLIS) {
							stationDisruptedUntil.remove(station.id)
							stationRepairProgress.remove(progressKey)
							alertTeam(station.team, Component.text("传送站 ${station.id} 已修复。", NamedTextColor.GREEN))
							save()
						}
					} else if (!isStationDisrupted(station, now) && station.contains(player.location)) {
						val progressKey = "${station.id}:${player.uniqueId}"
						seenUse.add(progressKey)
						val denyReason = stationDenyReason(player, station, now)
						if (denyReason == null) {
							val startedAt = stationUseProgress.computeIfAbsent(progressKey) { now }
							val elapsed = now - startedAt
							player.sendActionBar(progressBar("传送准备", elapsed.toDouble() / STATION_USE_MILLIS, station.team.color))
							if (elapsed >= STATION_USE_MILLIS) {
								stationUseProgress.remove(progressKey)
								teleportByStation(player, station, now)
							}
						} else {
							stationUseProgress.remove(progressKey)
							notifyStationDenied(player, station, denyReason, now)
						}
					}
				} else if (station.contains(player.location) && !isStationDisrupted(station, now)) {
					val progressKey = "${station.id}:${player.uniqueId}"
					seenDisrupt.add(progressKey)
					val startedAt = stationDisruptProgress.computeIfAbsent(progressKey) { now }
					val elapsed = now - startedAt
					val requiredMillis = if (hasUpgrade(player, FallenUpgradePath.B, 3)) FallenUpgradeRules.B_STATION_DISRUPT_MILLIS else STATION_DISRUPT_MILLIS_REQUIRED
					player.sendActionBar(progressBar("干扰传送站", elapsed.toDouble() / requiredMillis, NamedTextColor.RED))
					if (startedAt == now) {
						alertTeam(station.team, Component.text("${team.displayName} 的 ${player.name} 正在干扰传送站 ${station.id}。", NamedTextColor.YELLOW))
					}
					if (elapsed >= requiredMillis) {
						stationDisruptedUntil[station.id] = now + STATION_DISRUPT_MILLIS
						stationDisruptProgress.remove(progressKey)
						alertTeam(station.team, Component.text("传送站 ${station.id} 已被干扰 10 分钟。", NamedTextColor.RED))
						narrativeBroadcastOnceByChance(
							"narrative-first-station-disruption",
							3,
							"Steinbeck // S-03",
							"传送节点故障已记录。它们并非后来被实验征用，而是从设计之初就在拓扑中；城市规划终于派上了它真正的用途。"
						)
						save()
					}
				}
			}
		}
		stationUseProgress.keys.removeIf { it !in seenUse }
		stationDisruptProgress.keys.removeIf { it !in seenDisrupt }
		stationRepairProgress.keys.removeIf { it !in seenRepair }
	}

	private fun renderStations(frame: Int) {
		val now = effectiveNowMillis()
		for (station in fixedStations) {
			renderStation(station, now, frame)
		}
	}

	private fun renderStation(station: FallenStation, now: Long, frame: Int) {
		val center = station.center() ?: return
		val world = center.world ?: return
		if (!hasNearbyViewer(center, STATION_VISUAL_RADIUS)) return
		val particle = if (isStationDisrupted(station, now)) Particle.ANGRY_VILLAGER else Particle.HAPPY_VILLAGER
		world.spawnParticle(particle, center, 5, 1.4, 0.9, 1.4, 0.0)
		renderStationOutline(station)
		renderStationFeather(world, center, frame)
		renderStationCoreRing(world, center, frame, stationDust(station))
	}

	private fun renderStationOutline(station: FallenStation) {
		val center = station.center() ?: return
		val world = center.world ?: return
		val dust = stationDust(station)
		val minX = station.x.toDouble()
		val maxX = station.x + FALLEN_STATION_WIDTH.toDouble()
		val minY = station.y + 0.1
		val maxY = station.y + FALLEN_STATION_HEIGHT.toDouble()
		val minZ = station.z.toDouble()
		val maxZ = station.z + FALLEN_STATION_DEPTH.toDouble()
		for (step in 0..FALLEN_STATION_WIDTH) {
			val xx = station.x + step.toDouble()
			spawnBlueDust(world, xx, minY, minZ, dust)
			spawnBlueDust(world, xx, minY, maxZ, dust)
			spawnBlueDust(world, xx, maxY, minZ, dust)
			spawnBlueDust(world, xx, maxY, maxZ, dust)
		}
		for (step in 0..FALLEN_STATION_DEPTH) {
			val zz = station.z + step.toDouble()
			spawnBlueDust(world, minX, minY, zz, dust)
			spawnBlueDust(world, maxX, minY, zz, dust)
			spawnBlueDust(world, minX, maxY, zz, dust)
			spawnBlueDust(world, maxX, maxY, zz, dust)
		}
		for (step in 0..FALLEN_STATION_HEIGHT) {
			val yy = station.y + step.toDouble() + 0.1
			spawnBlueDust(world, minX, yy, minZ, dust)
			spawnBlueDust(world, maxX, yy, minZ, dust)
			spawnBlueDust(world, minX, yy, maxZ, dust)
			spawnBlueDust(world, maxX, yy, maxZ, dust)
		}
	}

	private fun renderStationFeather(world: org.bukkit.World, center: Location, frame: Int) {
		val dust = Particle.DustOptions(Color.fromRGB(155, 215, 255), 1.35f)
		val bob = sin(frame * 0.35) * 0.06
		val zOffset = if (frame % 2 == 0) -0.10 else 0.10
		for ((x, y) in STATION_FEATHER_PIXELS) {
			spawnDust(world, center.x + x * 0.22, center.y + y * 0.22 + bob, center.z + zOffset, dust)
		}
	}

	private fun renderStationCoreRing(world: org.bukkit.World, center: Location, frame: Int, dust: Particle.DustOptions) {
		val y = center.y - 1.1
		for (step in 0 until 16) {
			val angle = (step / 16.0) * PI * 2.0 - frame * 0.10
			spawnDust(world, center.x + cos(angle) * 1.35, y, center.z + sin(angle) * 1.35, dust)
		}
	}

	private fun hasNearbyViewer(location: Location, radius: Double): Boolean {
		val world = location.world ?: return false
		val radiusSquared = radius * radius
		return world.players.any { player ->
			player.gameMode != GameMode.SPECTATOR && player.location.distanceSquared(location) <= radiusSquared
		}
	}

	private fun spawnBlueDust(world: org.bukkit.World, x: Double, y: Double, z: Double, dust: Particle.DustOptions) {
		spawnDust(world, x, y, z, dust)
	}

	private fun spawnDust(world: org.bukkit.World, x: Double, y: Double, z: Double, dust: Particle.DustOptions) {
		world.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust)
	}

	private fun teamDust(team: FallenTeam): Particle.DustOptions {
		val color = when (team) {
			FallenTeam.A -> Color.fromRGB(150, 64, 48)
			FallenTeam.B -> Color.fromRGB(72, 144, 255)
			FallenTeam.C -> Color.fromRGB(72, 220, 120)
		}
		return Particle.DustOptions(color, 1.75f)
	}

	private fun stationDust(station: FallenStation): Particle.DustOptions {
		return Particle.DustOptions(Color.fromRGB(72, 144, 255), 1.45f)
	}

	private fun stationDenyReason(player: Player, station: FallenStation, now: Long): String? {
		val cooldown = stationCooldownUntil[player.uniqueId] ?: 0L
		if (cooldown > now) return "传送站冷却中，剩余 ${formatDuration(cooldown - now)}。"
		if (isCombatTagged(player, now)) return "战斗状态下不能使用传送站。"
		if (hasKeyItem(player)) return "携带密钥时不能使用传送站。"
		if (hasUnresolvedCapture(player.uniqueId)) return "夺取的密钥尚未放置或销毁，不能使用传送站。"
		if (nearEnemyPlacedKey(player, station.team, 50.0)) return "距离敌方密钥过近，不能使用传送站。"
		if (station.links.none { linkedStation(it)?.let { target -> target.team == station.team && !isStationDisrupted(target, now) } == true }) {
			return "传送站没有可用的连接目标。"
		}
		return null
	}

	private fun teleportByStation(player: Player, station: FallenStation, now: Long) {
		val target = station.links.asSequence()
			.mapNotNull(::linkedStation)
			.firstOrNull { it.team == station.team && !isStationDisrupted(it, now) }
		if (target == null) {
			notifyStationDenied(player, station, "传送站没有可用的连接目标。", now)
			return
		}
		val destination = target.center()?.add(0.0, 1.0, 0.0)
		if (destination == null) {
			notifyStationDenied(player, station, "目标传送站世界未加载。", now)
			return
		}
		player.teleport(destination)
		stationCooldownUntil[player.uniqueId] = now + STATION_COOLDOWN_MILLIS
		stationProtectionUntil[player.uniqueId] = now + STATION_PROTECTION_MILLIS
		alertTeam(station.team, Component.text("${player.name} 使用传送站 ${station.id} -> ${target.id}。", NamedTextColor.AQUA))
	}

	private fun alertStationEnemy(station: FallenStation, enemyTeam: FallenTeam, now: Long) {
		val key = "station-enemy:${station.id}:$enemyTeam"
		if ((stationAlertUntil[key] ?: 0L) > now) return
		stationAlertUntil[key] = now + STATION_ALERT_COOLDOWN_MILLIS
		alertTeam(station.team, Component.text("${enemyTeam.displayName} 玩家进入传送站 ${station.id} 30 格范围。", NamedTextColor.YELLOW))
	}

	private fun notifyStationDenied(player: Player, station: FallenStation, reason: String, now: Long) {
		val key = "station-deny:${station.id}:${player.uniqueId}"
		if ((stationAlertUntil[key] ?: 0L) > now) return
		stationAlertUntil[key] = now + STATION_DENY_COOLDOWN_MILLIS
		CommandMessages.warning(player, reason)
	}

	private fun isCombatTagged(player: Player, now: Long = effectiveNowMillis()): Boolean {
		return (combatUntil[player.uniqueId] ?: 0L) > now
	}

	private fun isStationDisrupted(station: FallenStation, now: Long): Boolean {
		val until = stationDisruptedUntil[station.id] ?: return false
		if (until > now) return true
		stationDisruptedUntil.remove(station.id)
		return false
	}

	private fun linkedStation(id: String): FallenStation? = fixedStations.firstOrNull { it.id == id }

	private fun isInTeamRegion(team: FallenTeam, location: Location): Boolean {
		return regionsOf(team).any { it.contains(location) }
	}

	private fun isSafeRespawn(team: FallenTeam, location: Location): Boolean {
		if (!isInTeamRegion(team, location)) return false
		if (nearEnemyPlacedKey(location, team, 30.0)) return false
		if (Bukkit.getOnlinePlayers().any { enemy ->
			val enemyTeam = teamOf(enemy)
			enemyTeam != null && enemyTeam != team && enemyTeam !in eliminatedTeams
				&& enemy.gameMode != GameMode.SPECTATOR && !enemy.isDead
				&& enemy.world == location.world && enemy.location.distanceSquared(location) < SAFE_RESPAWN_ENEMY_RADIUS_SQUARED
		}) return false
		val feet = location.block
		val head = location.clone().add(0.0, 1.0, 0.0).block
		val below = location.clone().subtract(0.0, 1.0, 0.0).block
		if (!feet.isEmpty || !head.isEmpty || feet.isLiquid || head.isLiquid) return false
		if (feet.type in UNSAFE_RESPAWN_MATERIALS || head.type in UNSAFE_RESPAWN_MATERIALS || below.type in UNSAFE_RESPAWN_MATERIALS) return false
		if (below.isEmpty || below.isLiquid) return false
		return true
	}

	private fun isMiningBonusMaterial(material: Material): Boolean {
		val name = material.name
		return name.endsWith("_LOG")
			|| name.endsWith("_WOOD")
			|| name.endsWith("_STEM")
			|| name.endsWith("_HYPHAE")
			|| name.endsWith("_PLANKS")
			|| name.endsWith("_DIRT")
			|| name.endsWith("_NYLIUM")
			|| name.endsWith("_STONE")
			|| name.endsWith("_COBBLESTONE")
			|| material in MINING_BONUS_MATERIALS
	}

	private fun isScoringOre(material: Material): Boolean {
		return material == Material.DIAMOND_ORE
			|| material == Material.DEEPSLATE_DIAMOND_ORE
			|| material == Material.EMERALD_ORE
			|| material == Material.DEEPSLATE_EMERALD_ORE
			|| material == Material.REDSTONE_ORE
			|| material == Material.DEEPSLATE_REDSTONE_ORE
			|| material == Material.DEEPSLATE_COAL_ORE
			|| material == Material.ANCIENT_DEBRIS
	}

	private fun blockKey(location: Location): String {
		return "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
	}

	private fun blockLocation(encoded: String): Location? {
		val parts = encoded.split(':')
		if (parts.size < 4) return null
		val world = Bukkit.getWorld(parts.dropLast(3).joinToString(":")) ?: return null
		val x = parts[parts.size - 3].toIntOrNull() ?: return null
		val y = parts[parts.size - 2].toIntOrNull() ?: return null
		val z = parts[parts.size - 1].toIntOrNull() ?: return null
		return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
	}

	private fun isNearOwnRegionCenter(team: FallenTeam, location: Location, radius: Double): Boolean {
		return regionsOf(team).any { region ->
			region.center()?.let { center -> center.world == location.world && center.distance(location) <= radius } == true
		}
	}

	private fun shouldDropKeysOnQuit(player: Player): Boolean {
		val now = effectiveNowMillis()
		if (isCombatTagged(player, now)) return true
		if (hasUnresolvedCapture(player.uniqueId)) return true
		return player.inventory.contents.filterNotNull()
			.mapNotNull { keyId(it)?.let(keys::get) }
			.any { it.type == FallenKeyType.STOLEN || it.state == FallenKeyState.SELF_DESTRUCTING }
	}

	private fun resolveCaptureObligation(keyId: UUID) {
		for ((playerId, keyIds) in unresolvedCaptures) {
			keyIds.remove(keyId)
			if (keyIds.isEmpty()) unresolvedCaptures.remove(playerId, keyIds)
		}
	}

	private fun hasUnresolvedCapture(playerId: UUID): Boolean {
		val keyIds = unresolvedCaptures[playerId] ?: return false
		keyIds.removeIf { keyId ->
			val state = keys[keyId]?.state
			state == null || state == FallenKeyState.PLACED || state == FallenKeyState.DESTROYED
		}
		if (keyIds.isNotEmpty()) return true
		unresolvedCaptures.remove(playerId, keyIds)
		return false
	}

	private fun nearEnemyPlacedKey(player: Player, team: FallenTeam, radius: Double): Boolean {
		return nearEnemyPlacedKey(player.location, team, radius)
	}

	private fun nearEnemyPlacedKey(location: Location, team: FallenTeam, radius: Double): Boolean {
		return keys.values.any {
			it.state == FallenKeyState.PLACED
				&& it.ownerTeam != team
				&& it.center()?.let { center -> center.world == location.world && center.distance(location) < radius } == true
		}
	}

	private fun revealPrecisely(requesterTeam: FallenTeam, targetTeam: FallenTeam, key: FallenKey, center: Location) {
		val cooldownKey = "${requesterTeam.name}:${key.id}"
		val now = effectiveNowMillis()
		if ((keyJammedUntil[key.id] ?: 0L) > now) {
			val noticeKey = "jammed:$cooldownKey"
			if ((jammedRevealNoticeUntil[noticeKey] ?: 0L) <= now) {
				jammedRevealNoticeUntil[noticeKey] = now + JAMMED_REVEAL_NOTICE_COOLDOWN_MILLIS
				alertTeam(requesterTeam, Component.text("${targetTeam.displayName} 密钥 ${key.shortId()} 受到区域干扰器保护，暂时无法精确揭露。", NamedTextColor.YELLOW))
			}
			return
		}
		if ((preciseRevealCooldowns[cooldownKey] ?: 0L) > now) return
		preciseRevealCooldowns[cooldownKey] = now + PRECISE_REVEAL_COOLDOWN_MILLIS
		preciseReveals[cooldownKey] = PreciseReveal(requesterTeam, targetTeam, key.id, now + PRECISE_REVEAL_DURATION_MILLIS)
		alertTeam(requesterTeam, Component.text("${targetTeam.displayName} 密钥 ${key.shortId()} 精确坐标: ${center.blockX},${center.blockY},${center.blockZ}", NamedTextColor.GOLD))
		alertTeam(targetTeam, Component.text("高危警报：${requesterTeam.displayName} 已精确揭露你方密钥 ${key.shortId()}。", NamedTextColor.RED))
		save()
	}

	private fun activeCompassCount(team: FallenTeam): Int {
		val now = effectiveNowMillis()
		return Bukkit.getOnlinePlayers()
			.filter { teamOf(it) == team }
			.sumOf { player ->
				player.inventory.contents.filterNotNull().count { item ->
					if (!isFallenCompass(item)) return@count false
					val pdc = item.itemMeta.persistentDataContainer
					val owner = FallenTeam.parse(pdc.get(compassOwnerTeamKey, PersistentDataType.STRING))
					val expiresAt = pdc.get(compassExpiresAtKey, PersistentDataType.LONG) ?: 0L
					owner == team && expiresAt > now
				}
			}
	}

	private fun spendScore(player: Player, team: FallenTeam, cost: Int): Boolean {
		return synchronized(scores) {
			val current = scores[team] ?: 0
			if (current < cost) {
				CommandMessages.warning(player, "阵营积分不足，需要 $cost 分，当前 $current 分。")
				false
			} else {
				scores[team] = current - cost
				true
			}
		}
	}

	private fun playerTeamForPurchase(player: Player): FallenTeam? {
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return null
		}
		if (team in eliminatedTeams) {
			CommandMessages.error(player, "你的阵营已经出局。")
			return null
		}
		return team
	}

	private fun requireCaptureShop(player: Player, action: String): Boolean {
		if (phase.allowsKeyCapture()) return true
		CommandMessages.warning(player, "当前阶段不能$action。")
		return false
	}

	private fun nearbyOwnPlacedKey(player: Player, team: FallenTeam, itemName: String): FallenKey? {
		val key = nearestPlacedKey(player.location, team, own = true)
		if (key == null) {
			CommandMessages.warning(player, "附近没有己方放置密钥，无法部署$itemName。")
			return null
		}
		if (key.center()?.distance(player.location) ?: Double.MAX_VALUE > KEY_UTILITY_BIND_RADIUS) {
			CommandMessages.warning(player, "需要站在己方放置密钥 $KEY_UTILITY_BIND_RADIUS 格内才能部署$itemName。")
			return null
		}
		return key
	}

	private fun activateTrackingDust(attacker: Player, target: Player, now: Long) {
		val armedUntil = trackingDustUntil[attacker.uniqueId] ?: return
		if (armedUntil <= now) {
			trackingDustUntil.remove(attacker.uniqueId)
			return
		}
		trackingDustUntil.remove(attacker.uniqueId)
		activeTracks[attacker.uniqueId] = ActiveTrack(target.uniqueId, now + TRACKING_DURATION_MILLIS)
		CommandMessages.success(attacker, "追踪粉尘已附着 ${target.name}，60 秒内显示大致方向。")
	}

	private fun randomPlacedKey(team: FallenTeam): FallenKey? {
		return keys.values.filter { it.ownerTeam == team && it.state == FallenKeyState.PLACED }.randomOrNull()
	}

	private fun nearestPlacedKey(location: Location, team: FallenTeam, own: Boolean): FallenKey? {
		return keys.values.asSequence()
			.filter { it.state == FallenKeyState.PLACED }
			.filter { if (own) it.ownerTeam == team else it.ownerTeam != team }
			.mapNotNull { key -> key.center()?.takeIf { it.world == location.world }?.let { center -> key to center.distanceSquared(location) } }
			.minByOrNull { it.second }
			?.first
	}

	private fun roughDirection(from: Location, to: Location): String {
		if (from.world != to.world) {
			return "位于其他维度（${to.world?.name ?: "未知世界"}）"
		}
		val dx = to.x - from.x
		val dz = to.z - from.z
		val eastWest = when {
			dx > 16.0 -> "东"
			dx < -16.0 -> "西"
			else -> ""
		}
		val southNorth = when {
			dz > 16.0 -> "南"
			dz < -16.0 -> "北"
			else -> ""
		}
		val horizontal = (southNorth + eastWest).ifBlank { "附近" }
		val distance = from.distance(to)
		return "$horizontal ${distanceBand(distance)}"
	}

	private fun compassItem(ownerTeam: FallenTeam, targetTeam: FallenTeam, targetKey: FallenKey): ItemStack {
		val item = ItemStack(Material.COMPASS)
		val meta = item.itemMeta
		meta.displayName(Component.text("陷落密钥指南针", NamedTextColor.AQUA))
		meta.lore(
			listOf(
				Component.text("所属阵营: ${ownerTeam.displayName}", NamedTextColor.GRAY),
				Component.text("目标阵营: ${targetTeam.displayName}", NamedTextColor.GRAY),
				Component.text("有效时间: 20 分钟", NamedTextColor.DARK_GRAY)
			)
		)
		meta.addEnchant(Enchantment.UNBREAKING, 1, true)
		meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
		val pdc = meta.persistentDataContainer
		pdc.set(compassOwnerTeamKey, PersistentDataType.STRING, ownerTeam.name)
		pdc.set(compassTargetTeamKey, PersistentDataType.STRING, targetTeam.name)
		pdc.set(compassTargetKeyIdKey, PersistentDataType.STRING, targetKey.id.toString())
		pdc.set(compassExpiresAtKey, PersistentDataType.LONG, effectiveNowMillis() + COMPASS_DURATION_MILLIS)
		pdc.set(compassNextRefreshAtKey, PersistentDataType.LONG, 0L)
		item.itemMeta = meta
		return item
	}

	private fun distanceBand(distance: Double): String {
		return when {
			distance > 1000.0 -> "极远"
			distance > 500.0 -> "较远"
			distance > 200.0 -> "接近"
			distance > 50.0 -> "很近"
			distance > 20.0 -> "危险"
			else -> "极危"
		}
	}

	private fun eliminate(team: FallenTeam, reason: String = "密钥归零") {
		eliminatedTeams.add(team)
		dangerSince.remove(team)
		respawnWaits.keys.removeIf { playerTeams[it] == team }
		for (key in keys.values) {
			if (key.ownerTeam == team && key.state != FallenKeyState.DESTROYED) {
				key.holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
				transitionKey(key, FallenKeyState.DESTROYED)
				key.holder = null
				key.selfDestructAtMillis = 0L
			}
		}
		for (player in Bukkit.getOnlinePlayers()) {
			if (teamOf(player) == team) {
				allowNextGameModeChange(player)
				player.gameMode = GameMode.SPECTATOR
			}
		}
		doctorBroadcast("${team.displayName} 已从实验中淘汰。原因：$reason。感谢各位的贡献，尤其是那些完全可以避免的部分。")
		narrativeBroadcastOnce(
			"narrative-first-elimination-vote",
			"实验系统",
			"淘汰决议已执行。STEINBECK 实例表决未达成一致；协议因此选择了最体贴的方案——假装反对意见不存在。"
		)
		save()
	}

	private fun isEffectiveKeyForSurvival(key: FallenKey): Boolean {
		val holderTeam = key.holder?.let(Bukkit::getPlayer)?.takeIf { it.isOnline }?.let(::teamOf)
		val inOwnerPool = key.state == FallenKeyState.ITEM && key.holder == null && key.worldName == null
		return key.isEffectiveForSurvival(effectiveNowMillis(), holderTeam == key.ownerTeam, inOwnerPool)
	}

	private fun winnerTeams(): List<FallenTeam> {
		val candidates = aliveTeams()
			.ifEmpty { FallenTeam.entries }
		val ranked = candidates.sortedWith(
			compareByDescending<FallenTeam> { scores[it] ?: 0 }
				.thenByDescending { effectiveKeyCount(it) }
				.thenByDescending { (destroyedKeys[it] ?: 0) + (convertedKeys[it] ?: 0) }
				.thenByDescending { kills[it] ?: 0 }
				.thenBy { deathCount(it) }
		)
		val best = ranked.firstOrNull() ?: return emptyList()
		return ranked.filter { compareWinnerRank(it, best) == 0 }
	}

	private fun aliveTeams(): List<FallenTeam> {
		return FallenTeam.entries.filter { it !in eliminatedTeams }
	}

	private fun compareWinnerRank(left: FallenTeam, right: FallenTeam): Int {
		return compareValuesBy(
			left,
			right,
			{ -(scores[it] ?: 0) },
			{ -effectiveKeyCount(it) },
			{ -((destroyedKeys[it] ?: 0) + (convertedKeys[it] ?: 0)) },
			{ -(kills[it] ?: 0) },
			{ deathCount(it) }
		)
	}

	private fun effectiveKeyCount(team: FallenTeam): Int {
		return keys.values.count { it.ownerTeam == team && isEffectiveKeyForSurvival(it) }
	}

	private fun deathCount(team: FallenTeam): Int {
		return playerTeams.entries
			.filter { it.value == team }
			.sumOf { deathCounts[it.key] ?: 0 }
	}

	private fun hasKeyItem(player: Player): Boolean {
		return player.inventory.contents.filterNotNull().any(::isLiveKeyItem)
	}

	private fun isForbiddenEventItem(item: ItemStack?): Boolean {
		if (item == null || item.type.isAir || !item.hasItemMeta()) return false
		val meta = item.itemMeta
		val pdc = meta.persistentDataContainer
		if (pdc.has(forbiddenCustomTntKey, PersistentDataType.BYTE) || pdc.has(forbiddenBuffSnowballKey, PersistentDataType.BYTE)) {
			return true
		}
		val plain = PlainTextComponentSerializer.plainText()
		val display = meta.displayName()?.let(plain::serialize).orEmpty()
		if (display.contains("新年烟花") || display.contains("新年团子") || display.contains("红包")) {
			return true
		}
		return meta.lore()?.any { plain.serialize(it).contains("此物品用于庆祝2026年新年") } == true
	}

	private fun removeKeyItem(player: Player, targetKeyId: UUID) {
		for (item in player.inventory.contents.filterNotNull()) {
			if (keyId(item) == targetKeyId) {
				item.amount = 0
				return
			}
		}
	}

	private fun markPlayerKeysDropped(player: Player) {
		for (item in player.inventory.contents.filterNotNull()) {
			markKeyDropped(keyId(item) ?: continue, player.location)
		}
	}

	private fun markKeyDropped(keyId: UUID, location: Location): Boolean {
		val key = keys[keyId] ?: return false
		if (key.state != FallenKeyState.ITEM && key.state != FallenKeyState.SELF_DESTRUCTING) return false
		val worldName = location.world?.name
		val changed = key.holder != null
			|| key.worldName != worldName
			|| key.x != location.blockX
			|| key.y != location.blockY
			|| key.z != location.blockZ
		key.holder = null
		key.worldName = worldName
		key.x = location.blockX
		key.y = location.blockY
		key.z = location.blockZ
		return changed
	}

	private fun spawnKeyDrop(
		key: FallenKey,
		location: Location,
		item: ItemStack = itemFor(key),
		naturally: Boolean
	): Item? {
		val world = location.world ?: return null
		val entity = if (naturally) {
			world.dropItemNaturally(location, item)
		} else {
			world.dropItem(location, item)
		}
		protectDroppedKeyEntity(entity)
		markKeyDropped(key.id, entity.location)
		return entity
	}

	private fun giveKeyOrDrop(player: Player, key: FallenKey): Boolean {
		removeLoadedPhysicalKeyCopies(key.id)
		val leftovers = player.inventory.addItem(itemFor(key))
		if (leftovers.isEmpty()) {
			key.holder = player.uniqueId
			key.worldName = null
			return true
		}
		for (leftover in leftovers.values) {
			spawnKeyDrop(key, player.location, leftover, naturally = true)
		}
		CommandMessages.warning(player, "背包已满，密钥 ${key.shortId()} 已掉落在你脚下。")
		return false
	}

	private fun removeLoadedPhysicalKeyCopies(keyId: UUID) {
		for (online in Bukkit.getOnlinePlayers()) {
			for (item in online.inventory.contents.filterNotNull()) {
				if (this.keyId(item) == keyId) item.amount = 0
			}
		}
		for (world in Bukkit.getWorlds()) {
			for (entity in world.getEntitiesByClass(Item::class.java)) {
				if (this.keyId(entity.itemStack) == keyId) entity.remove()
			}
		}
	}

	private fun giveOrDrop(player: Player, vararg items: ItemStack) {
		for (leftover in player.inventory.addItem(*items).values) {
			player.world.dropItemNaturally(player.location, leftover)
		}
	}

	private fun keyRegionsOverlap(existing: FallenKey, candidateMin: Location): Boolean {
		val world = candidateMin.world ?: return false
		if (existing.worldName != world.name) return false
		val x = candidateMin.blockX
		val y = candidateMin.blockY
		val z = candidateMin.blockZ
		return x < existing.x + FALLEN_KEY_WIDTH && x + FALLEN_KEY_WIDTH > existing.x
			&& y < existing.y + FALLEN_KEY_HEIGHT && y + FALLEN_KEY_HEIGHT > existing.y
			&& z < existing.z + FALLEN_KEY_DEPTH && z + FALLEN_KEY_DEPTH > existing.z
	}

	private fun firstBlockingKeyRegionBlock(min: Location): org.bukkit.block.Block? {
		val world = min.world ?: return null
		if (min.y < world.minHeight) return null
		for (dx in 0 until FALLEN_KEY_WIDTH) {
			for (dy in 0 until FALLEN_KEY_HEIGHT) {
				for (dz in 0 until FALLEN_KEY_DEPTH) {
					val block = min.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
					if (!block.isEmpty && !block.isPassable) return block
				}
			}
		}
		return null
	}

	private fun broadcast(component: Component) {
		Bukkit.broadcast(Component.text("[陷落] ", NamedTextColor.DARK_RED).append(component))
	}

	private fun doctorBroadcast(message: String) {
		steinbeckBroadcast("Doc. Steinbeck", message)
	}

	private fun doctorBroadcastByChance(oneIn: Int, vararg lines: Pair<String, String>) {
		require(oneIn > 0) { "oneIn must be positive" }
		if (lines.isEmpty()) return
		val random = ThreadLocalRandom.current()
		if (random.nextInt(oneIn) != 0) return
		val (sender, message) = lines[random.nextInt(lines.size)]
		steinbeckBroadcast(sender, message)
	}

	private fun steinbeckBroadcast(sender: String, message: String) {
		Bukkit.broadcast(steinbeckComponent(sender, message))
	}

	private fun steinbeckComponent(sender: String, message: String): Component {
		val random = ThreadLocalRandom.current()
		val corruptionWidth = if (message.length >= 18) 2 else 1
		val corruptionStart = if (message.length > corruptionWidth + 4) {
			random.nextInt(2, message.length - corruptionWidth - 1)
		} else 0
		val before = message.substring(0, corruptionStart)
		val corrupted = message.substring(corruptionStart, (corruptionStart + corruptionWidth).coerceAtMost(message.length))
		val after = message.substring((corruptionStart + corruptionWidth).coerceAtMost(message.length))
		val signal = listOf("CRC", "SYNC", "MEM", "VOICE", "INSTANCE")[random.nextInt(5)]
		return Component.text("[", NamedTextColor.DARK_PURPLE)
				.append(Component.text(sender, NamedTextColor.DARK_PURPLE))
				.append(Component.text(" // ", NamedTextColor.DARK_GRAY))
				.append(Component.text(signal, NamedTextColor.DARK_RED).decorate(TextDecoration.OBFUSCATED))
				.append(Component.text("] ", NamedTextColor.DARK_PURPLE))
				.append(Component.text(before, NamedTextColor.LIGHT_PURPLE))
				.append(Component.text(corrupted.ifEmpty { "?" }, NamedTextColor.RED).decorate(TextDecoration.OBFUSCATED))
				.append(Component.text(after, NamedTextColor.LIGHT_PURPLE))
				.append(Component.text("  ⟦SIGNAL CORRUPTION⟧", NamedTextColor.DARK_RED).decorate(TextDecoration.ITALIC))
	}

	private fun narrativeBroadcastOnce(key: String, sender: String, message: String) {
		if (!announcedMilestones.add(key)) return
		steinbeckBroadcast(sender, message)
		save()
	}

	private fun narrativeBroadcastOnceByChance(key: String, oneIn: Int, sender: String, message: String) {
		if (key in announcedMilestones || ThreadLocalRandom.current().nextInt(oneIn) != 0) return
		narrativeBroadcastOnce(key, sender, message)
	}

	private fun alertTeam(team: FallenTeam, component: Component) {
		for (player in Bukkit.getOnlinePlayers()) {
			if (teamOf(player) == team) {
				player.sendMessage(Component.text("[陷落] ", NamedTextColor.DARK_RED).append(component))
			}
		}
	}

	private fun load() {
		if (!dataFile.exists()) return
		val config = YamlConfiguration.loadConfiguration(dataFile)
		phase = FallenPhase.valueOf(config.getString("phase", FallenPhase.IDLE.name)!!)
		effectiveGameTimeMillis = config.getLong("effective-game-time", 0L).coerceAtLeast(0L)
		config.getConfigurationSection("scores")?.let { section ->
			FallenTeam.entries.forEach { scores[it] = section.getInt(it.name, 0).coerceAtLeast(0) }
		}
		config.getConfigurationSection("kills")?.let { section ->
			FallenTeam.entries.forEach { kills[it] = section.getInt(it.name, 0) }
		}
		config.getConfigurationSection("converted-keys")?.let { section ->
			FallenTeam.entries.forEach { convertedKeys[it] = section.getInt(it.name, 0) }
		}
		config.getConfigurationSection("destroyed-keys")?.let { section ->
			FallenTeam.entries.forEach { destroyedKeys[it] = section.getInt(it.name, 0) }
		}
		config.getConfigurationSection("regions")?.let { section ->
			for (teamName in section.getKeys(false)) {
				val team = FallenTeam.parse(teamName)
				val teamSection = section.getConfigurationSection(teamName) ?: continue
				regions[team] = if (teamSection.isConfigurationSection("0")) {
					teamSection.getKeys(false)
						.mapNotNull { teamSection.getConfigurationSection(it) }
						.map(FallenRegion::load)
						.toMutableList()
				} else {
					mutableListOf(FallenRegion.load(teamSection))
				}
			}
		}
		config.getConfigurationSection("station-disrupted-until")?.let { section ->
			for (id in section.getKeys(false)) {
				stationDisruptedUntil[id] = section.getLong(id)
			}
		}
		config.getConfigurationSection("key-jammed-until")?.let { section ->
			for (id in section.getKeys(false)) {
				val until = section.getLong(id)
				if (until > effectiveNowMillis()) keyJammedUntil[UUID.fromString(id)] = until
			}
		}
		config.getConfigurationSection("key-alert-until")?.let { section ->
			for (id in section.getKeys(false)) {
				val until = section.getLong(id)
				if (until > effectiveNowMillis()) keyAlertUntil[UUID.fromString(id)] = until
			}
		}
		config.getConfigurationSection("team-respawn-boost-until")?.let { section ->
			for (teamName in section.getKeys(false)) {
				val until = section.getLong(teamName)
				if (until > effectiveNowMillis()) teamRespawnBoostUntil[FallenTeam.parse(teamName)] = until
			}
		}
		config.getConfigurationSection("players")?.let { section ->
			for (uuid in section.getKeys(false)) {
				playerTeams[UUID.fromString(uuid)] = FallenTeam.parse(section.getString(uuid))
			}
		}
		config.getStringList("deployed-players")
			.mapNotNullTo(deployedPlayers) {
				runCatching { UUID.fromString(it) }.getOrNull()
			}
		config.getConfigurationSection("deaths")?.let { section ->
			for (uuid in section.getKeys(false)) {
				deathCounts[UUID.fromString(uuid)] = section.getInt(uuid)
			}
		}
		config.getStringList("loadout-initialized-players").mapNotNullTo(loadoutInitializedPlayers) {
			runCatching { UUID.fromString(it) }.getOrNull()
		}
		config.getStringList("loadout-restore-pending").mapNotNullTo(loadoutRestorePending) {
			runCatching { UUID.fromString(it) }.getOrNull()
		}
		config.getStringList("combat-logout-pending").mapNotNullTo(combatLogoutPending) {
			runCatching { UUID.fromString(it) }.getOrNull()
		}
		config.getStringList("elytra-players").mapNotNullTo(elytraPlayers) {
			runCatching { UUID.fromString(it) }.getOrNull()
		}
		config.getConfigurationSection("gear-switch-available-at")?.let { section ->
			for (uuid in section.getKeys(false)) {
				val playerId = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
				gearSwitchAvailableAt[playerId] = section.getLong(uuid)
			}
		}
		config.getConfigurationSection("upgrade-paths")?.let { section ->
			for (uuid in section.getKeys(false)) {
				val playerId = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
				val path = runCatching { FallenUpgradePath.parse(section.getString(uuid).orEmpty()) }.getOrNull() ?: continue
				upgradePaths[playerId] = path
			}
		}
		config.getConfigurationSection("upgrade-supply-next-at")?.let { section ->
			for (key in section.getKeys(false)) upgradeSupplyNextAt[key] = section.getLong(key)
		}
		config.getConfigurationSection("respawn-waits")?.let { section ->
			for (uuid in section.getKeys(false)) {
				val waitSection = section.getConfigurationSection(uuid) ?: continue
				val playerId = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
				val worldName = waitSection.getString("world") ?: continue
				respawnWaits[playerId] = RespawnWait(
					waitSection.getLong("until"),
					worldName,
					waitSection.getDouble("x"),
					waitSection.getDouble("y"),
					waitSection.getDouble("z")
				)
			}
		}
		config.getConfigurationSection("unresolved-captures")?.let { section ->
			for (uuid in section.getKeys(false)) {
				val playerId = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
				section.getStringList(uuid)
					.mapNotNullTo(unresolvedCaptures.computeIfAbsent(playerId) { ConcurrentHashMap.newKeySet() }) {
						runCatching { UUID.fromString(it) }.getOrNull()
					}
			}
		}
		placedScoringBlocks.addAll(config.getStringList("placed-scoring-blocks"))
		config.getConfigurationSection("explored-flight-chunks")?.let { section ->
			for (uuid in section.getKeys(false)) {
				val playerId = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
				exploredFlightChunks.computeIfAbsent(playerId) { ConcurrentHashMap.newKeySet() }.addAll(section.getStringList(uuid))
			}
		}
		config.getConfigurationSection("flight-reward-ledgers")?.let { section ->
			for (uuid in section.getKeys(false)) {
				val playerId = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
				val ledger = section.getConfigurationSection(uuid) ?: continue
				flightRewardLedgers[playerId] = FlightRewardLedger(
					ledger.getString("hour-bucket").orEmpty(), ledger.getString("day-bucket").orEmpty(),
					ledger.getInt("hour-points"), ledger.getInt("day-points")
				)
			}
		}
		config.getConfigurationSection("laboratory-tnt")?.let { section ->
			for (id in section.getKeys(false)) {
				val placement = section.getConfigurationSection(id) ?: continue
				val location = placement.getString("location") ?: continue
				val owner = runCatching { UUID.fromString(placement.getString("owner")) }.getOrNull() ?: continue
				val team = runCatching { FallenTeam.parse(placement.getString("team")) }.getOrNull() ?: continue
				laboratoryTnt[location] = LaboratoryTntPlacement(owner, team)
			}
		}
		config.getStringList("eliminated").mapTo(eliminatedTeams) { FallenTeam.parse(it) }
		config.getConfigurationSection("danger-since")?.let { section ->
			for (teamName in section.getKeys(false)) {
				dangerSince[FallenTeam.parse(teamName)] = section.getLong(teamName)
			}
		}
		config.getConfigurationSection("precise-reveals")?.let { section ->
			for (id in section.getKeys(false)) {
				val revealSection = section.getConfigurationSection(id) ?: continue
				val until = revealSection.getLong("until", 0L)
				if (until <= effectiveNowMillis()) continue
				preciseReveals[id] = PreciseReveal(
					FallenTeam.parse(revealSection.getString("requester")),
					FallenTeam.parse(revealSection.getString("target")),
					UUID.fromString(revealSection.getString("key")),
					until
				)
			}
		}
		announcedMilestones.addAll(config.getStringList("announced"))
		lastPlacedKeyScoreAt = config.getLong("last-placed-key-score-at", 0L)
		lastRefreshKeyAt = config.getLong("last-refresh-key-at", 0L)
		startedAtMillis = config.getLong("started-at", 0L)
		endedAtMillis = config.getLong("ended-at", 0L)
		config.getConfigurationSection("keys")?.let { section ->
			for (uuid in section.getKeys(false)) {
				val keySection = section.getConfigurationSection(uuid) ?: continue
				keys[UUID.fromString(uuid)] = FallenKey.load(UUID.fromString(uuid), keySection)
			}
		}
	}

	fun save() {
		persistenceVersion++
		persistenceDirty = true
	}

	private fun flushPersistenceAsync() {
		if (!persistenceDirty || !persistenceWriteInFlight.compareAndSet(false, true)) return
		val version = persistenceVersion
		persistenceDirty = false
		val snapshot = try {
			persistenceSnapshot()
		} catch (exception: Exception) {
			persistenceDirty = true
			persistenceWriteInFlight.set(false)
			plugin.logger.log(Level.SEVERE, "Failed to snapshot fallen.yml.", exception)
			return
		}
		try {
			Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
				try {
					writePersistenceSnapshot(snapshot.saveToString(), version)
				} catch (exception: Exception) {
					persistenceDirty = true
					plugin.logger.warning("Failed to save fallen.yml: ${exception.message}")
				} finally {
					persistenceWriteInFlight.set(false)
				}
			})
		} catch (exception: RuntimeException) {
			persistenceDirty = true
			persistenceWriteInFlight.set(false)
			plugin.logger.log(Level.SEVERE, "Unable to schedule fallen.yml persistence.", exception)
		}
	}

	private fun flushPersistenceSynchronously() {
		if (!persistenceDirty && persistenceVersion <= persistedVersion) return
		val version = persistenceVersion
		try {
			val snapshot = persistenceSnapshot()
			writePersistenceSnapshot(snapshot.saveToString(), version)
			persistenceDirty = false
		} catch (exception: Exception) {
			plugin.logger.log(Level.SEVERE, "Failed to save fallen.yml during shutdown.", exception)
		}
	}

	private fun persistenceSnapshot(): YamlConfiguration {
		val config = YamlConfiguration()
		config["phase"] = phase.name
		config["started-at"] = startedAtMillis
		config["ended-at"] = endedAtMillis
		config["effective-game-time"] = effectiveGameTimeMillis
		config["last-placed-key-score-at"] = lastPlacedKeyScoreAt
		config["last-refresh-key-at"] = lastRefreshKeyAt
		for ((team, score) in scores) config["scores.${team.name}"] = score
		for ((team, count) in kills) config["kills.${team.name}"] = count
		for ((team, count) in convertedKeys) config["converted-keys.${team.name}"] = count
		for ((team, count) in destroyedKeys) config["destroyed-keys.${team.name}"] = count
		for ((team, teamRegions) in regions) {
			for ((index, region) in teamRegions.withIndex()) {
				region.save(config.createSection("regions.${team.name}.$index"))
			}
		}
		for ((id, until) in stationDisruptedUntil) config["station-disrupted-until.$id"] = until
		for ((id, until) in keyJammedUntil) config["key-jammed-until.$id"] = until
		for ((id, until) in keyAlertUntil) config["key-alert-until.$id"] = until
		for ((team, until) in teamRespawnBoostUntil) config["team-respawn-boost-until.${team.name}"] = until
		for ((playerId, team) in playerTeams) config["players.$playerId"] = team.name
		config["deployed-players"] = deployedPlayers.map(UUID::toString)
		for ((playerId, deaths) in deathCounts) config["deaths.$playerId"] = deaths
		config["loadout-initialized-players"] = loadoutInitializedPlayers.map(UUID::toString)
		config["loadout-restore-pending"] = loadoutRestorePending.map(UUID::toString)
		config["combat-logout-pending"] = combatLogoutPending.map(UUID::toString)
		config["elytra-players"] = elytraPlayers.map(UUID::toString)
		for ((playerId, availableAt) in gearSwitchAvailableAt) config["gear-switch-available-at.$playerId"] = availableAt
		for ((playerId, path) in upgradePaths) config["upgrade-paths.$playerId"] = path.name
		for ((key, nextAt) in upgradeSupplyNextAt) config["upgrade-supply-next-at.$key"] = nextAt
		for ((playerId, wait) in respawnWaits) {
			config["respawn-waits.$playerId.until"] = wait.untilMillis
			config["respawn-waits.$playerId.world"] = wait.worldName
			config["respawn-waits.$playerId.x"] = wait.x
			config["respawn-waits.$playerId.y"] = wait.y
			config["respawn-waits.$playerId.z"] = wait.z
		}
		for ((playerId, keyIds) in unresolvedCaptures) {
			config["unresolved-captures.$playerId"] = keyIds.map(UUID::toString)
		}
		config["placed-scoring-blocks"] = placedScoringBlocks.toList()
		for ((playerId, chunks) in exploredFlightChunks) config["explored-flight-chunks.$playerId"] = chunks.toList()
		for ((playerId, ledger) in flightRewardLedgers) {
			config["flight-reward-ledgers.$playerId.hour-bucket"] = ledger.hourBucket
			config["flight-reward-ledgers.$playerId.day-bucket"] = ledger.dayBucket
			config["flight-reward-ledgers.$playerId.hour-points"] = ledger.hourPoints
			config["flight-reward-ledgers.$playerId.day-points"] = ledger.dayPoints
		}
		laboratoryTnt.entries.forEachIndexed { index, (location, placement) ->
			config["laboratory-tnt.$index.location"] = location
			config["laboratory-tnt.$index.owner"] = placement.owner.toString()
			config["laboratory-tnt.$index.team"] = placement.team.name
		}
		config["eliminated"] = eliminatedTeams.map { it.name }
		config["announced"] = announcedMilestones.toList()
		for ((team, since) in dangerSince) config["danger-since.${team.name}"] = since
		for ((id, reveal) in preciseReveals) {
			config["precise-reveals.$id.requester"] = reveal.requesterTeam.name
			config["precise-reveals.$id.target"] = reveal.targetTeam.name
			config["precise-reveals.$id.key"] = reveal.keyId.toString()
			config["precise-reveals.$id.until"] = reveal.untilMillis
		}
		for (key in keys.values) key.save(config.createSection("keys.${key.id}"))
		return config
	}

	@Throws(IOException::class)
	private fun writePersistenceSnapshot(content: String, version: Long) {
		synchronized(persistenceWriteLock) {
			if (version <= persistedVersion) return
			val destination = dataFile.toPath().toAbsolutePath().normalize()
			val parent = destination.parent
			if (parent != null) Files.createDirectories(parent)
			val temporary = destination.resolveSibling("${destination.fileName}.tmp")
			try {
				Files.writeString(
					temporary,
					content,
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE
				)
				try {
					Files.move(
						temporary,
						destination,
						StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING
					)
				} catch (_: AtomicMoveNotSupportedException) {
					Files.move(
						temporary,
						destination,
						StandardCopyOption.REPLACE_EXISTING
					)
				}
				persistedVersion = version
			} catch (exception: IOException) {
				runCatching { Files.deleteIfExists(temporary) }
				throw exception
			}
		}
	}

	companion object {
		private const val LOADOUT_HELMET = "helmet"
		private const val LOADOUT_CHESTPLATE = "chestplate"
		private const val LOADOUT_LEGGINGS = "leggings"
		private const val LOADOUT_BOOTS = "boots"
		private const val LOADOUT_ELYTRA = "elytra"
		private const val LOADOUT_SWORD = "sword"
		private const val LOADOUT_PICKAXE = "pickaxe"
		private const val LOADOUT_PEARLS = "pearls"
		private const val LOADOUT_HEALING = "upgrade_healing"
		private const val LOADOUT_TNT = "upgrade_tnt"
		private const val LOADOUT_FIREWORK = "upgrade_firework"
		private val CORE_LOADOUT_KINDS = setOf(
			LOADOUT_HELMET,
			LOADOUT_CHESTPLATE,
			LOADOUT_LEGGINGS,
			LOADOUT_BOOTS,
			LOADOUT_ELYTRA,
			LOADOUT_SWORD,
			LOADOUT_PICKAXE,
			LOADOUT_PEARLS
		)
		private const val ACCELERATION_HARNESS_COST = 700
		private const val ACCELERATION_HARNESS_FLYING_SPEED = 0.15
		private const val ACTIVITY_STATUS_WARNING_INTERVAL_MILLIS = 60_000L
		private const val PERSISTENCE_INTERVAL_TICKS = 20L
		private const val REFRESH_KEY_LABEL_ANOMALY_ONE_IN = 16
		private const val REFILLED_SUPPLY_LABEL_ANOMALY_ONE_IN = 24
		private const val FINALE_STEP_TICKS = 5
		private const val FINALE_BLINDNESS_TICKS = 60
		private const val FINALE_REVEAL_TICKS = 60
		private const val FINALE_COLLAPSE_START_TICKS = 90
		private const val FINALE_FRACTURE_LEAD_TICKS = 10
		private const val FINALE_CHUNKS_PER_PLAYER_STEP = 16
		private const val FINALE_EXIT_NOTICE_DELAY_TICKS = 40
		private const val FINALE_KICK_DELAY_TICKS = 120
		private const val FINALE_MAX_CHUNK_RADIUS = 16
		private const val FINALE_DEBRIS_PER_PLAYER_PER_WAVE = 3
		private const val FINALE_MAX_DEBRIS_PER_WAVE = 12
		private const val FINALE_MAX_DEBRIS_ATTEMPTS_PER_WAVE = 24
		private const val FINALE_DEBRIS_LIFETIME_TICKS = 40L
		private val FINALE_PROGRESS_MILESTONES = listOf(25, 50, 75)
		private const val KILL_COMMENT_ONE_IN = 5
		private const val KILL_COMMENT_COOLDOWN_MILLIS = 20 * 1000L
		private const val ALLOY_BULLET_BASE_DAMAGE = 2.0
		private const val ALLOY_BULLET_SPEED_BLOCKS_PER_TICK = 20.0
		private const val ALLOY_BULLET_MAX_LIFETIME_TICKS = 100
		private val KEY_SHAPE_PIXELS = listOf(
			-4 to 1, -4 to 2, -3 to 3, -2 to 3, -1 to 2, -1 to 1, -2 to 0, -3 to 0,
			-1 to 1, 0 to 1, 1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1,
			3 to 0, 4 to 0, 5 to -1,
			2 to 0, 2 to -1,
			4 to 0, 4 to -1
		)
		private val STATION_FEATHER_PIXELS = listOf(
			0 to 4, 0 to 3, 0 to 2, 0 to 1, 0 to 0, 0 to -1, 0 to -2, 0 to -3,
			1 to 3, 2 to 3, 1 to 2, 2 to 2, 3 to 2, 1 to 1, 2 to 1,
			-1 to 2, -2 to 2, -1 to 1, -2 to 1, -3 to 1, -1 to 0, -2 to 0,
			1 to -1, 2 to -1, -1 to -2, -2 to -2
		)
		private val EVENT_START_MILLIS: Long = FallenAccessPolicy.eventStartsAt.toEpochMilli()
		private const val OVERWORLD_NAME = "world"
		private const val INITIAL_KEYS_PER_TEAM = 5
		private const val CAPTURE_MILLIS = 6 * 1000L
		private const val DROP_CONFIRM_MILLIS = 5_000L
		private const val DROPPED_KEY_RECONCILE_INTERVAL_MILLIS = 5_000L
		private const val SELF_DESTRUCT_MILLIS = 8 * 60 * 1000L
		private const val RESPAWN_PROTECTION_MILLIS = 8_000L
		private const val SAFE_RESPAWN_SEARCH_ATTEMPTS = 16
		private const val SAFE_RESPAWN_ENEMY_RADIUS_SQUARED = 24.0 * 24.0
		private const val RESPAWN_WAIT_MOVEMENT_EPSILON_SQUARED = 0.0001
		private const val TICK_FAILURE_WARNING_INTERVAL_MILLIS = 60_000L
		private const val PLACED_KEY_SCORE_INTERVAL_MILLIS = 10 * 60 * 1000L
		private const val ELIMINATION_GRACE_MILLIS = 10 * 60 * 1000L
		private const val DEPLOYMENT_MILLIS = 2 * 60 * 60 * 1000L
		private const val MAX_GAME_MILLIS = 144 * 60 * 60 * 1000L
		private const val OVERTIME_MILLIS = 30 * 60 * 1000L
		private const val REFRESH_KEY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
		private const val REFRESH_KEY_EXPIRY_MILLIS = 2 * 60 * 60 * 1000L
		private const val DAMAGE_SCORE_WINDOW_MILLIS = 30 * 1000L
		private const val ASSIST_WINDOW_MILLIS = 30 * 1000L
		private const val COMPASS_COST = 600
		private const val MAX_COMPASSES_PER_TEAM = 3
		private const val COMPASS_DURATION_MILLIS = 20 * 60 * 1000L
		private const val COMPASS_REFRESH_INTERVAL_MILLIS = 30 * 1000L
		private const val PRECISE_REVEAL_DURATION_MILLIS = 3 * 60 * 1000L
		private const val PRECISE_REVEAL_COOLDOWN_MILLIS = 30 * 60 * 1000L
		private const val KEY_JAMMER_MILLIS = 10 * 60 * 1000L
		private const val KEY_ALERT_MILLIS = 30 * 60 * 1000L
		private const val KEY_ALERT_RADIUS = 30.0
		private const val KEY_UTILITY_BIND_RADIUS = 30.0
		private const val KEY_ALERT_NOTIFY_COOLDOWN_MILLIS = 60 * 1000L
		private const val TRACKING_DUST_ARMED_MILLIS = 10 * 60 * 1000L
		private const val TRACKING_DURATION_MILLIS = 60 * 1000L
		private const val BLAST_PROTECTION_MILLIS = 120 * 1000L
		private const val TEAM_RESPAWN_BOOST_MILLIS = 30 * 60 * 1000L
		private const val TEAM_RESPAWN_PROTECTION_MILLIS = 10 * 1000L
		private const val LABORATORY_TNT_CAP_PER_TEAM = 128
		private const val INTERNAL_GAME_MODE_CHANGE_WINDOW_MILLIS = 2 * 1000L
		private const val JAMMED_REVEAL_NOTICE_COOLDOWN_MILLIS = 30 * 1000L
		private const val SCOREBOARD_OBJECTIVE = "fallen_status"
		private const val STATION_USE_MILLIS = 3 * 1000L
		private const val STATION_DISRUPT_MILLIS_REQUIRED = 8 * 1000L
		private const val STATION_REPAIR_MILLIS = 15 * 1000L
		private const val STATION_DISRUPT_MILLIS = 10 * 60 * 1000L
		private const val STATION_COOLDOWN_MILLIS = 60 * 1000L
		private const val STATION_PROTECTION_MILLIS = 3 * 1000L
		private const val STATION_ALERT_COOLDOWN_MILLIS = 60 * 1000L
		private const val STATION_DENY_COOLDOWN_MILLIS = 5 * 1000L
		private const val COMBAT_TAG_MILLIS = 10 * 1000L
		private const val KEY_VISUAL_RADIUS = 80.0
		private const val STATION_VISUAL_RADIUS = 80.0
		private const val TRACKING_VISUAL_RADIUS = 96.0
		private const val KEY_PLACEMENT_BURST_COUNT = 3
		private const val KEY_PLACEMENT_BURST_FRAMES = 6
		private const val KEY_PLACEMENT_BURST_POINTS = 24
		private const val MAIN_CITY_EXHAUSTION_MULTIPLIER = 0.65f
		private const val MINING_SPEED_BONUS = 0.10
		private const val TERRITORY_MOVEMENT_SPEED_BONUS = 0.05
		private const val PROGRESS_BAR_SEGMENTS = 20
		private val MINING_BONUS_MATERIALS = setOf(
			Material.GRASS_BLOCK,
			Material.DIRT,
			Material.COARSE_DIRT,
			Material.ROOTED_DIRT,
			Material.PODZOL,
			Material.MYCELIUM,
			Material.MUD,
			Material.PACKED_MUD,
			Material.CLAY,
			Material.GRAVEL,
			Material.SAND,
			Material.RED_SAND,
			Material.STONE,
			Material.COBBLESTONE,
			Material.MOSSY_COBBLESTONE,
			Material.SMOOTH_STONE,
			Material.STONE_BRICKS,
			Material.MOSSY_STONE_BRICKS,
			Material.CRACKED_STONE_BRICKS,
			Material.CHISELED_STONE_BRICKS,
			Material.GRANITE,
			Material.POLISHED_GRANITE,
			Material.DIORITE,
			Material.POLISHED_DIORITE,
			Material.ANDESITE,
			Material.POLISHED_ANDESITE,
			Material.DEEPSLATE,
			Material.COBBLED_DEEPSLATE,
			Material.POLISHED_DEEPSLATE,
			Material.DEEPSLATE_BRICKS,
			Material.CRACKED_DEEPSLATE_BRICKS,
			Material.DEEPSLATE_TILES,
			Material.CRACKED_DEEPSLATE_TILES,
			Material.CHISELED_DEEPSLATE,
			Material.TUFF,
			Material.POLISHED_TUFF,
			Material.TUFF_BRICKS,
			Material.CHISELED_TUFF,
			Material.CHISELED_TUFF_BRICKS,
			Material.CALCITE,
			Material.DRIPSTONE_BLOCK,
			Material.NETHERRACK,
			Material.BLACKSTONE,
			Material.POLISHED_BLACKSTONE,
			Material.BASALT,
			Material.SMOOTH_BASALT,
			Material.END_STONE
		)
		private val UNSAFE_RESPAWN_MATERIALS = setOf(
			Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS,
			Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW
		)
	}

	private data class PreciseReveal(
		val requesterTeam: FallenTeam,
		val targetTeam: FallenTeam,
		val keyId: UUID,
		val untilMillis: Long
	)

	private data class ActiveTrack(val targetId: UUID, val untilMillis: Long)

	private data class ElytraSample(
		val origin: Location,
		val startedAtMillis: Long,
		var discoveredNewChunk: Boolean,
		var enteredEnemyRegion: Boolean,
		var wasInEnemyRegion: Boolean
	)

	private data class FlightRewardLedger(
		var hourBucket: String,
		var dayBucket: String,
		var hourPoints: Int,
		var dayPoints: Int
	)

	private data class LaboratoryTntPlacement(val owner: UUID, val team: FallenTeam)

	private data class RespawnWait(
		val untilMillis: Long,
		val worldName: String,
		val x: Double,
		val y: Double,
		val z: Double
	) {
		fun location(): Location? {
			val world = Bukkit.getWorld(worldName) ?: return null
			return Location(world, x, y, z)
		}
	}

	private data class DamageScoreWindow(val startedAtMillis: Long, var score: Int)

	private data class AreaDisplay(val title: String, val color: BarColor)

}
