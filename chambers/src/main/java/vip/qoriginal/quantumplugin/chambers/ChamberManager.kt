package vip.qoriginal.quantumplugin.chambers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitTask;
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class ChamberManager implements Listener {
    private final ChambersPlugin plugin;
    private final Map<UUID, ActiveRun> activeRuns = new HashMap<>();
    private ChamberCatalog catalog = new ChamberCatalog(
            List.of(),
            null,
            "chambers_template",
            "qchamber_",
            1,
            new ChamberPosition(0.0, 64.0, 0.0, 0.0f, 0.0f),
            32
    );
    private ChamberWorldManager worldManager;
    private BukkitTask timeoutTask;

    public ChamberManager(ChambersPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        timeoutTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireTimedOutRuns, 20L, 20L);
    }

    public void reload() {
        if (!activeRuns.isEmpty()) {
            throw new IllegalStateException("cannot reload while chamber runs are active");
        }
        ChamberCatalog loaded = ChamberCatalog.load(new File(plugin.getDataFolder(), "chambers.yml"));
        ChamberWorldManager loadedWorldManager = new ChamberWorldManager(
                plugin,
                loaded.templateWorldName(),
                loaded.instanceWorldPrefix()
        );
        loadedWorldManager.initialize();
        catalog = loaded;
        worldManager = loadedWorldManager;
    }

    public boolean isReady() {
        return !catalog.chambers().isEmpty();
    }

    public int chamberCount() {
        return catalog.chambers().size();
    }

    public boolean isRunning(Player player) {
        return activeRuns.containsKey(player.getUniqueId());
    }

    public boolean startPractice(Player player) {
        return startRun(player, null, ignored -> {
        });
    }

    public void enterTemplate(Player player) {
        World template = worldManager.templateWorld();
        Location destination = new Location(template, 0.5, 64.0, 0.5);
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.teleport(destination);
        player.sendMessage(Component.text(
                "已进入测试室模板世界。搭建完成后请将关卡导出为 chamber 文件夹中的 structure.nbt。",
                NamedTextColor.YELLOW
        ));
    }

    public boolean startRegistration(
            Player player,
            MinecraftRegistrationTest.Session registrationSession,
            Consumer<ChamberRunResult> completion
    ) {
        return startRun(player, registrationSession, completion);
    }

    public void cancel(Player player, boolean reportResult) {
        finish(player.getUniqueId(), ChamberRunResult.FinishReason.CANCELLED, reportResult);
    }

    public void shutdown() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
        for (UUID playerId : List.copyOf(activeRuns.keySet())) {
            finish(playerId, ChamberRunResult.FinishReason.PLUGIN_DISABLED, false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        ActiveRun run = activeRuns.get(event.getPlayer().getUniqueId());
        if (run == null) {
            return;
        }
        fireCurrentRegions(event.getPlayer(), run, to);
        if (!run.currentChamber().definition().goal().contains(
                to,
                run.instanceWorld,
                run.currentChamber().origin()
        )) {
            return;
        }
        advance(event.getPlayer(), run);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ActiveRun run = activeRuns.get(event.getPlayer().getUniqueId());
        if (run == null) {
            return;
        }
        PlacedChamber chamber = run.currentChamber();
        chamber.definition().scripts().fireInteraction(
                event.getAction(),
                event.getClickedBlock(),
                scriptContext(event.getPlayer(), run, chamber, run.completedChambers),
                run.firedScripts
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        finish(event.getPlayer().getUniqueId(), ChamberRunResult.FinishReason.QUIT, true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (activeRuns.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("测试期间不能离开实例世界。", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        ActiveRun run = activeRuns.get(event.getPlayer().getUniqueId());
        if (run != null) {
            event.setRespawnLocation(run.currentSpawn());
        }
    }

    @EventHandler
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        ActiveRun run = activeRuns.get(event.getPlayer().getUniqueId());
        if (run == null || event.getPlayer().getWorld().getUID().equals(run.instanceWorld.getUID())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(
                plugin,
                () -> event.getPlayer().teleport(run.currentSpawn())
        );
    }

    private boolean startRun(
            Player player,
            MinecraftRegistrationTest.Session registrationSession,
            Consumer<ChamberRunResult> completion
    ) {
        if (!isReady() || activeRuns.containsKey(player.getUniqueId())) {
            return false;
        }
        World instanceWorld;
        try {
            instanceWorld = worldManager.createInstance(player.getUniqueId());
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Unable to create chamber instance for "
                    + player.getName() + ": " + exception.getMessage());
            return false;
        }
        List<PlacedChamber> placedChambers;
        try {
            placedChambers = placeChambers(instanceWorld, catalog.selectForRun());
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Unable to place chambers for "
                    + player.getName() + ": " + exception.getMessage());
            worldManager.destroyInstance(instanceWorld);
            return false;
        }
        ActiveRun run = new ActiveRun(
                placedChambers,
                catalog.lobby(),
                player.getLocation().clone(),
                instanceWorld,
                registrationSession,
                completion
        );
        activeRuns.put(player.getUniqueId(), run);
        enterCurrentChamber(player, run);
        return true;
    }

    private void advance(Player player, ActiveRun run) {
        fireCurrentEvent(ChamberScripts.TriggerType.COMPLETE, player, run);
        run.completedChambers++;
        if (run.completedChambers >= run.chambers.size()) {
            finish(player.getUniqueId(), ChamberRunResult.FinishReason.PASSED, true);
            return;
        }
        run.chamberIndex++;
        enterCurrentChamber(player, run);
    }

    private void enterCurrentChamber(Player player, ActiveRun run) {
        ChamberDefinition chamber = run.currentChamber().definition();
        run.deadlineMillis = System.currentTimeMillis() + chamber.timeLimitSeconds() * 1_000L;
        player.teleport(run.currentSpawn());
        fireCurrentEvent(ChamberScripts.TriggerType.ENTER, player, run);
        player.showTitle(Title.title(
                Component.text(chamber.title(), NamedTextColor.AQUA),
                Component.text(chamber.objective(), NamedTextColor.GRAY)
        ));
        player.sendMessage(Component.text(
                "进入 " + chamber.title() + "（" + (run.chamberIndex + 1) + " / " + run.chambers.size() + "）",
                NamedTextColor.AQUA
        ));
        player.sendMessage(Component.text(
                "目标：" + chamber.objective() + "（限时 " + chamber.timeLimitSeconds() + " 秒）",
                NamedTextColor.GRAY
        ));
    }

    private void expireTimedOutRuns() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, ActiveRun> entry : List.copyOf(activeRuns.entrySet())) {
            if (entry.getValue().deadlineMillis <= now) {
                finish(entry.getKey(), ChamberRunResult.FinishReason.TIMED_OUT, true);
            }
        }
    }

    private void finish(UUID playerId, ChamberRunResult.FinishReason reason, boolean reportResult) {
        ActiveRun run = activeRuns.get(playerId);
        if (run == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            if (reason != ChamberRunResult.FinishReason.PASSED
                    && reason != ChamberRunResult.FinishReason.PLUGIN_DISABLED) {
                fireCurrentEvent(ChamberScripts.TriggerType.FAIL, player, run);
            }
        }
        activeRuns.remove(playerId);
        if (player != null && player.isOnline()) {
            Location destination = run.lobby == null ? run.returnLocation : run.lobby;
            player.teleport(destination.clone());
            if (reason == ChamberRunResult.FinishReason.PASSED) {
                player.sendMessage(Component.text("全部测试室完成。", NamedTextColor.GREEN));
            } else if (reason != ChamberRunResult.FinishReason.PLUGIN_DISABLED) {
                player.sendMessage(Component.text("测试流程已结束：" + finishMessage(reason), NamedTextColor.RED));
            }
        }
        if (reportResult) {
            run.completion.accept(new ChamberRunResult(
                    run.registrationSession,
                    reason,
                    run.completedChambers,
                    run.chambers.size()
            ));
        }
        worldManager.destroyInstance(run.instanceWorld);
    }

    private List<PlacedChamber> placeChambers(World world, List<ChamberDefinition> definitions) {
        List<PlacedChamber> placed = new ArrayList<>(definitions.size());
        int x = (int) Math.floor(catalog.placementOrigin().x());
        int y = (int) Math.floor(catalog.placementOrigin().y());
        int z = (int) Math.floor(catalog.placementOrigin().z());
        Random random = new Random();
        for (ChamberDefinition definition : definitions) {
            Location origin = new Location(world, x, y, z);
            definition.structure().place(
                    origin,
                    definition.includeEntities(),
                    StructureRotation.NONE,
                    Mirror.NONE,
                    0,
                    1.0f,
                    random
            );
            placed.add(new PlacedChamber(definition, origin));
            x += definition.structure().getSize().getBlockX() + catalog.placementGap();
        }
        return List.copyOf(placed);
    }

    private void fireCurrentEvent(ChamberScripts.TriggerType type, Player player, ActiveRun run) {
        PlacedChamber chamber = run.currentChamber();
        chamber.definition().scripts().fireEvent(
                type,
                scriptContext(
                        player,
                        run,
                        chamber,
                        type == ChamberScripts.TriggerType.COMPLETE
                                ? run.completedChambers + 1
                                : run.completedChambers
                ),
                run.firedScripts
        );
    }

    private void fireCurrentRegions(Player player, ActiveRun run, Location location) {
        PlacedChamber chamber = run.currentChamber();
        chamber.definition().scripts().fireRegions(
                location,
                scriptContext(player, run, chamber, run.completedChambers),
                run.firedScripts
        );
    }

    private ChamberScripts.ScriptContext scriptContext(
            Player player,
            ActiveRun run,
            PlacedChamber chamber,
            int completedChambers
    ) {
        return new ChamberScripts.ScriptContext(
                plugin,
                player,
                chamber.definition(),
                chamber.origin(),
                completedChambers,
                run.chambers.size(),
                () -> activeRuns.get(player.getUniqueId()) == run
                        && run.currentChamber() == chamber
        );
    }

    private static String finishMessage(ChamberRunResult.FinishReason reason) {
        return switch (reason) {
            case TIMED_OUT -> "测试室超时";
            case CANCELLED -> "玩家主动退出";
            case QUIT -> "玩家离开服务器";
            case PLUGIN_DISABLED -> "插件已关闭";
            case PASSED -> "全部完成";
        };
    }

    private static final class ActiveRun {
        private final List<PlacedChamber> chambers;
        private final Location lobby;
        private final Location returnLocation;
        private final World instanceWorld;
        private final MinecraftRegistrationTest.Session registrationSession;
        private final Consumer<ChamberRunResult> completion;
        private final Set<String> firedScripts = new HashSet<>();
        private int chamberIndex;
        private int completedChambers;
        private long deadlineMillis;

        private ActiveRun(
                List<PlacedChamber> chambers,
                Location lobby,
                Location returnLocation,
                World instanceWorld,
                MinecraftRegistrationTest.Session registrationSession,
                Consumer<ChamberRunResult> completion
        ) {
            this.chambers = List.copyOf(chambers);
            this.lobby = lobby == null ? null : lobby.clone();
            this.returnLocation = returnLocation;
            this.instanceWorld = instanceWorld;
            this.registrationSession = registrationSession;
            this.completion = completion;
        }

        private PlacedChamber currentChamber() {
            return chambers.get(chamberIndex);
        }

        private Location currentSpawn() {
            PlacedChamber chamber = currentChamber();
            return chamber.definition().spawn().relativeTo(instanceWorld, chamber.origin());
        }
    }
}
