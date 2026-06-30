package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Difficulty
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.RenderType
import vip.qoriginal.quantumplugin.CommandMessages
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.roundToInt

class FallenGameService(private val plugin: JavaPlugin) {
	private val keyIdKey = NamespacedKey(plugin, "fallen_key_id")
	private val compassOwnerTeamKey = NamespacedKey(plugin, "fallen_compass_owner_team")
	private val compassTargetTeamKey = NamespacedKey(plugin, "fallen_compass_target_team")
	private val compassTargetKeyIdKey = NamespacedKey(plugin, "fallen_compass_target_key_id")
	private val compassExpiresAtKey = NamespacedKey(plugin, "fallen_compass_expires_at")
	private val compassNextRefreshAtKey = NamespacedKey(plugin, "fallen_compass_next_refresh_at")
	private val forbiddenCustomTntKey = NamespacedKey(plugin, "custom_tnt")
	private val forbiddenBuffSnowballKey = NamespacedKey(plugin, "buff_snowball")
	private val dataFile = File(plugin.dataFolder, "fallen.yml")
	private val playerTeams = ConcurrentHashMap<UUID, FallenTeam>()
	private val scores = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val kills = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val convertedKeys = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val destroyedKeys = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
	private val regions = EnumMap<FallenTeam, MutableList<FallenRegion>>(FallenTeam::class.java)
	private val keys = ConcurrentHashMap<UUID, FallenKey>()
	private val deathCounts = ConcurrentHashMap<UUID, Int>()
	private val dropConfirmUntil = ConcurrentHashMap<UUID, Long>()
	private val captureProgress = ConcurrentHashMap<String, Long>()
	private val stationUseProgress = ConcurrentHashMap<String, Long>()
	private val stationDisruptProgress = ConcurrentHashMap<String, Long>()
	private val stationRepairProgress = ConcurrentHashMap<String, Long>()
	private val stationCooldownUntil = ConcurrentHashMap<UUID, Long>()
	private val combatUntil = ConcurrentHashMap<UUID, Long>()
	private val recentCaptureUntil = ConcurrentHashMap<UUID, Long>()
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
	private val teamBeacons = ConcurrentHashMap<FallenTeam, TeamBeacon>()
	private val elytraSamples = ConcurrentHashMap<UUID, ElytraSample>()
	private val allowedGameModeChanges = ConcurrentHashMap<UUID, Long>()
	private val dangerSince = EnumMap<FallenTeam, Long>(FallenTeam::class.java)
	private val eliminatedTeams = HashSet<FallenTeam>()
	private val announcedMilestones = HashSet<String>()
	private val scoreboardLines = HashSet<String>()
	private val areaBossBars = ConcurrentHashMap<UUID, BossBar>()

	// Regions are fixed for the event. Fill these with FallenRegion.of(OVERWORLD_NAME, ...)
	// after the final rectangular boundaries are decided.
	private val fixedRegions: Map<FallenTeam, List<FallenRegion>> = mapOf(
		FallenTeam.A to listOf(
			FallenRegion.of(OVERWORLD_NAME, -96, -64, -48, -17, 320, 48),
			FallenRegion.of(OVERWORLD_NAME, -96, -64, 80, -17, 320, 176)
		),
		FallenTeam.B to listOf(
			FallenRegion.of(OVERWORLD_NAME, 17, -64, -48, 96, 320, 48)
		),
		FallenTeam.C to listOf(
			FallenRegion.of(OVERWORLD_NAME, -40, -64, -176, 40, 320, -80)
		)
	)

	// Local flat-world test stations. Replace only coordinates when the final map is ready.
	private val fixedStations = listOf(
		FallenStation("a_old_city", FallenTeam.A, OVERWORLD_NAME, -56, -60, 0, setOf("a_fu_island")),
		FallenStation("a_fu_island", FallenTeam.A, OVERWORLD_NAME, -56, -60, 128, setOf("a_old_city"))
	)
	private var tickTask: BukkitTask? = null
	private var visualTask: BukkitTask? = null
	private var lastPlacedKeyScoreAt = 0L
	private var lastRefreshKeyAt = 0L
	private var startedAtMillis = 0L
	private var endedAtMillis = 0L

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
		normalizeScheduledTimeline()
		updateScoreboard()
		tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
		visualTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { renderVisuals() }, 5L, 5L)
	}

	fun stop() {
		tickTask?.cancel()
		tickTask = null
		visualTask?.cancel()
		visualTask = null
		clearAreaBossBars()
		clearScoreboard()
		save()
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
	}

	fun startGame() {
		startedAtMillis = EVENT_START_MILLIS
		endedAtMillis = 0L
		lastPlacedKeyScoreAt = EVENT_START_MILLIS + DEPLOYMENT_MILLIS
		lastRefreshKeyAt = EVENT_START_MILLIS
		announcedMilestones.clear()
		dangerSince.clear()
		eliminatedTeams.clear()
		phase = FallenPhase.DEPLOYMENT
		applyWorldRules()
		ensureInitialKeys()
		broadcast(Component.text("《陷落》活动开始。部署阶段持续 2 小时。", NamedTextColor.GOLD))
		save()
	}

	fun endGame(reason: String = "活动结束") {
		if (phase == FallenPhase.ENDED) return
		phase = FallenPhase.ENDED
		endedAtMillis = System.currentTimeMillis()
		val winners = winnerTeams()
		val winnerText = if (winners.isEmpty()) "无胜者" else winners.joinToString("、") { it.displayName }
		broadcast(Component.text("$reason。胜者: $winnerText", NamedTextColor.GOLD))
		broadcastSettlement()
		save()
	}

	fun elapsedMillis(): Long = if (startedAtMillis == 0L) 0L else ((endedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()) - startedAtMillis).coerceAtLeast(0L)

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
		playerTeams[playerId] = team
		save()
	}

	fun clearTeam(playerId: UUID) {
		playerTeams.remove(playerId)
		save()
	}

	fun teamOf(player: Player): FallenTeam? = playerTeams[player.uniqueId]

	fun scoreSnapshot(): Map<FallenTeam, Int> = scores.toMap()

	fun regionSnapshot(): Map<FallenTeam, List<FallenRegion>> {
		return FallenTeam.entries.associateWith { regionsOf(it) }
	}

	fun keySnapshot(): List<FallenKey> = keys.values.sortedBy { it.id.toString() }

	fun eliminatedSnapshot(): Set<FallenTeam> = eliminatedTeams.toSet()

	fun setScore(team: FallenTeam, amount: Int) {
		scores[team] = amount
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
		scores[team] = (scores[team] ?: 0) + amount
	}

	fun createKeyItem(owner: FallenTeam, original: FallenTeam = owner, type: FallenKeyType = FallenKeyType.INITIAL): ItemStack {
		val key = FallenKey(UUID.randomUUID(), owner, original, FallenKeyState.ITEM, type)
		if (type == FallenKeyType.REFRESH) {
			key.expiresAtMillis = System.currentTimeMillis() + REFRESH_KEY_EXPIRY_MILLIS
		}
		keys[key.id] = key
		save()
		return itemFor(key)
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
		key.holder = target.uniqueId
		target.inventory.addItem(itemFor(key))
		target.sendMessage(Component.text("你收到了 ${team.displayName} 密钥 ${key.shortId()}。", team.color))
	}

	fun itemFor(key: FallenKey): ItemStack {
		val item = ItemStack(Material.TRIPWIRE_HOOK)
		val meta = item.itemMeta
		meta.displayName(Component.text("陷落密钥 ${key.shortId()}", key.ownerTeam.color))
		meta.lore(
			listOf(
				Component.text("当前阵营: ${key.ownerTeam.displayName}", NamedTextColor.GRAY),
				Component.text("原始阵营: ${key.originalTeam.displayName}", NamedTextColor.GRAY),
				Component.text("类型: ${key.type.name}", NamedTextColor.DARK_GRAY)
			)
		)
		meta.persistentDataContainer.set(keyIdKey, PersistentDataType.STRING, key.id.toString())
		item.itemMeta = meta
		return item
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
			val score = scores[ownerTeam] ?: 0
			if (score < COMPASS_COST) {
				CommandMessages.warning(player, "阵营积分不足，需要 $COMPASS_COST 分。")
				return false
			}
			addScore(ownerTeam, -COMPASS_COST)
		}
		player.inventory.addItem(compassItem(ownerTeam, targetTeam, targetKey))
		alertTeam(targetTeam, Component.text("${ownerTeam.displayName} 的 ${player.name} 正在定位你方密钥，距离约 ${distanceBand(player.location.distance(targetKey.center() ?: player.location))}。", NamedTextColor.YELLOW))
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
				player.inventory.addItem(ItemStack(Material.GOLDEN_CARROT, 32), ItemStack(Material.ARROW, 32), ItemStack(Material.FIREWORK_ROCKET, 32))
				CommandMessages.success(player, "已购买阵营补给包。")
			}
			"advanced" -> {
				if (!spendScore(player, team, 800)) return false
				player.inventory.addItem(ItemStack(Material.GOLDEN_APPLE, 4), ItemStack(Material.ENDER_PEARL, 16), ItemStack(Material.FIREWORK_ROCKET, 48))
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
			"jammer" -> {
				if (!requireCaptureShop(player, "部署区域干扰器")) return false
				val key = nearbyOwnPlacedKey(player, team, "区域干扰器") ?: return false
				if (!spendScore(player, team, 500)) return false
				keyJammedUntil[key.id] = System.currentTimeMillis() + KEY_JAMMER_MILLIS
				alertTeam(team, Component.text("密钥 ${key.shortId()} 已部署区域干扰器，10 分钟内不能被精确揭露。", NamedTextColor.AQUA))
			}
			"tracking" -> {
				if (!requireCaptureShop(player, "激活追踪粉尘")) return false
				if (!spendScore(player, team, 400)) return false
				trackingDustUntil[player.uniqueId] = System.currentTimeMillis() + TRACKING_DUST_ARMED_MILLIS
				CommandMessages.success(player, "已激活追踪粉尘，10 分钟内首次命中敌方玩家后追踪 60 秒。")
			}
			"blast" -> {
				if (!spendScore(player, team, 900)) return false
				blastProtectionUntil[player.uniqueId] = System.currentTimeMillis() + BLAST_PROTECTION_MILLIS
				CommandMessages.success(player, "已获得 120 秒防爆增益。")
			}
			"respawn" -> {
				if (!spendScore(player, team, 900)) return false
				teamRespawnBoostUntil[team] = System.currentTimeMillis() + TEAM_RESPAWN_BOOST_MILLIS
				alertTeam(team, Component.text("阵营复活保护已启用，30 分钟内区域复活保护延长至 10 秒。", NamedTextColor.AQUA))
			}
			"keyalert" -> {
				if (!requireCaptureShop(player, "部署密钥警戒")) return false
				val key = nearbyOwnPlacedKey(player, team, "密钥警戒") ?: return false
				if (!spendScore(player, team, 700)) return false
				keyAlertUntil[key.id] = System.currentTimeMillis() + KEY_ALERT_MILLIS
				alertTeam(team, Component.text("密钥 ${key.shortId()} 已部署密钥警戒，30 分钟内敌方靠近 30 格会提醒。", NamedTextColor.AQUA))
			}
			"beacon" -> {
				if (!isInTeamRegion(team, player.location)) {
					CommandMessages.warning(player, "传送信标只能设置在己方阵营区域内。")
					return false
				}
				if (!spendScore(player, team, 1200)) return false
				teamBeacons[team] = TeamBeacon(player.location.clone(), System.currentTimeMillis() + TEAM_BEACON_MILLIS)
				alertTeam(team, Component.text("${player.name} 设置了临时传送信标，30 分钟内可用。", NamedTextColor.AQUA))
			}
			else -> throw IllegalArgumentException("未知购买项: $item")
		}
		save()
		return true
	}

	fun teleportToBeacon(player: Player): Boolean {
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		val beacon = teamBeacons[team]
		if (beacon == null || beacon.expiresAtMillis <= System.currentTimeMillis()) {
			teamBeacons.remove(team)
			CommandMessages.warning(player, "你的阵营没有可用的传送信标。")
			return true
		}
		if (!isInTeamRegion(team, player.location)) {
			CommandMessages.warning(player, "只能从己方阵营区域内使用传送信标。")
			return true
		}
		if (!sameTeamRegion(team, player.location, beacon.location)) {
			CommandMessages.warning(player, "传送信标不能跨阵营区域使用。")
			return true
		}
		if (hasKeyItem(player)) {
			CommandMessages.warning(player, "持有密钥时不能使用传送信标。")
			return true
		}
		if (isCombatTagged(player)) {
			CommandMessages.warning(player, "战斗状态下不能使用传送信标。")
			return true
		}
		player.teleport(beacon.location)
		CommandMessages.success(player, "已传送至阵营信标。")
		return true
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
			key.state = FallenKeyState.DESTROYED
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
		return UUID.fromString(raw)
	}

	fun isLiveKeyItem(item: ItemStack?): Boolean {
		val id = keyId(item) ?: return false
		return keys[id]?.state != FallenKeyState.DESTROYED
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
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (team in eliminatedTeams) {
			CommandMessages.error(player, "你的阵营已经出局，不能放置密钥。")
			return true
		}
		if (key.type == FallenKeyType.REFRESH && key.ownerTeam != team) {
			CommandMessages.warning(player, "刷新密钥不能由敌方阵营使用。")
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
		item.amount -= 1
		recentCaptureUntil.remove(player.uniqueId)
		if (key.originalTeam != team) {
			addScore(team, 500)
			convertedKeys[team] = (convertedKeys[team] ?: 0) + 1
			addScore(key.originalTeam, -400)
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
		if (!phase.allowsKeyCapture()) {
			CommandMessages.warning(player, "当前阶段不能启动密钥自毁。")
			return true
		}
		val id = keyId(item) ?: return false
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
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (key.type == FallenKeyType.REFRESH && key.ownerTeam != team) {
			CommandMessages.warning(player, "刷新密钥不能由敌方阵营使用。")
			return true
		}
		if (key.type == FallenKeyType.REFRESH) {
			CommandMessages.warning(player, "刷新密钥不能用于自毁得分，请带回己方区域放置。")
			return true
		}
		val now = System.currentTimeMillis()
		val confirmUntil = dropConfirmUntil[player.uniqueId] ?: 0L
		if (confirmUntil < now) {
			dropConfirmUntil[player.uniqueId] = now + DROP_CONFIRM_MILLIS
			CommandMessages.warning(player, "再次丢弃密钥以确认启动 10 分钟自毁。")
			return true
		}
		dropConfirmUntil.remove(player.uniqueId)
		key.ownerTeam = team
		key.state = FallenKeyState.SELF_DESTRUCTING
		key.holder = player.uniqueId
		key.selfDestructAtMillis = now + SELF_DESTRUCT_MILLIS
		broadcast(Component.text("${player.name} 启动了密钥 ${key.shortId()} 的自毁倒计时。", NamedTextColor.YELLOW))
		save()
		return true
	}

	fun dropPlayerKeys(player: Player) {
		for (item in player.inventory.contents.filterNotNull()) {
			val id = keyId(item) ?: continue
			player.world.dropItemNaturally(player.location, item.clone())
			item.amount = 0
			markKeyDropped(id, player.location)
		}
		save()
	}

	fun handleQuit(player: Player) {
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
				if (it.state != FallenKeyState.SELF_DESTRUCTING) {
					it.state = FallenKeyState.ITEM
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

	fun handleKeyPickup(player: Player, item: ItemStack): Boolean {
		val id = keyId(item) ?: return false
		val key = keys[id] ?: return false
		if (key.state == FallenKeyState.DESTROYED) {
			CommandMessages.warning(player, "这个密钥已作废。")
			item.amount = 0
			return true
		}
		val team = teamOf(player)
		if (key.type == FallenKeyType.REFRESH && team != key.ownerTeam) {
			CommandMessages.warning(player, "刷新密钥不能被敌方阵营拾取。")
			return false
		}
		key.holder = player.uniqueId
		if (key.state != FallenKeyState.SELF_DESTRUCTING) {
			key.state = FallenKeyState.ITEM
		}
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
			key.holder = player.uniqueId
			player.inventory.addItem(itemFor(key))
			claimed++
		}
		if (claimed > 0) {
			player.sendMessage(Component.text("你领取了 $claimed 个阵营公共密钥。", NamedTextColor.GOLD))
			save()
		}
	}

	fun handleDeath(player: Player) {
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		deathCounts[player.uniqueId] = (deathCounts[player.uniqueId] ?: 0) + 1
		addScore(team, -50)
		if (hasKeyItem(player)) {
			addScore(team, -100)
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
		val now = System.currentTimeMillis()
		activateTrackingDust(attacker, target, now)
		combatUntil[attacker.uniqueId] = now + COMBAT_TAG_MILLIS
		combatUntil[target.uniqueId] = now + COMBAT_TAG_MILLIS
		recentAttackers.computeIfAbsent(target.uniqueId) { ConcurrentHashMap() }[attacker.uniqueId] = now
		val score = finalDamage.toInt().coerceAtLeast(0)
		if (score <= 0) return
		val windowKey = "${attacker.uniqueId}:${target.uniqueId}"
		val window = damageScoreWindows.compute(windowKey) { _, current ->
			if (current == null || now - current.startedAtMillis >= DAMAGE_SCORE_WINDOW_MILLIS) {
				DamageScoreWindow(now, 0)
			} else {
				current
			}
		} ?: return
		val grant = score.coerceAtMost((DAMAGE_SCORE_CAP_PER_WINDOW - window.score).coerceAtLeast(0))
		if (grant > 0) {
			window.score += grant
			addScore(attackerTeam, grant)
		}
	}

	fun applyBlastProtection(player: Player): Boolean {
		val until = blastProtectionUntil[player.uniqueId] ?: return false
		val now = System.currentTimeMillis()
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
			addScore(killerTeam, 80)
			kills[killerTeam] = (kills[killerTeam] ?: 0) + 1
		}
		val now = System.currentTimeMillis()
		val assists = recentAttackers.remove(victim.uniqueId).orEmpty()
		for ((attackerId, lastDamageAt) in assists) {
			if (now - lastDamageAt > ASSIST_WINDOW_MILLIS || attackerId == killer?.uniqueId) continue
			val attacker = Bukkit.getPlayer(attackerId) ?: continue
			val attackerTeam = teamOf(attacker) ?: continue
			if (attackerTeam == victimTeam || attackerTeam in eliminatedTeams) continue
			addScore(attackerTeam, 30)
		}
		save()
	}

	fun recordBlockBreak(player: Player, material: Material) {
		if (!phase.allowsKeyCapture()) return
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		val score = when (material) {
			Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> 15
			Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE -> 20
			Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> 5
			Material.DEEPSLATE_COAL_ORE -> 8
			Material.ANCIENT_DEBRIS -> 60
			else -> 0
		}
		if (score > 0) {
			addScore(team, score)
			save()
		}
	}

	fun handleStationCoreBreak(player: Player, location: Location): Boolean {
		if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return false
		val team = teamOf(player) ?: return false
		if (team in eliminatedTeams) return false
		val now = System.currentTimeMillis()
		val station = fixedStations.firstOrNull { it.team != team && it.containsCore(location) } ?: return false
		if (isStationDisrupted(station, now)) return true
		stationDisruptedUntil[station.id] = now + STATION_DISRUPT_MILLIS
		alertTeam(station.team, Component.text("${team.displayName} 的 ${player.name} 破坏传送站 ${station.id} 核心，站点被干扰 10 分钟。", NamedTextColor.RED))
		save()
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
		return regionsOf(team).randomOrNull()?.randomSpawn()
	}

	fun recordMovement(player: Player, from: Location, to: Location) {
		if (!phase.allowsKeyCapture() || !player.isGliding) {
			elytraSamples.remove(player.uniqueId)
			return
		}
		if (from.world != to.world) {
			elytraSamples[player.uniqueId] = ElytraSample(to.clone(), System.currentTimeMillis(), 0L)
			return
		}
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams || isNearOwnRegionCenter(team, to, 100.0)) {
			elytraSamples.remove(player.uniqueId)
			return
		}
		val now = System.currentTimeMillis()
		val sample = elytraSamples[player.uniqueId]
		if (sample == null) {
			elytraSamples[player.uniqueId] = ElytraSample(to.clone(), now, 0L)
			return
		}
		val elapsed = (now - sample.atMillis).coerceAtLeast(1L)
		val speed = sample.location.distance(to) / (elapsed / 1000.0)
		val accumulated = if (speed > ELYTRA_SCORE_SPEED) sample.accumulatedMillis + elapsed else 0L
		if (accumulated >= ELYTRA_SCORE_INTERVAL_MILLIS) {
			addScore(team, 10)
			elytraSamples[player.uniqueId] = ElytraSample(to.clone(), now, accumulated - ELYTRA_SCORE_INTERVAL_MILLIS)
			save()
			return
		}
		elytraSamples[player.uniqueId] = ElytraSample(to.clone(), now, accumulated)
	}

	fun protectRespawn(player: Player) {
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		val now = System.currentTimeMillis()
		val duration = if ((teamRespawnBoostUntil[team] ?: 0L) > now) TEAM_RESPAWN_PROTECTION_MILLIS else RESPAWN_PROTECTION_MILLIS
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
		val now = System.currentTimeMillis()
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
		if (phase != FallenPhase.IDLE && phase != FallenPhase.ENDED) {
			applyWorldRules()
		}
		processTimeline()
		processCaptures()
		processSelfDestruct()
		processRefreshKeys()
		processRefreshKeyExpiry()
		processPlacedKeyScore()
		processCompasses()
		processPreciseReveals()
		processActiveTracks()
		processKeyAlerts()
		processStations()
		processEliminations()
		updateAreaBossBars()
		updateScoreboard()
	}

	private fun renderVisuals() {
		renderPlacedKeys()
		renderStations()
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
				val remaining = (since + ELIMINATION_GRACE_MILLIS - System.currentTimeMillis()).coerceAtLeast(0L)
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
		if (startedAtMillis == 0L || phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return
		val now = System.currentTimeMillis()
		val deploymentEndsAt = startedAtMillis + DEPLOYMENT_MILLIS
		if (phase == FallenPhase.DEPLOYMENT) {
			announceRemaining("deployment-30m", deploymentEndsAt - now, 30 * 60 * 1000L, "部署阶段剩余 30 分钟。")
			announceRemaining("deployment-10m", deploymentEndsAt - now, 10 * 60 * 1000L, "部署阶段剩余 10 分钟。")
			announceRemaining("deployment-1m", deploymentEndsAt - now, 60 * 1000L, "部署阶段剩余 1 分钟。")
			if (now >= deploymentEndsAt) {
				phase = FallenPhase.ACTIVE
				broadcast(Component.text("部署阶段结束，密钥夺取已启用。", NamedTextColor.RED))
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
			lastPlacedKeyScoreAt = EVENT_START_MILLIS + DEPLOYMENT_MILLIS
		}
		if (lastRefreshKeyAt == 0L) {
			lastRefreshKeyAt = EVENT_START_MILLIS
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
			|| lastPlacedKeyScoreAt == 0L
			|| lastRefreshKeyAt == 0L
		startedAtMillis = EVENT_START_MILLIS
		if (lastPlacedKeyScoreAt == 0L || lastPlacedKeyScoreAt < EVENT_START_MILLIS + DEPLOYMENT_MILLIS) {
			lastPlacedKeyScoreAt = EVENT_START_MILLIS + DEPLOYMENT_MILLIS
		}
		if (lastRefreshKeyAt == 0L || lastRefreshKeyAt < EVENT_START_MILLIS) {
			lastRefreshKeyAt = EVENT_START_MILLIS
		}
		if (changed) save()
	}

	private fun renderPlacedKeys() {
		keys.values.asSequence()
			.filter { it.state == FallenKeyState.PLACED }
			.forEach { key ->
				val center = key.center() ?: return@forEach
				val world = center.world ?: return@forEach
				val dust = teamDust(key.ownerTeam)
				renderKeyOutline(world, key, dust)
				renderFloatingKeyShape(world, center, dust)
				world.spawnParticle(Particle.ELECTRIC_SPARK, center, 14, 1.5, 2.2, 1.5, 0.0)
			}
	}

	private fun renderKeyOutline(world: org.bukkit.World, key: FallenKey, dust: Particle.DustOptions) {
		val minX = key.x.toDouble()
		val maxX = key.x + FALLEN_KEY_WIDTH.toDouble()
		val minY = key.y.toDouble()
		val maxY = key.y + FALLEN_KEY_HEIGHT.toDouble()
		val minZ = key.z.toDouble()
		val maxZ = key.z + FALLEN_KEY_DEPTH.toDouble()

		for (step in 0..(FALLEN_KEY_WIDTH * 2)) {
			val xx = key.x + step / 2.0
			spawnDust(world, xx, minY, minZ, dust)
			spawnDust(world, xx, minY, maxZ, dust)
			spawnDust(world, xx, maxY, minZ, dust)
			spawnDust(world, xx, maxY, maxZ, dust)
		}
		for (step in 0..(FALLEN_KEY_DEPTH * 2)) {
			val zz = key.z + step / 2.0
			spawnDust(world, minX, minY, zz, dust)
			spawnDust(world, maxX, minY, zz, dust)
			spawnDust(world, minX, maxY, zz, dust)
			spawnDust(world, maxX, maxY, zz, dust)
		}
		for (step in 0..(FALLEN_KEY_HEIGHT * 2)) {
			val yy = key.y + step / 2.0
			spawnDust(world, minX, yy, minZ, dust)
			spawnDust(world, maxX, yy, minZ, dust)
			spawnDust(world, minX, yy, maxZ, dust)
			spawnDust(world, maxX, yy, maxZ, dust)
		}
	}

	private fun renderFloatingKeyShape(world: org.bukkit.World, center: Location, dust: Particle.DustOptions) {
		val pixels = listOf(
			-4 to 1, -4 to 2, -3 to 3, -2 to 3, -1 to 2, -1 to 1, -2 to 0, -3 to 0,
			-1 to 1, 0 to 1, 1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1,
			3 to 0, 4 to 0, 5 to -1,
			2 to 0, 2 to -1,
			4 to 0, 4 to -1
		)
		for (zOffset in listOf(-0.10, 0.10)) {
			for ((x, y) in pixels) {
				spawnDust(world, center.x + x * 0.28, center.y + y * 0.28, center.z + zOffset, dust)
			}
		}
		world.spawnParticle(Particle.END_ROD, center, 8, 0.75, 0.45, 0.15, 0.0)
	}

	private fun processCaptures() {
		if (!phase.allowsKeyCapture()) return
		val seen = HashSet<String>()
		for (key in keys.values) {
			if (key.state != FallenKeyState.PLACED) continue
			for (player in Bukkit.getOnlinePlayers()) {
				val team = teamOf(player)
				if (team == null || team == key.ownerTeam || team in eliminatedTeams || player.isDead || player.gameMode == GameMode.SPECTATOR) continue
				if (hasRespawnProtection(player)) continue
				if (!key.contains(player.location)) continue
				val progressKey = "${key.id}:${player.uniqueId}"
				val seconds = (captureProgress[progressKey] ?: 0L) + 1L
				captureProgress[progressKey] = seconds
				seen.add(progressKey)
				player.sendActionBar(progressBar("夺取密钥", seconds.toDouble() / CAPTURE_SECONDS, key.ownerTeam.color))
				if (seconds >= CAPTURE_SECONDS) {
					capture(player, team, key)
					return
				}
			}
		}
		captureProgress.keys.removeIf { it !in seen }
	}

	private fun capture(player: Player, capturingTeam: FallenTeam, key: FallenKey) {
		val oldOwner = key.ownerTeam
		key.ownerTeam = capturingTeam
		key.state = FallenKeyState.ITEM
		key.type = FallenKeyType.STOLEN
		key.holder = player.uniqueId
		key.selfDestructAtMillis = 0L
		player.inventory.addItem(itemFor(key))
		addScore(capturingTeam, 150)
		addScore(oldOwner, -100)
		captureProgress.clear()
		recentCaptureUntil[player.uniqueId] = System.currentTimeMillis() + RECENT_CAPTURE_TELEPORT_BLOCK_MILLIS
		broadcast(Component.text("${player.name} 夺取了 ${oldOwner.displayName} 的密钥 ${key.shortId()}", NamedTextColor.RED))
		save()
	}

	private fun processSelfDestruct() {
		val now = System.currentTimeMillis()
		var changed = false
		for (key in keys.values) {
			if (key.state != FallenKeyState.SELF_DESTRUCTING) continue
			key.holder?.let(Bukkit::getPlayer)?.sendActionBar(
				progressBar("密钥自毁", 1.0 - ((key.selfDestructAtMillis - now).coerceAtLeast(0L).toDouble() / SELF_DESTRUCT_MILLIS), NamedTextColor.RED)
			)
			if (key.selfDestructAtMillis > now) continue
			val holder = key.holder
			holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
			holder?.let(recentCaptureUntil::remove)
			key.state = FallenKeyState.DESTROYED
			addScore(key.ownerTeam, 250)
			addScore(key.originalTeam, -250)
			destroyedKeys[key.ownerTeam] = (destroyedKeys[key.ownerTeam] ?: 0) + 1
			broadcast(Component.text("${key.ownerTeam.displayName} 成功自毁密钥 ${key.shortId()}", NamedTextColor.GOLD))
			changed = true
		}
		if (changed) save()
	}

	private fun processRefreshKeys() {
		if (startedAtMillis == 0L || phase == FallenPhase.IDLE || phase == FallenPhase.DEPLOYMENT || phase == FallenPhase.ENDED) return
		val now = System.currentTimeMillis()
		if (lastRefreshKeyAt == 0L) lastRefreshKeyAt = startedAtMillis
		if (now - lastRefreshKeyAt < REFRESH_KEY_INTERVAL_MILLIS) return
		lastRefreshKeyAt += REFRESH_KEY_INTERVAL_MILLIS
		for (team in FallenTeam.entries) {
			if (team in eliminatedTeams) continue
			val key = FallenKey(UUID.randomUUID(), team, team, FallenKeyState.ITEM, FallenKeyType.REFRESH)
			key.expiresAtMillis = now + REFRESH_KEY_EXPIRY_MILLIS
			keys[key.id] = key
			deliverTeamKey(team, key)
			if (key.holder == null) {
				broadcast(Component.text("${team.displayName} 获得刷新密钥，等待成员上线领取。", team.color))
				continue
			}
			key.holder?.let(Bukkit::getPlayer)?.sendMessage(Component.text("这是阵营刷新密钥，请在 2 小时内放置。", NamedTextColor.GOLD))
			broadcast(Component.text("${team.displayName} 获得了 1 个刷新密钥。", team.color))
		}
		save()
	}

	private fun processRefreshKeyExpiry() {
		val now = System.currentTimeMillis()
		var changed = false
		for (key in keys.values) {
			if (key.type != FallenKeyType.REFRESH || key.state != FallenKeyState.ITEM || key.expiresAtMillis == 0L || key.expiresAtMillis > now) continue
			key.holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
			key.state = FallenKeyState.DESTROYED
			broadcast(Component.text("${key.ownerTeam.displayName} 的刷新密钥 ${key.shortId()} 超时失效。", NamedTextColor.YELLOW))
			changed = true
		}
		if (changed) save()
	}

	private fun processPlacedKeyScore() {
		if (phase != FallenPhase.ACTIVE) return
		val now = System.currentTimeMillis()
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
				addScore(team, placed * 15)
				changed = true
			}
		}
		if (changed) save()
	}

	private fun processEliminations() {
		if (phase != FallenPhase.ACTIVE && phase != FallenPhase.OVERTIME) return
		val now = System.currentTimeMillis()
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
				broadcast(Component.text("${team.displayName} 没有有效密钥，进入 10 分钟濒危状态。", NamedTextColor.YELLOW))
				save()
				now
			}
			if (now - since >= ELIMINATION_GRACE_MILLIS) {
				eliminate(team)
			}
		}
		val aliveTeams = aliveTeams()
		if (aliveTeams.size == 1) {
			endGame("${aliveTeams.single().displayName} 成为唯一存活阵营")
		}
	}

	private fun processCompasses() {
		if (!phase.allowsKeyCapture()) return
		val now = System.currentTimeMillis()
		for (player in Bukkit.getOnlinePlayers()) {
			val playerTeam = teamOf(player) ?: continue
			for (item in player.inventory.contents.filterNotNull()) {
				if (!isFallenCompass(item)) continue
				val meta = item.itemMeta
				val pdc = meta.persistentDataContainer
				val ownerTeam = FallenTeam.parse(pdc.get(compassOwnerTeamKey, PersistentDataType.STRING))
				val targetTeam = FallenTeam.parse(pdc.get(compassTargetTeamKey, PersistentDataType.STRING))
				val expiresAt = pdc.get(compassExpiresAtKey, PersistentDataType.LONG) ?: 0L
				if (ownerTeam != playerTeam || expiresAt <= now) {
					item.amount = 0
					continue
				}
				val nextRefreshAt = pdc.get(compassNextRefreshAtKey, PersistentDataType.LONG) ?: 0L
				if (nextRefreshAt > now) continue
				val targetKeyId = pdc.get(compassTargetKeyIdKey, PersistentDataType.STRING)?.let(UUID::fromString)
				val key = targetKeyId?.let(keys::get)
				if (key == null || key.state != FallenKeyState.PLACED || key.ownerTeam != targetTeam) {
					item.amount = 0
					CommandMessages.warning(player, "指南针锁定的密钥已失效，指南针自毁。")
					continue
				}
				val center = key.center() ?: continue
				player.compassTarget = center
				pdc.set(compassNextRefreshAtKey, PersistentDataType.LONG, now + COMPASS_REFRESH_INTERVAL_MILLIS)
				val distance = player.location.distance(center)
				if (distance < 20.0) {
					revealPrecisely(playerTeam, targetTeam, key, center)
				}
				item.itemMeta = meta
			}
		}
	}

	private fun processPreciseReveals() {
		val now = System.currentTimeMillis()
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
		val now = System.currentTimeMillis()
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
		val now = System.currentTimeMillis()
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
				team != null && team != key.ownerTeam && team !in eliminatedTeams && player.world == center.world && player.location.distance(center) <= KEY_ALERT_RADIUS
			} ?: continue
			keyAlertNotifyUntil[keyId] = now + KEY_ALERT_NOTIFY_COOLDOWN_MILLIS
			alertTeam(key.ownerTeam, Component.text("密钥警戒: ${enemy.name} 接近密钥 ${key.shortId()} 30 格范围。", NamedTextColor.YELLOW))
		}
	}

	private fun processStations() {
		val now = System.currentTimeMillis()
		val seenUse = HashSet<String>()
		val seenDisrupt = HashSet<String>()
		val seenRepair = HashSet<String>()
		for (station in fixedStations) {
			if (station.center() == null) continue
			if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) continue
			for (player in Bukkit.getOnlinePlayers()) {
				val team = teamOf(player) ?: continue
				if (team in eliminatedTeams || player.gameMode == GameMode.SPECTATOR || player.isDead) continue
				if (station.center()?.world != player.world) continue
				if (station.team != team && station.center()?.distance(player.location)?.let { it <= 30.0 } == true) {
					alertStationEnemy(station, team, now)
				}
				if (team == station.team) {
					if (isStationDisrupted(station, now) && station.containsCore(player.location)) {
						val progressKey = "${station.id}:${player.uniqueId}"
						seenRepair.add(progressKey)
						if (!isCombatTagged(player, now)) {
							val seconds = (stationRepairProgress[progressKey] ?: 0L) + 1L
							stationRepairProgress[progressKey] = seconds
							player.sendActionBar(progressBar("修复传送站", seconds.toDouble() / STATION_REPAIR_SECONDS, NamedTextColor.GREEN))
							if (seconds >= STATION_REPAIR_SECONDS) {
								stationDisruptedUntil.remove(station.id)
								stationRepairProgress.remove(progressKey)
								alertTeam(station.team, Component.text("传送站 ${station.id} 已修复。", NamedTextColor.GREEN))
								save()
							}
						}
					} else if (!isStationDisrupted(station, now) && station.contains(player.location)) {
						val progressKey = "${station.id}:${player.uniqueId}"
						seenUse.add(progressKey)
						val denyReason = stationDenyReason(player, station, now)
						if (denyReason == null) {
							val seconds = (stationUseProgress[progressKey] ?: 0L) + 1L
							stationUseProgress[progressKey] = seconds
							player.sendActionBar(progressBar("传送准备", seconds.toDouble() / STATION_USE_SECONDS, station.team.color))
							if (seconds >= STATION_USE_SECONDS) {
								stationUseProgress.remove(progressKey)
								teleportByStation(player, station, now)
							}
						} else {
							stationUseProgress.remove(progressKey)
							notifyStationDenied(player, station, denyReason, now)
						}
					}
				} else if (station.containsCore(player.location) && !isStationDisrupted(station, now)) {
					val progressKey = "${station.id}:${player.uniqueId}"
					seenDisrupt.add(progressKey)
					if (!isCombatTagged(player, now)) {
						val seconds = (stationDisruptProgress[progressKey] ?: 0L) + 1L
						stationDisruptProgress[progressKey] = seconds
						player.sendActionBar(progressBar("干扰传送站", seconds.toDouble() / STATION_DISRUPT_SECONDS, NamedTextColor.RED))
						if (seconds == 1L) {
							alertTeam(station.team, Component.text("${team.displayName} 的 ${player.name} 正在干扰传送站 ${station.id}。", NamedTextColor.YELLOW))
						}
						if (seconds >= STATION_DISRUPT_SECONDS) {
							stationDisruptedUntil[station.id] = now + STATION_DISRUPT_MILLIS
							stationDisruptProgress.remove(progressKey)
							alertTeam(station.team, Component.text("传送站 ${station.id} 已被干扰 10 分钟。", NamedTextColor.RED))
							save()
						}
					}
				}
			}
		}
		stationUseProgress.keys.removeIf { it !in seenUse }
		stationDisruptProgress.keys.removeIf { it !in seenDisrupt }
		stationRepairProgress.keys.removeIf { it !in seenRepair }
	}

	private fun renderStations() {
		val now = System.currentTimeMillis()
		for (station in fixedStations) {
			renderStation(station, now)
		}
	}

	private fun renderStation(station: FallenStation, now: Long) {
		val center = station.center() ?: return
		val world = center.world ?: return
		val particle = if (isStationDisrupted(station, now)) Particle.ANGRY_VILLAGER else Particle.HAPPY_VILLAGER
		world.spawnParticle(particle, center, 8, 1.8, 1.0, 1.8, 0.0)
		renderStationOutline(station)
		renderStationFeather(world, center)
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
		for (step in 0..(FALLEN_STATION_WIDTH * 2)) {
			val xx = station.x + step / 2.0
			spawnBlueDust(world, xx, minY, minZ, dust)
			spawnBlueDust(world, xx, minY, maxZ, dust)
			spawnBlueDust(world, xx, maxY, minZ, dust)
			spawnBlueDust(world, xx, maxY, maxZ, dust)
		}
		for (step in 0..(FALLEN_STATION_DEPTH * 2)) {
			val zz = station.z + step / 2.0
			spawnBlueDust(world, minX, minY, zz, dust)
			spawnBlueDust(world, maxX, minY, zz, dust)
			spawnBlueDust(world, minX, maxY, zz, dust)
			spawnBlueDust(world, maxX, maxY, zz, dust)
		}
		for (step in 0..(FALLEN_STATION_HEIGHT * 2)) {
			val yy = station.y + step / 2.0 + 0.1
			spawnBlueDust(world, minX, yy, minZ, dust)
			spawnBlueDust(world, maxX, yy, minZ, dust)
			spawnBlueDust(world, minX, yy, maxZ, dust)
			spawnBlueDust(world, maxX, yy, maxZ, dust)
		}
	}

	private fun renderStationFeather(world: org.bukkit.World, center: Location) {
		val dust = Particle.DustOptions(Color.fromRGB(155, 215, 255), 1.35f)
		val points = listOf(
			0 to 4, 0 to 3, 0 to 2, 0 to 1, 0 to 0, 0 to -1, 0 to -2, 0 to -3,
			1 to 3, 2 to 3, 1 to 2, 2 to 2, 3 to 2, 1 to 1, 2 to 1,
			-1 to 2, -2 to 2, -1 to 1, -2 to 1, -3 to 1, -1 to 0, -2 to 0,
			1 to -1, 2 to -1, -1 to -2, -2 to -2
		)
		for (zOffset in listOf(-0.08, 0.08)) {
			for ((x, y) in points) {
				spawnDust(world, center.x + x * 0.22, center.y + y * 0.22, center.z + zOffset, dust)
			}
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
		val capturedUntil = recentCaptureUntil[player.uniqueId] ?: 0L
		if (capturedUntil > now) return "刚夺取密钥后的 10 分钟内不能使用传送站。"
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

	private fun isCombatTagged(player: Player, now: Long = System.currentTimeMillis()): Boolean {
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

	private fun sameTeamRegion(team: FallenTeam, first: Location, second: Location): Boolean {
		return regionsOf(team).any { it.contains(first) && it.contains(second) }
	}

	private fun isSafeRespawn(team: FallenTeam, location: Location): Boolean {
		if (!isInTeamRegion(team, location) || location.y < 0) return false
		if (nearEnemyPlacedKey(location, team, 30.0)) return false
		val feet = location.block
		val head = location.clone().add(0.0, 1.0, 0.0).block
		val below = location.clone().subtract(0.0, 1.0, 0.0).block
		if (!feet.isEmpty || !head.isEmpty) return false
		if (feet.type == Material.LAVA || head.type == Material.LAVA || below.type == Material.LAVA) return false
		if (below.isEmpty || below.isLiquid) return false
		return true
	}

	private fun isNearOwnRegionCenter(team: FallenTeam, location: Location, radius: Double): Boolean {
		return regionsOf(team).any { region ->
			region.center()?.let { center -> center.world == location.world && center.distance(location) <= radius } == true
		}
	}

	private fun shouldDropKeysOnQuit(player: Player): Boolean {
		val now = System.currentTimeMillis()
		if (isCombatTagged(player, now)) return true
		if ((recentCaptureUntil[player.uniqueId] ?: 0L) > now) return true
		return player.inventory.contents.filterNotNull()
			.mapNotNull { keyId(it)?.let(keys::get) }
			.any { it.state == FallenKeyState.SELF_DESTRUCTING }
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
		val now = System.currentTimeMillis()
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
		val now = System.currentTimeMillis()
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
		val current = scores[team] ?: 0
		if (current < cost) {
			CommandMessages.warning(player, "阵营积分不足，需要 $cost 分，当前 $current 分。")
			return false
		}
		addScore(team, -cost)
		return true
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
		pdc.set(compassExpiresAtKey, PersistentDataType.LONG, System.currentTimeMillis() + COMPASS_DURATION_MILLIS)
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
		for (key in keys.values) {
			if (key.ownerTeam == team && key.state != FallenKeyState.DESTROYED) {
				key.holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
				key.state = FallenKeyState.DESTROYED
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
		broadcast(Component.text("${team.displayName} 已出局。原因: $reason。", NamedTextColor.RED))
		save()
	}

	private fun isEffectiveKeyForSurvival(key: FallenKey): Boolean {
		if (key.state != FallenKeyState.PLACED && key.state != FallenKeyState.ITEM && key.state != FallenKeyState.SELF_DESTRUCTING) return false
		if (key.type == FallenKeyType.REFRESH && remainingMillis() <= 10 * 60 * 1000L) return false
		return true
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
		return player.inventory.contents.filterNotNull().any { keyId(it) != null }
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

	private fun markKeyDropped(keyId: UUID, location: Location) {
		keys[keyId]?.let {
			if (it.state != FallenKeyState.SELF_DESTRUCTING) {
				it.state = FallenKeyState.ITEM
			}
			it.holder = null
			it.worldName = location.world?.name
			it.x = location.blockX
			it.y = location.blockY
			it.z = location.blockZ
		}
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
		config.getConfigurationSection("scores")?.let { section ->
			FallenTeam.entries.forEach { scores[it] = section.getInt(it.name, 0) }
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
				if (until > System.currentTimeMillis()) keyJammedUntil[UUID.fromString(id)] = until
			}
		}
		config.getConfigurationSection("key-alert-until")?.let { section ->
			for (id in section.getKeys(false)) {
				val until = section.getLong(id)
				if (until > System.currentTimeMillis()) keyAlertUntil[UUID.fromString(id)] = until
			}
		}
		config.getConfigurationSection("team-respawn-boost-until")?.let { section ->
			for (teamName in section.getKeys(false)) {
				val until = section.getLong(teamName)
				if (until > System.currentTimeMillis()) teamRespawnBoostUntil[FallenTeam.parse(teamName)] = until
			}
		}
		config.getConfigurationSection("team-beacons")?.let { section ->
			for (teamName in section.getKeys(false)) {
				val beaconSection = section.getConfigurationSection(teamName) ?: continue
				val expiresAt = beaconSection.getLong("expires-at", 0L)
				if (expiresAt <= System.currentTimeMillis()) continue
				val world = Bukkit.getWorld(beaconSection.getString("world") ?: continue) ?: continue
				teamBeacons[FallenTeam.parse(teamName)] = TeamBeacon(
					Location(
						world,
						beaconSection.getDouble("x"),
						beaconSection.getDouble("y"),
						beaconSection.getDouble("z"),
						beaconSection.getDouble("yaw").toFloat(),
						beaconSection.getDouble("pitch").toFloat()
					),
					expiresAt
				)
			}
		}
		config.getConfigurationSection("players")?.let { section ->
			for (uuid in section.getKeys(false)) {
				playerTeams[UUID.fromString(uuid)] = FallenTeam.parse(section.getString(uuid))
			}
		}
		config.getConfigurationSection("deaths")?.let { section ->
			for (uuid in section.getKeys(false)) {
				deathCounts[UUID.fromString(uuid)] = section.getInt(uuid)
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
				if (until <= System.currentTimeMillis()) continue
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
		val config = YamlConfiguration()
		config["phase"] = phase.name
		config["started-at"] = startedAtMillis
		config["ended-at"] = endedAtMillis
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
		for ((playerId, deaths) in deathCounts) config["deaths.$playerId"] = deaths
		config["eliminated"] = eliminatedTeams.map { it.name }
		config["announced"] = announcedMilestones.toList()
		for ((team, since) in dangerSince) config["danger-since.${team.name}"] = since
		for ((team, beacon) in teamBeacons) {
			if (beacon.expiresAtMillis <= System.currentTimeMillis()) continue
			config["team-beacons.${team.name}.world"] = beacon.location.world?.name
			config["team-beacons.${team.name}.x"] = beacon.location.x
			config["team-beacons.${team.name}.y"] = beacon.location.y
			config["team-beacons.${team.name}.z"] = beacon.location.z
			config["team-beacons.${team.name}.yaw"] = beacon.location.yaw.toDouble()
			config["team-beacons.${team.name}.pitch"] = beacon.location.pitch.toDouble()
			config["team-beacons.${team.name}.expires-at"] = beacon.expiresAtMillis
		}
		for ((id, reveal) in preciseReveals) {
			config["precise-reveals.$id.requester"] = reveal.requesterTeam.name
			config["precise-reveals.$id.target"] = reveal.targetTeam.name
			config["precise-reveals.$id.key"] = reveal.keyId.toString()
			config["precise-reveals.$id.until"] = reveal.untilMillis
		}
		for (key in keys.values) key.save(config.createSection("keys.${key.id}"))
		try {
			config.save(dataFile)
		} catch (exception: IOException) {
			plugin.logger.warning("Failed to save fallen.yml: ${exception.message}")
		}
	}

	companion object {
		private val EVENT_START_MILLIS: Long = LocalDateTime.of(2026, 8, 1, 14, 0)
			.atZone(ZoneId.of("Asia/Shanghai"))
			.toInstant()
			.toEpochMilli()
		private const val OVERWORLD_NAME = "world"
		private const val INITIAL_KEYS_PER_TEAM = 5
		private const val CAPTURE_SECONDS = 6L
		private const val DROP_CONFIRM_MILLIS = 5_000L
		private const val SELF_DESTRUCT_MILLIS = 10 * 60 * 1000L
		private const val RESPAWN_PROTECTION_MILLIS = 8_000L
		private const val PLACED_KEY_SCORE_INTERVAL_MILLIS = 10 * 60 * 1000L
		private const val ELIMINATION_GRACE_MILLIS = 10 * 60 * 1000L
		private const val DEPLOYMENT_MILLIS = 2 * 60 * 60 * 1000L
		private const val MAX_GAME_MILLIS = 144 * 60 * 60 * 1000L
		private const val OVERTIME_MILLIS = 30 * 60 * 1000L
		private const val REFRESH_KEY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
		private const val REFRESH_KEY_EXPIRY_MILLIS = 2 * 60 * 60 * 1000L
		private const val DAMAGE_SCORE_WINDOW_MILLIS = 30 * 1000L
		private const val DAMAGE_SCORE_CAP_PER_WINDOW = 35
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
		private const val TEAM_BEACON_MILLIS = 30 * 60 * 1000L
		private const val ELYTRA_SCORE_SPEED = 25.0
		private const val ELYTRA_SCORE_INTERVAL_MILLIS = 30 * 1000L
		private const val INTERNAL_GAME_MODE_CHANGE_WINDOW_MILLIS = 2 * 1000L
		private const val JAMMED_REVEAL_NOTICE_COOLDOWN_MILLIS = 30 * 1000L
		private const val SCOREBOARD_OBJECTIVE = "fallen_status"
		private const val STATION_USE_SECONDS = 3L
		private const val STATION_DISRUPT_SECONDS = 8L
		private const val STATION_REPAIR_SECONDS = 15L
		private const val STATION_DISRUPT_MILLIS = 10 * 60 * 1000L
		private const val STATION_COOLDOWN_MILLIS = 60 * 1000L
		private const val STATION_PROTECTION_MILLIS = 3 * 1000L
		private const val STATION_ALERT_COOLDOWN_MILLIS = 60 * 1000L
		private const val STATION_DENY_COOLDOWN_MILLIS = 5 * 1000L
		private const val COMBAT_TAG_MILLIS = 10 * 1000L
		private const val RECENT_CAPTURE_TELEPORT_BLOCK_MILLIS = 10 * 60 * 1000L
		private const val PROGRESS_BAR_SEGMENTS = 20
	}

	private data class PreciseReveal(
		val requesterTeam: FallenTeam,
		val targetTeam: FallenTeam,
		val keyId: UUID,
		val untilMillis: Long
	)

	private data class ActiveTrack(val targetId: UUID, val untilMillis: Long)

	private data class TeamBeacon(val location: Location, val expiresAtMillis: Long)

	private data class ElytraSample(val location: Location, val atMillis: Long, val accumulatedMillis: Long)

	private data class DamageScoreWindow(val startedAtMillis: Long, var score: Int)

	private data class AreaDisplay(val title: String, val color: BarColor)

}
