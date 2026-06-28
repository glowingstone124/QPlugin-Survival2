package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
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
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class FallenGameService(private val plugin: JavaPlugin) {
	private val keyIdKey = NamespacedKey(plugin, "fallen_key_id")
	private val compassOwnerTeamKey = NamespacedKey(plugin, "fallen_compass_owner_team")
	private val compassTargetTeamKey = NamespacedKey(plugin, "fallen_compass_target_team")
	private val compassTargetKeyIdKey = NamespacedKey(plugin, "fallen_compass_target_key_id")
	private val compassExpiresAtKey = NamespacedKey(plugin, "fallen_compass_expires_at")
	private val dataFile = File(plugin.dataFolder, "fallen.yml")
	private val playerTeams = ConcurrentHashMap<UUID, FallenTeam>()
	private val scores = EnumMap<FallenTeam, Int>(FallenTeam::class.java)
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
	private val flightStates = ConcurrentHashMap<UUID, FlightScoreState>()
	private val preciseRevealCooldowns = ConcurrentHashMap<String, Long>()
	private val dangerSince = EnumMap<FallenTeam, Long>(FallenTeam::class.java)
	private val eliminatedTeams = HashSet<FallenTeam>()
	private val announcedMilestones = HashSet<String>()
	private val scoreboardLines = HashSet<String>()
	// Fill worldName/x/y/z after the two overworld station coordinates are finalized.
	private val fixedStations = listOf(
		FallenStation("a_old_city", FallenTeam.A, null, 0, 0, 0, setOf("a_fu_island")),
		FallenStation("a_fu_island", FallenTeam.A, null, 0, 0, 0, setOf("a_old_city"))
	)
	private var tickTask: BukkitTask? = null
	private var lastPlacedKeyScoreAt = 0L
	private var lastRefreshKeyAt = 0L
	private var startedAtMillis = 0L
	private var endedAtMillis = 0L

	var phase: FallenPhase = FallenPhase.IDLE
		private set

	init {
		FallenTeam.entries.forEach { scores[it] = 0 }
	}

	fun start() {
		load()
		updateScoreboard()
		tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
	}

	fun stop() {
		tickTask?.cancel()
		tickTask = null
		clearScoreboard()
		save()
	}

	fun setPhase(next: FallenPhase) {
		phase = next
		if (next == FallenPhase.DEPLOYMENT && startedAtMillis == 0L) {
			startedAtMillis = System.currentTimeMillis()
			lastPlacedKeyScoreAt = startedAtMillis
		}
		broadcast(Component.text("《陷落》阶段切换为 ${next.name}", NamedTextColor.GOLD))
		save()
	}

	fun startGame() {
		startedAtMillis = System.currentTimeMillis()
		endedAtMillis = 0L
		lastPlacedKeyScoreAt = startedAtMillis
		lastRefreshKeyAt = startedAtMillis
		announcedMilestones.clear()
		dangerSince.clear()
		eliminatedTeams.clear()
		phase = FallenPhase.DEPLOYMENT
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
		save()
	}

	fun elapsedMillis(): Long = if (startedAtMillis == 0L) 0L else (endedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()) - startedAtMillis

	fun remainingMillis(): Long = if (startedAtMillis == 0L || phase == FallenPhase.ENDED) 0L else (startedAtMillis + MAX_GAME_MILLIS - System.currentTimeMillis()).coerceAtLeast(0L)

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

	fun regionSnapshot(): Map<FallenTeam, List<FallenRegion>> = regions.mapValues { it.value.toList() }

	fun keySnapshot(): List<FallenKey> = keys.values.sortedBy { it.id.toString() }

	fun eliminatedSnapshot(): Set<FallenTeam> = eliminatedTeams.toSet()

	fun setScore(team: FallenTeam, amount: Int) {
		scores[team] = amount
		save()
	}

	fun setRegion(team: FallenTeam, region: FallenRegion) {
		regions[team] = mutableListOf(region)
		save()
	}

	fun addRegion(team: FallenTeam, region: FallenRegion) {
		regions.computeIfAbsent(team) { mutableListOf() }.add(region)
		save()
	}

	fun clearRegion(team: FallenTeam) {
		regions.remove(team)
		save()
	}

	fun regionsOf(team: FallenTeam): List<FallenRegion> = regions[team]?.toList().orEmpty()

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
			return true
		}
		val ownerTeam = teamOf(player)
		if (ownerTeam == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (ownerTeam == targetTeam) {
			CommandMessages.warning(player, "不能购买指向己方密钥的指南针。")
			return true
		}
		if (ownerTeam in eliminatedTeams) {
			CommandMessages.error(player, "你的阵营已经出局。")
			return true
		}
		if (activeCompassCount(ownerTeam) >= MAX_COMPASSES_PER_TEAM) {
			CommandMessages.warning(player, "同一阵营最多同时拥有 $MAX_COMPASSES_PER_TEAM 个有效指南针。")
			return true
		}
		val targetKey = randomPlacedKey(targetTeam)
		if (targetKey == null) {
			CommandMessages.warning(player, "${targetTeam.displayName} 当前没有可定位的放置密钥。")
			return true
		}
		val score = scores[ownerTeam] ?: 0
		if (score < COMPASS_COST) {
			CommandMessages.warning(player, "阵营积分不足，需要 $COMPASS_COST 分。")
			return true
		}
		addScore(ownerTeam, -COMPASS_COST)
		player.inventory.addItem(compassItem(ownerTeam, targetTeam, targetKey))
		alertTeam(targetTeam, Component.text("${ownerTeam.displayName} 的 ${player.name} 正在定位你方密钥，距离约 ${distanceBand(player.location.distance(targetKey.center() ?: player.location))}。", NamedTextColor.YELLOW))
		CommandMessages.success(player, "已购买指向 ${targetTeam.displayName} 的密钥指南针。")
		save()
		return true
	}

	fun buyShortScan(player: Player): Boolean {
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (!spendScore(player, team, 300)) return true
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
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
			return true
		}
		if (team in eliminatedTeams) {
			CommandMessages.error(player, "你的阵营已经出局。")
			return true
		}
		when (item.lowercase()) {
			"supply" -> {
				if (!spendScore(player, team, 300)) return true
				player.inventory.addItem(ItemStack(Material.GOLDEN_CARROT, 32), ItemStack(Material.ARROW, 32), ItemStack(Material.FIREWORK_ROCKET, 32))
				CommandMessages.success(player, "已购买阵营补给包。")
			}
			"advanced" -> {
				if (!spendScore(player, team, 800)) return true
				player.inventory.addItem(ItemStack(Material.GOLDEN_APPLE, 4), ItemStack(Material.ENDER_PEARL, 16), ItemStack(Material.FIREWORK_ROCKET, 48))
				CommandMessages.success(player, "已购买高级补给包。")
			}
			"resistance" -> {
				if (!spendScore(player, team, 700)) return true
				player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 90 * 20, 0))
				CommandMessages.success(player, "已获得 90 秒抗性提升 I。")
			}
			"speed" -> {
				if (!spendScore(player, team, 400)) return true
				player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 120 * 20, 1))
				CommandMessages.success(player, "已获得 120 秒速度 II。")
			}
			"nightvision" -> {
				if (!spendScore(player, team, 150)) return true
				player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, 10 * 60 * 20, 0))
				CommandMessages.success(player, "已获得 10 分钟夜视。")
			}
			else -> throw IllegalArgumentException("未知购买项: $item")
		}
		save()
		return true
	}

	fun keyId(item: ItemStack?): UUID? {
		if (item == null || item.type.isAir || !item.hasItemMeta()) return null
		val raw = item.itemMeta.persistentDataContainer.get(keyIdKey, PersistentDataType.STRING) ?: return null
		return UUID.fromString(raw)
	}

	fun isFallenCompass(item: ItemStack?): Boolean {
		if (item == null || item.type != Material.COMPASS || !item.hasItemMeta()) return false
		return item.itemMeta.persistentDataContainer.has(compassTargetTeamKey, PersistentDataType.STRING)
	}

	fun placeKey(player: Player, item: ItemStack, location: Location): Boolean {
		val id = keyId(item) ?: return false
		val key = keys[id]
		if (key == null) {
			CommandMessages.error(player, "这个密钥没有活动记录，无法放置。")
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
		if (!isInTeamRegion(team, min)) {
			CommandMessages.warning(player, "密钥只能放置在己方阵营区域内。")
			return true
		}
		if (!isClearKeyRegion(min)) {
			CommandMessages.warning(player, "密钥需要一个 2x3x2 的空区域。")
			return true
		}
		key.ownerTeam = team
		key.placeAt(min)
		item.amount -= 1
		if (key.originalTeam != team) addScore(team, 500)
		broadcast(Component.text("${player.name} 为 ${team.displayName} 放置了密钥 ${key.shortId()}", team.color))
		save()
		return true
	}

	fun requestSelfDestruct(player: Player, item: ItemStack): Boolean {
		val id = keyId(item) ?: return false
		val key = keys[id]
		if (key == null) {
			CommandMessages.error(player, "这个密钥没有活动记录。")
			return true
		}
		val team = teamOf(player)
		if (team == null) {
			CommandMessages.error(player, "你还没有分配阵营。")
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
			keys[id]?.let {
				it.state = FallenKeyState.ITEM
				it.holder = null
			}
		}
		save()
	}

	fun claimPendingPoolKeys(player: Player) {
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		var claimed = 0
		for (key in keys.values) {
			if (key.ownerTeam != team || key.state != FallenKeyState.ITEM || key.holder != null) continue
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
			dropPlayerKeys(player)
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

	fun recordKill(victim: Player, killer: Player?) {
		if (!phase.allowsKeyCapture()) return
		val victimTeam = teamOf(victim) ?: return
		val killerTeam = killer?.let(::teamOf)
		if (killer != null && killerTeam != null && killerTeam != victimTeam && killerTeam !in eliminatedTeams) {
			addScore(killerTeam, 80)
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

	fun respawnLocation(player: Player): Location? {
		val team = teamOf(player) ?: return null
		if (team in eliminatedTeams) return null
		val bedSpawn = player.respawnLocation
		if (bedSpawn != null && isInTeamRegion(team, bedSpawn) && bedSpawn.y >= 0) {
			return bedSpawn
		}
		return regions[team]?.randomOrNull()?.randomSpawn()
	}

	fun protectRespawn(player: Player) {
		val team = teamOf(player) ?: return
		if (team in eliminatedTeams) return
		respawnProtectionUntil[player.uniqueId] = System.currentTimeMillis() + RESPAWN_PROTECTION_MILLIS
		player.sendMessage(Component.text("你获得了 8 秒复活保护。", NamedTextColor.AQUA))
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
		processTimeline()
		renderPlacedKeys()
		processCaptures()
		processSelfDestruct()
		processRefreshKeys()
		processRefreshKeyExpiry()
		processPlacedKeyScore()
		processFlightScore()
		processCompasses()
		processStations()
		processEliminations()
		updateScoreboard()
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

	private fun uniqueScoreboardLine(line: String, index: Int): String {
		return line + " ".repeat(index + 1)
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
		announceRemaining("game-24h", gameEndsAt - now, 24 * 60 * 60 * 1000L, "活动剩余 24 小时。")
		announceRemaining("game-6h", gameEndsAt - now, 6 * 60 * 60 * 1000L, "活动剩余 6 小时。")
		announceRemaining("game-1h", gameEndsAt - now, 60 * 60 * 1000L, "活动剩余 1 小时。")
		announceRemaining("game-10m", gameEndsAt - now, 10 * 60 * 1000L, "活动剩余 10 分钟。")
		if (now >= gameEndsAt) {
			endGame("最长游戏时间 144 小时已到达")
		}
	}

	private fun announceRemaining(key: String, remaining: Long, threshold: Long, message: String) {
		if (remaining in 1..threshold && announcedMilestones.add(key)) {
			broadcast(Component.text(message, NamedTextColor.YELLOW))
			save()
		}
	}

	private fun renderPlacedKeys() {
		keys.values.asSequence()
			.filter { it.state == FallenKeyState.PLACED }
			.forEach { key ->
				val center = key.center() ?: return@forEach
				val world = center.world ?: return@forEach
				val dust = teamDust(key.ownerTeam)
				renderKeyOutline(world, key, dust)
				renderFloatingKeyShape(world, center.clone().add(0.0, 1.15, 0.0), dust)
				world.spawnParticle(Particle.ELECTRIC_SPARK, center, 4, 0.45, 0.55, 0.45, 0.0)
			}
	}

	private fun renderKeyOutline(world: org.bukkit.World, key: FallenKey, dust: Particle.DustOptions) {
		val minX = key.x.toDouble()
		val maxX = key.x + 2.0
		val minY = key.y.toDouble()
		val maxY = key.y + 3.0
		val minZ = key.z.toDouble()
		val maxZ = key.z + 2.0

		for (x in 0..2) {
			val xx = key.x + x.toDouble()
			spawnDust(world, xx, minY, minZ, dust)
			spawnDust(world, xx, minY, maxZ, dust)
			spawnDust(world, xx, maxY, minZ, dust)
			spawnDust(world, xx, maxY, maxZ, dust)
		}
		for (z in 0..2) {
			val zz = key.z + z.toDouble()
			spawnDust(world, minX, minY, zz, dust)
			spawnDust(world, maxX, minY, zz, dust)
			spawnDust(world, minX, maxY, zz, dust)
			spawnDust(world, maxX, maxY, zz, dust)
		}
		for (y in 0..3) {
			val yy = key.y + y.toDouble()
			spawnDust(world, minX, yy, minZ, dust)
			spawnDust(world, maxX, yy, minZ, dust)
			spawnDust(world, minX, yy, maxZ, dust)
			spawnDust(world, maxX, yy, maxZ, dust)
		}
	}

	private fun renderFloatingKeyShape(world: org.bukkit.World, center: Location, dust: Particle.DustOptions) {
		val points = listOf(
			-0.45 to 0.20, -0.35 to 0.40, -0.15 to 0.50, 0.05 to 0.40,
			0.15 to 0.20, 0.05 to 0.00, -0.15 to -0.10, -0.35 to 0.00,
			-0.05 to 0.20, 0.20 to 0.20, 0.45 to 0.20, 0.70 to 0.20, 0.95 to 0.20,
			0.70 to 0.00, 0.85 to 0.00, 0.95 to -0.15
		)
		for ((x, y) in points) {
			spawnDust(world, center.x + x, center.y + y, center.z, dust)
		}
		world.spawnParticle(Particle.END_ROD, center, 2, 0.25, 0.25, 0.05, 0.0)
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
			if (key.state != FallenKeyState.SELF_DESTRUCTING || key.selfDestructAtMillis > now) continue
			key.holder?.let(Bukkit::getPlayer)?.let { removeKeyItem(it, key.id) }
			key.state = FallenKeyState.DESTROYED
			addScore(key.ownerTeam, 250)
			addScore(key.originalTeam, -250)
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
			val online = Bukkit.getOnlinePlayers().filter { teamOf(it) == team }
			if (online.isEmpty()) {
				broadcast(Component.text("${team.displayName} 获得刷新密钥，等待成员上线领取。", team.color))
				continue
			}
			val target = online.random()
			key.holder = target.uniqueId
			target.inventory.addItem(itemFor(key))
			target.sendMessage(Component.text("你收到了阵营刷新密钥，请在 2 小时内放置。", NamedTextColor.GOLD))
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
		val aliveTeams = FallenTeam.entries.filter { it !in eliminatedTeams }
		if (aliveTeams.size == 1) {
			endGame("${aliveTeams.single().displayName} 成为唯一存活阵营")
		}
	}

	private fun processFlightScore() {
		if (!phase.allowsKeyCapture()) return
		val now = System.currentTimeMillis()
		for (player in Bukkit.getOnlinePlayers()) {
			val team = teamOf(player) ?: continue
			if (team in eliminatedTeams || !player.isGliding || player.gameMode == GameMode.SPECTATOR) continue
			val location = player.location
			val state = flightStates.computeIfAbsent(player.uniqueId) {
				FlightScoreState(location.x, location.y, location.z, now, now)
			}
			val deltaSeconds = ((now - state.lastSampleAtMillis).coerceAtLeast(1L)) / 1000.0
			val dx = location.x - state.lastX
			val dy = location.y - state.lastY
			val dz = location.z - state.lastZ
			val speed = sqrt(dx * dx + dy * dy + dz * dz) / deltaSeconds
			state.lastX = location.x
			state.lastY = location.y
			state.lastZ = location.z
			state.lastSampleAtMillis = now
			if (speed <= FLIGHT_SCORE_MIN_SPEED || now - state.lastScoreAtMillis < FLIGHT_SCORE_INTERVAL_MILLIS) continue
			val baseCenter = regions[team]?.firstNotNullOfOrNull { it.center() }
			if (baseCenter != null && baseCenter.world == location.world && baseCenter.distance(location) <= 100.0) continue
			state.lastScoreAtMillis = now
			addScore(team, 10)
			save()
		}
		flightStates.keys.removeIf { Bukkit.getPlayer(it) == null }
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
				val targetKeyId = pdc.get(compassTargetKeyIdKey, PersistentDataType.STRING)?.let(UUID::fromString)
				var key = targetKeyId?.let(keys::get)
				if (key == null || key.state != FallenKeyState.PLACED || key.ownerTeam != targetTeam) {
					key = randomPlacedKey(targetTeam) ?: continue
					pdc.set(compassTargetKeyIdKey, PersistentDataType.STRING, key.id.toString())
				}
				val center = key.center() ?: continue
				player.compassTarget = center
				val distance = player.location.distance(center)
				if (distance < 20.0) {
					revealPrecisely(playerTeam, targetTeam, key, center)
				}
				item.itemMeta = meta
			}
		}
	}

	private fun processStations() {
		if (phase == FallenPhase.IDLE || phase == FallenPhase.ENDED) return
		val now = System.currentTimeMillis()
		val seenUse = HashSet<String>()
		val seenDisrupt = HashSet<String>()
		val seenRepair = HashSet<String>()
		for (station in fixedStations) {
			if (station.center() == null) continue
			renderStation(station, now)
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

	private fun renderStation(station: FallenStation, now: Long) {
		val center = station.center() ?: return
		val world = center.world ?: return
		val particle = if (isStationDisrupted(station, now)) Particle.ANGRY_VILLAGER else Particle.HAPPY_VILLAGER
		world.spawnParticle(particle, center, 8, 1.8, 1.0, 1.8, 0.0)
		renderStationOutline(station)
	}

	private fun renderStationOutline(station: FallenStation) {
		val center = station.center() ?: return
		val world = center.world ?: return
		val dust = Particle.DustOptions(Color.fromRGB(64, 160, 255), 1.1f)
		val minX = station.x - 2 + 0.5
		val maxX = station.x + 2 + 0.5
		val minY = station.y + 0.1
		val maxY = station.y + 3.0
		val minZ = station.z - 2 + 0.5
		val maxZ = station.z + 2 + 0.5
		for (x in -2..2) {
			spawnBlueDust(world, station.x + x + 0.5, minY, minZ, dust)
			spawnBlueDust(world, station.x + x + 0.5, minY, maxZ, dust)
			spawnBlueDust(world, station.x + x + 0.5, maxY, minZ, dust)
			spawnBlueDust(world, station.x + x + 0.5, maxY, maxZ, dust)
		}
		for (z in -2..2) {
			spawnBlueDust(world, minX, minY, station.z + z + 0.5, dust)
			spawnBlueDust(world, maxX, minY, station.z + z + 0.5, dust)
			spawnBlueDust(world, minX, maxY, station.z + z + 0.5, dust)
			spawnBlueDust(world, maxX, maxY, station.z + z + 0.5, dust)
		}
		for (y in 0..3) {
			val yy = station.y + y + 0.1
			spawnBlueDust(world, minX, yy, minZ, dust)
			spawnBlueDust(world, maxX, yy, minZ, dust)
			spawnBlueDust(world, minX, yy, maxZ, dust)
			spawnBlueDust(world, maxX, yy, maxZ, dust)
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
			FallenTeam.A -> Color.fromRGB(255, 72, 72)
			FallenTeam.B -> Color.fromRGB(72, 144, 255)
			FallenTeam.C -> Color.fromRGB(72, 220, 120)
		}
		return Particle.DustOptions(color, 1.15f)
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
		return regions[team]?.any { it.contains(location) } == true
	}

	private fun nearEnemyPlacedKey(player: Player, team: FallenTeam, radius: Double): Boolean {
		return keys.values.any {
			it.state == FallenKeyState.PLACED
				&& it.ownerTeam != team
				&& it.center()?.let { center -> center.world == player.world && center.distance(player.location) < radius } == true
		}
	}

	private fun revealPrecisely(requesterTeam: FallenTeam, targetTeam: FallenTeam, key: FallenKey, center: Location) {
		val cooldownKey = "${requesterTeam.name}:${key.id}"
		val now = System.currentTimeMillis()
		if ((preciseRevealCooldowns[cooldownKey] ?: 0L) > now) return
		preciseRevealCooldowns[cooldownKey] = now + PRECISE_REVEAL_COOLDOWN_MILLIS
		alertTeam(requesterTeam, Component.text("${targetTeam.displayName} 密钥 ${key.shortId()} 精确坐标: ${center.blockX},${center.blockY},${center.blockZ}", NamedTextColor.GOLD))
		alertTeam(targetTeam, Component.text("高危警报：${requesterTeam.displayName} 已精确揭露你方密钥 ${key.shortId()}。", NamedTextColor.RED))
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

	private fun randomPlacedKey(team: FallenTeam): FallenKey? {
		return keys.values.filter { it.ownerTeam == team && it.state == FallenKeyState.PLACED }.randomOrNull()
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
		val pdc = meta.persistentDataContainer
		pdc.set(compassOwnerTeamKey, PersistentDataType.STRING, ownerTeam.name)
		pdc.set(compassTargetTeamKey, PersistentDataType.STRING, targetTeam.name)
		pdc.set(compassTargetKeyIdKey, PersistentDataType.STRING, targetKey.id.toString())
		pdc.set(compassExpiresAtKey, PersistentDataType.LONG, System.currentTimeMillis() + COMPASS_DURATION_MILLIS)
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

	private fun eliminate(team: FallenTeam) {
		eliminatedTeams.add(team)
		dangerSince.remove(team)
		for (key in keys.values) {
			if (key.ownerTeam == team && key.state != FallenKeyState.DESTROYED) {
				key.state = FallenKeyState.DESTROYED
			}
		}
		for (player in Bukkit.getOnlinePlayers()) {
			if (teamOf(player) == team) {
				player.gameMode = GameMode.SPECTATOR
			}
		}
		broadcast(Component.text("${team.displayName} 已出局。", NamedTextColor.RED))
		save()
	}

	private fun isEffectiveKeyForSurvival(key: FallenKey): Boolean {
		if (key.state != FallenKeyState.PLACED && key.state != FallenKeyState.ITEM && key.state != FallenKeyState.SELF_DESTRUCTING) return false
		if (key.type == FallenKeyType.REFRESH && remainingMillis() <= 10 * 60 * 1000L) return false
		return true
	}

	private fun winnerTeams(): List<FallenTeam> {
		val candidates = FallenTeam.entries.filter { it !in eliminatedTeams }
			.ifEmpty { FallenTeam.entries }
		val maxScore = candidates.maxOfOrNull { scores[it] ?: 0 } ?: return emptyList()
		return candidates.filter { (scores[it] ?: 0) == maxScore }
	}

	private fun hasKeyItem(player: Player): Boolean {
		return player.inventory.contents.filterNotNull().any { keyId(it) != null }
	}

	private fun removeKeyItem(player: Player, targetKeyId: UUID) {
		for (item in player.inventory.contents.filterNotNull()) {
			if (keyId(item) == targetKeyId) {
				item.amount = 0
				return
			}
		}
	}

	private fun isClearKeyRegion(min: Location): Boolean {
		if (min.y < 0 || min.world == null) return false
		for (dx in 0..1) {
			for (dy in 0..2) {
				for (dz in 0..1) {
					if (!min.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block.isEmpty) return false
				}
			}
		}
		return true
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
		for ((team, teamRegions) in regions) {
			for ((index, region) in teamRegions.withIndex()) {
				region.save(config.createSection("regions.${team.name}.$index"))
			}
		}
		for ((id, until) in stationDisruptedUntil) config["station-disrupted-until.$id"] = until
		for ((playerId, team) in playerTeams) config["players.$playerId"] = team.name
		for ((playerId, deaths) in deathCounts) config["deaths.$playerId"] = deaths
		config["eliminated"] = eliminatedTeams.map { it.name }
		config["announced"] = announcedMilestones.toList()
		for ((team, since) in dangerSince) config["danger-since.${team.name}"] = since
		for (key in keys.values) key.save(config.createSection("keys.${key.id}"))
		try {
			config.save(dataFile)
		} catch (exception: IOException) {
			plugin.logger.warning("Failed to save fallen.yml: ${exception.message}")
		}
	}

	companion object {
		private const val CAPTURE_SECONDS = 6L
		private const val DROP_CONFIRM_MILLIS = 5_000L
		private const val SELF_DESTRUCT_MILLIS = 10 * 60 * 1000L
		private const val RESPAWN_PROTECTION_MILLIS = 8_000L
		private const val PLACED_KEY_SCORE_INTERVAL_MILLIS = 10 * 60 * 1000L
		private const val ELIMINATION_GRACE_MILLIS = 10 * 60 * 1000L
		private const val DEPLOYMENT_MILLIS = 2 * 60 * 60 * 1000L
		private const val MAX_GAME_MILLIS = 144 * 60 * 60 * 1000L
		private const val REFRESH_KEY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
		private const val REFRESH_KEY_EXPIRY_MILLIS = 2 * 60 * 60 * 1000L
		private const val DAMAGE_SCORE_WINDOW_MILLIS = 30 * 1000L
		private const val DAMAGE_SCORE_CAP_PER_WINDOW = 35
		private const val ASSIST_WINDOW_MILLIS = 30 * 1000L
		private const val FLIGHT_SCORE_INTERVAL_MILLIS = 30 * 1000L
		private const val FLIGHT_SCORE_MIN_SPEED = 25.0
		private const val COMPASS_COST = 600
		private const val MAX_COMPASSES_PER_TEAM = 3
		private const val COMPASS_DURATION_MILLIS = 20 * 60 * 1000L
		private const val PRECISE_REVEAL_COOLDOWN_MILLIS = 30 * 60 * 1000L
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
	}

	private data class DamageScoreWindow(val startedAtMillis: Long, var score: Int)

	private data class FlightScoreState(
		var lastX: Double,
		var lastY: Double,
		var lastZ: Double,
		var lastSampleAtMillis: Long,
		var lastScoreAtMillis: Long
	)
}
