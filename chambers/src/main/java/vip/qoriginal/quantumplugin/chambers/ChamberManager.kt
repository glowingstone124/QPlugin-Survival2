package vip.qoriginal.quantumplugin.chambers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.scheduler.BukkitTask
import vip.qoriginal.quantumplugin.chambers.data.ChamberDefinition
import vip.qoriginal.quantumplugin.chambers.data.ChamberPosition
import vip.qoriginal.quantumplugin.chambers.data.ChamberRunResult
import vip.qoriginal.quantumplugin.chambers.data.PlacedChamber
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.function.BooleanSupplier
import kotlin.math.floor

class ChamberManager(
    private val plugin: ChambersPlugin,
) : Listener {
    private val activeRuns = mutableMapOf<UUID, ActiveRun>()
    private val progressStore = ChamberProgressStore(plugin.dataFolder.toPath())
    private var catalog = ChamberCatalog(
        chambers = emptyList(),
        lobby = null,
        templateWorldName = "chambers_template",
        instanceWorldPrefix = "qchamber_",
        selectionCount = 1,
        placementOrigin = ChamberPosition(0.0, 64.0, 0.0, 0.0f, 0.0f),
        placementGap = 32,
    )
    private lateinit var worldManager: ChamberWorldManager
    private var timeoutTask: BukkitTask? = null

    fun start() {
        timeoutTask = plugin.server.scheduler.runTaskTimer(
            plugin,
            ::expireTimedOutRuns,
            20L,
            20L,
        )
    }

    fun reload() {
        check(activeRuns.isEmpty()) {
            "cannot reload while chamber runs are active"
        }
        val loaded = ChamberCatalog.load(File(plugin.dataFolder, "chambers.yml"))
        val loadedWorldManager = ChamberWorldManager(
            plugin,
            loaded.templateWorldName,
            loaded.instanceWorldPrefix,
        )
        loadedWorldManager.initialize()
        catalog = loaded
        worldManager = loadedWorldManager
    }

    fun isReady(): Boolean = catalog.chambers.isNotEmpty()

    fun chamberCount(): Int = catalog.chambers.size

    fun isRunning(player: Player): Boolean = player.uniqueId in activeRuns

    fun startPractice(player: Player): Boolean = startRun(player, null) {}

    fun enterTemplate(player: Player) {
        val destination = Location(worldManager.templateWorld(), 0.5, 64.0, 0.5)
        player.gameMode = GameMode.CREATIVE
        player.allowFlight = true
        player.isFlying = true
        player.teleport(destination)
        player.sendMessage(
            Component.text(
                "已进入测试室模板世界。搭建完成后请将关卡导出为 chamber 文件夹中的 structure.nbt。",
                NamedTextColor.YELLOW,
            ),
        )
    }

    fun startRegistration(
        player: Player,
        registrationSession: MinecraftRegistrationTest.Session,
        completion: (ChamberRunResult) -> Unit,
    ): Boolean = startRun(player, registrationSession, completion)

    fun clearProgress(sessionId: String) {
        try {
            progressStore.delete(sessionId)
        } catch (exception: RuntimeException) {
            plugin.logger.warning(
                "Unable to clear completed chamber progress for " +
                    "$sessionId: ${exception.message}",
            )
        }
    }

    fun cancel(player: Player, reportResult: Boolean) {
        finish(
            player.uniqueId,
            ChamberRunResult.FinishReason.CANCELLED,
            reportResult,
        )
    }

    fun shutdown() {
        timeoutTask?.cancel()
        timeoutTask = null
        activeRuns.keys.toList().forEach {
            finish(it, ChamberRunResult.FinishReason.PLUGIN_DISABLED, false)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (
            from.blockX == to.blockX &&
            from.blockY == to.blockY &&
            from.blockZ == to.blockZ &&
            from.world === to.world
        ) {
            return
        }
        val run = activeRuns[event.player.uniqueId] ?: return
        fireCurrentRegions(event.player, run, to)
        val chamber = run.currentChamber()
        if (!chamber.definition.goal.contains(to, run.instanceWorld, chamber.origin)) {
            return
        }
        advance(event.player, run)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val run = activeRuns[event.player.uniqueId] ?: return
        val chamber = run.currentChamber()
        chamber.definition.scripts.fireInteraction(
            event.action,
            event.clickedBlock,
            scriptContext(event.player, run, chamber, run.completedChambers),
            run.firedScripts,
        )
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        finish(event.player.uniqueId, ChamberRunResult.FinishReason.QUIT, false)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        if (event.player.uniqueId in activeRuns) {
            event.isCancelled = true
            event.player.sendMessage(
                Component.text(
                    "测试期间不能离开实例世界。",
                    NamedTextColor.RED,
                ),
            )
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        activeRuns[event.player.uniqueId]?.let {
            event.respawnLocation = it.currentSpawn()
        }
    }

    @EventHandler
    fun onWorldChanged(event: PlayerChangedWorldEvent) {
        val run = activeRuns[event.player.uniqueId] ?: return
        if (event.player.world.uid == run.instanceWorld.uid) return
        plugin.server.scheduler.runTask(
            plugin,
            Runnable { event.player.teleport(run.currentSpawn()) },
        )
    }

    private fun startRun(
        player: Player,
        registrationSession: MinecraftRegistrationTest.Session?,
        completion: (ChamberRunResult) -> Unit,
    ): Boolean {
        if (!isReady() || player.uniqueId in activeRuns) return false
        val progress = if (registrationSession == null) {
            null
        } else {
            try {
                progressStore.loadOrCreate(registrationSession) {
                    catalog.selectForRun().map(ChamberDefinition::id)
                }
            } catch (exception: RuntimeException) {
                plugin.logger.severe(
                    "Unable to load chamber progress for ${player.name}: " +
                        "${exception.message}",
                )
                return false
            }
        }
        val definitions = try {
            progress?.let { catalog.resolveSequence(it.chamberIds) }
                ?: catalog.selectForRun()
        } catch (exception: RuntimeException) {
            plugin.logger.severe(
                "Unable to restore chamber sequence for ${player.name}: " +
                    "${exception.message}",
            )
            return false
        }
        val stateMachine = progress?.let(ChamberRunStateMachine::restore)
        val persistedReason = stateMachine?.terminalReason()
        if (stateMachine != null && persistedReason != null) {
            completion(
                ChamberRunResult(
                    registrationSession = registrationSession,
                    reason = persistedReason,
                    completedChambers = stateMachine.completedChambers,
                    totalChambers = definitions.size,
                ),
            )
            return true
        }
        val instanceWorld = try {
            worldManager.createInstance(player.uniqueId)
        } catch (exception: RuntimeException) {
            plugin.logger.severe(
                "Unable to create chamber instance for " +
                    "${player.name}: ${exception.message}",
            )
            return false
        }
        val placedChambers = try {
            placeChambers(instanceWorld, definitions)
        } catch (exception: RuntimeException) {
            plugin.logger.severe(
                "Unable to place chambers for ${player.name}: ${exception.message}",
            )
            worldManager.destroyInstance(instanceWorld)
            return false
        }
        val run = ActiveRun(
            chambers = placedChambers,
            sourceLobby = catalog.lobby,
            returnLocation = player.location.clone(),
            instanceWorld = instanceWorld,
            registrationSession = registrationSession,
            completion = completion,
            stateMachine = stateMachine,
        )
        activeRuns[player.uniqueId] = run
        if (!transitionAndSave(run, ChamberRunStateMachine::start)) {
            suspendForPersistenceFailure(player, run)
            return false
        }
        enterCurrentChamber(
            player,
            run,
            resumed = progress?.state?.let { it != ChamberRunState.READY } == true,
        )
        return true
    }

    private fun advance(player: Player, run: ActiveRun) {
        fireCurrentEvent(ChamberScripts.TriggerType.COMPLETE, player, run)
        val saved = if (run.stateMachine == null) {
            run.practiceCompletedChambers++
            true
        } else {
            transitionAndSave(run, ChamberRunStateMachine::completeCurrentChamber)
        }
        if (!saved) {
            suspendForPersistenceFailure(player, run)
            return
        }
        if (run.completedChambers >= run.chambers.size) {
            finish(player.uniqueId, ChamberRunResult.FinishReason.PASSED, true)
            return
        }
        enterCurrentChamber(player, run, resumed = false)
    }

    private fun enterCurrentChamber(
        player: Player,
        run: ActiveRun,
        resumed: Boolean,
    ) {
        val chamber = run.currentChamber().definition
        run.deadlineMillis =
            System.currentTimeMillis() + chamber.timeLimitSeconds * 1_000L
        player.teleport(run.currentSpawn())
        fireCurrentEvent(ChamberScripts.TriggerType.ENTER, player, run)
        player.showTitle(
            Title.title(
                Component.text(chamber.title, NamedTextColor.AQUA),
                Component.text(chamber.objective, NamedTextColor.GRAY),
            ),
        )
        player.sendMessage(
            Component.text(
                "进入 ${chamber.title}（${run.chamberIndex + 1} / " +
                    "${run.chambers.size}）",
                NamedTextColor.AQUA,
            ),
        )
        player.sendMessage(
            Component.text(
                "目标：${chamber.objective}（限时 ${chamber.timeLimitSeconds} 秒）",
                NamedTextColor.GRAY,
            ),
        )
        if (resumed) {
            player.sendMessage(
                Component.text(
                    "已恢复已完成的进度；当前测试室将从头开始。",
                    NamedTextColor.YELLOW,
                ),
            )
        }
    }

    private fun expireTimedOutRuns() {
        val now = System.currentTimeMillis()
        activeRuns.entries.toList()
            .filter { it.value.deadlineMillis <= now }
            .forEach {
                finish(
                    it.key,
                    ChamberRunResult.FinishReason.TIMED_OUT,
                    true,
                )
            }
    }

    private fun finish(
        playerId: UUID,
        reason: ChamberRunResult.FinishReason,
        reportResult: Boolean,
    ) {
        val run = activeRuns[playerId] ?: return
        val player = plugin.server.getPlayer(playerId)
        if (!transitionForFinish(run, reason)) {
            activeRuns.remove(playerId)
            if (player?.isOnline == true) {
                player.teleport((run.lobby ?: run.returnLocation).clone())
                player.sendMessage(
                    Component.text(
                        "测试结果无法安全保存，请联系管理员后重新连接。",
                        NamedTextColor.RED,
                    ),
                )
            }
            worldManager.destroyInstance(run.instanceWorld)
            return
        }
        if (
            player?.isOnline == true &&
            reason != ChamberRunResult.FinishReason.PASSED &&
            reason != ChamberRunResult.FinishReason.QUIT &&
            reason != ChamberRunResult.FinishReason.PLUGIN_DISABLED
        ) {
            fireCurrentEvent(ChamberScripts.TriggerType.FAIL, player, run)
        }
        activeRuns.remove(playerId)
        if (player?.isOnline == true) {
            player.teleport((run.lobby ?: run.returnLocation).clone())
            if (reason == ChamberRunResult.FinishReason.PASSED) {
                player.sendMessage(
                    Component.text("全部测试室完成。", NamedTextColor.GREEN),
                )
            } else if (reason != ChamberRunResult.FinishReason.PLUGIN_DISABLED) {
                player.sendMessage(
                    Component.text(
                        "测试流程已结束：${finishMessage(reason)}",
                        NamedTextColor.RED,
                    ),
                )
            }
        }
        if (reportResult) {
            run.completion(
                ChamberRunResult(
                    registrationSession = run.registrationSession,
                    reason = reason,
                    completedChambers = run.completedChambers,
                    totalChambers = run.chambers.size,
                ),
            )
        }
        worldManager.destroyInstance(run.instanceWorld)
    }

    private fun transitionForFinish(
        run: ActiveRun,
        reason: ChamberRunResult.FinishReason,
    ): Boolean = transitionAndSave(run) { machine ->
        when (reason) {
            ChamberRunResult.FinishReason.PASSED -> check(
                machine.state == ChamberRunState.PASSED,
            ) {
                "run reached PASSED finish without completing all chambers"
            }
            ChamberRunResult.FinishReason.TIMED_OUT,
            ChamberRunResult.FinishReason.CANCELLED,
            -> machine.fail(reason)
            ChamberRunResult.FinishReason.QUIT,
            ChamberRunResult.FinishReason.PLUGIN_DISABLED,
            -> machine.pause()
        }
    }

    private fun transitionAndSave(
        run: ActiveRun,
        transition: (ChamberRunStateMachine) -> Unit,
    ): Boolean {
        val machine = run.stateMachine ?: return true
        return try {
            transition(machine)
            progressStore.save(machine.snapshot)
            true
        } catch (exception: RuntimeException) {
            plugin.logger.severe(
                "Unable to save chamber progress for " +
                    "${machine.snapshot.sessionId}: ${exception.message}",
            )
            false
        }
    }

    private fun suspendForPersistenceFailure(player: Player, run: ActiveRun) {
        if (activeRuns.remove(player.uniqueId) !== run) return
        player.teleport((run.lobby ?: run.returnLocation).clone())
        player.sendMessage(
            Component.text(
                "测试进度无法安全保存，本次测试已暂停；请联系管理员后重新连接。",
                NamedTextColor.RED,
            ),
        )
        worldManager.destroyInstance(run.instanceWorld)
    }

    private fun placeChambers(
        world: World,
        definitions: List<ChamberDefinition>,
    ): List<PlacedChamber> {
        val placed = ArrayList<PlacedChamber>(definitions.size)
        var x = floor(catalog.placementOrigin.x).toInt()
        val y = floor(catalog.placementOrigin.y).toInt()
        val z = floor(catalog.placementOrigin.z).toInt()
        val random = Random()
        definitions.forEach { definition ->
            val origin = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
            definition.structure.place(
                origin,
                definition.includeEntities,
                StructureRotation.NONE,
                Mirror.NONE,
                0,
                1.0f,
                random,
            )
            placed.add(PlacedChamber(definition, origin))
            x += definition.structure.size.blockX + catalog.placementGap
        }
        return placed.toList()
    }

    private fun fireCurrentEvent(
        type: ChamberScripts.TriggerType,
        player: Player,
        run: ActiveRun,
    ) {
        val chamber = run.currentChamber()
        chamber.definition.scripts.fireEvent(
            type,
            scriptContext(
                player,
                run,
                chamber,
                if (type == ChamberScripts.TriggerType.COMPLETE) {
                    run.completedChambers + 1
                } else {
                    run.completedChambers
                },
            ),
            run.firedScripts,
        )
    }

    private fun fireCurrentRegions(
        player: Player,
        run: ActiveRun,
        location: Location,
    ) {
        val chamber = run.currentChamber()
        chamber.definition.scripts.fireRegions(
            location,
            scriptContext(player, run, chamber, run.completedChambers),
            run.firedScripts,
        )
    }

    private fun scriptContext(
        player: Player,
        run: ActiveRun,
        chamber: PlacedChamber,
        completedChambers: Int,
    ): ChamberScripts.ScriptContext = ChamberScripts.ScriptContext(
        plugin = plugin,
        player = player,
        chamber = chamber.definition,
        origin = chamber.origin,
        completed = completedChambers,
        total = run.chambers.size,
        validity = BooleanSupplier {
            activeRuns[player.uniqueId] === run &&
                run.currentChamber() === chamber
        },
    )

    private fun finishMessage(reason: ChamberRunResult.FinishReason): String =
        when (reason) {
            ChamberRunResult.FinishReason.TIMED_OUT -> "测试室超时"
            ChamberRunResult.FinishReason.CANCELLED -> "玩家主动退出"
            ChamberRunResult.FinishReason.QUIT -> "玩家离开服务器"
            ChamberRunResult.FinishReason.PLUGIN_DISABLED -> "插件已关闭"
            ChamberRunResult.FinishReason.PASSED -> "全部完成"
        }

    private class ActiveRun(
        chambers: List<PlacedChamber>,
        sourceLobby: Location?,
        val returnLocation: Location,
        val instanceWorld: World,
        val registrationSession: MinecraftRegistrationTest.Session?,
        val completion: (ChamberRunResult) -> Unit,
        val stateMachine: ChamberRunStateMachine?,
    ) {
        val chambers = chambers.toList()
        val lobby = sourceLobby?.clone()
        val firedScripts = mutableSetOf<String>()
        var practiceCompletedChambers = 0
        var deadlineMillis = 0L

        val completedChambers: Int
            get() = stateMachine?.completedChambers ?: practiceCompletedChambers

        val chamberIndex: Int
            get() = completedChambers

        fun currentChamber(): PlacedChamber = chambers[chamberIndex]

        fun currentSpawn(): Location {
            val chamber = currentChamber()
            return chamber.definition.spawn.relativeTo(
                instanceWorld,
                chamber.origin,
            )
        }
    }
}
