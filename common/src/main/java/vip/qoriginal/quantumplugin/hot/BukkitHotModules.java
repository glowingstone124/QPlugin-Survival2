package vip.qoriginal.quantumplugin.hot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import vip.qoriginal.quantumplugin.hot.api.HotModule;
import vip.qoriginal.quantumplugin.hot.runtime.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Permanent host; the existing login, QMod bridge and NMS services remain in the plugin class loader. */
public final class BukkitHotModules implements Listener, AutoCloseable {
    private record SavedState(String active, String previous) { }
    private final JavaPlugin plugin;
    private final Path root;
    private final BukkitModulePlatform platform;
    private final String fingerprint;
    private final String instanceId = UUID.randomUUID().toString();
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "QPlugin-HotUpdate"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean updating = new AtomicBoolean();
    private final HotSwapManager manager;
    private volatile boolean stopped;
    private volatile String phase = "starting";

    public static void register(JavaPlugin plugin) {
        register(plugin, plugin.getName());
    }
    public static void register(JavaPlugin plugin, String hostName) {
        BukkitHotModules host = null;
        try {
            host = new BukkitHotModules(plugin, hostName);
            host.start();
            plugin.getServer().getPluginManager().registerEvents(host, plugin);
        } catch (Exception error) {
            if (host != null) try { host.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            plugin.getLogger().log(Level.SEVERE, "Hot-module host unavailable; core login remains loaded. Restart after fixing the module release.", error);
            var command = plugin.getCommand("qhot");
            if (command != null) command.setExecutor((sender, ignored, label, args) -> {
                sender.sendMessage("热更新宿主启动失败，请查看日志；修复后重启服务器。登录等核心功能继续运行。");
                return true;
            });
            if (!plugin.getName().equals("QuantumPlugin-Chambers")) {
                var fallback = new vip.qoriginal.quantumplugin.NamePrefix();
                vip.qoriginal.quantumplugin.DailyLoginAdvertisement.init(plugin);
                plugin.getServer().getPluginManager().registerEvents(fallback, plugin);
                plugin.getServer().getPluginManager().registerEvents(new Listener() {
                    @EventHandler(priority = EventPriority.MONITOR) public void dailyFallback(PlayerJoinEvent event) {
                        vip.qoriginal.quantumplugin.DailyLoginAdvertisement.showIfFirstLoginToday(event.getPlayer());
                    }
                    @EventHandler public void stopFallback(PluginDisableEvent event) {
                        if (event.getPlugin() == plugin) fallback.close();
                    }
                }, plugin);
            }
        }
    }
    private BukkitHotModules(JavaPlugin plugin, String hostName) throws Exception {
        this.plugin = plugin;
        ReleaseManifest.requireName(hostName);
        root = plugin.getDataFolder().toPath().toAbsolutePath().resolve("hot");
        Files.createDirectories(root.resolve("releases"));
        platform = new BukkitModulePlatform(plugin, root, hostName);
        Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        fingerprint = HostFingerprint.code(jar);
        manager = new HotSwapManager(platform, fingerprint, (active, previous) ->
            AtomicFiles.write(root.resolve("state.json"), ReleaseManifest.JSON.toJson(new SavedState(active, previous)) + "\n"));
    }
    private void start() throws Exception {
        Path builtin = installBuiltin();
        SavedState saved = readState();
        HotSwapManager.PreparedRelease initial;
        String previous = null;
        try {
            initial = manager.prepare(saved == null ? builtin : releasePath(saved.active()));
            previous = saved == null ? null : saved.previous();
        } catch (Exception error) {
            plugin.getLogger().warning("Saved module release is unavailable or incompatible; using the bundled release");
            initial = manager.prepare(builtin);
        }
        var result = manager.restore(initial, previous);
        if (!result.ok()) throw new IOException("Bundled/saved module could not pass activation health check");
        phase = "ready";
        writeStatus();
        registerCommand();
        UpdateMailbox mailbox = new UpdateMailbox(root, instanceId,
            request -> apply(request.releaseId(), request.expectedActive()), io, plugin.getLogger());
        io.scheduleWithFixedDelay(() -> {
            if (stopped) return;
            try { writeStatus(); mailbox.poll(); }
            catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Hot-update mailbox polling failed", error); }
        }, 1, 1, TimeUnit.SECONDS);
        plugin.getLogger().info("Hot-module host ready: " + manager.status().activeRelease());
    }
    private SavedState readState() {
        Path file = root.resolve("state.json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) return null;
            SavedState state = ReleaseManifest.JSON.fromJson(Files.readString(file), SavedState.class);
            ReleaseManifest.requireName(state.active());
            if (state.previous() != null) ReleaseManifest.requireName(state.previous());
            return state;
        } catch (Exception ignored) { return null; }
    }
    private Path releasePath(String id) throws IOException {
        ReleaseManifest.requireName(id);
        Path directory = root.resolve("releases").resolve(id);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Release directory does not exist");
        return directory;
    }
    private CompletableFuture<HotSwapManager.Result> apply(String id, String expected) {
        var outcome = new CompletableFuture<HotSwapManager.Result>();
        if (stopped || !updating.compareAndSet(false, true)) return CompletableFuture.completedFuture(
            new HotSwapManager.Result(false, stopped ? "STOPPED" : "BUSY", id, "Host is stopped or another deployment is in progress"));
        phase = "preparing";
        io.execute(() -> {
            HotSwapManager.PreparedRelease prepared;
            try {
                prepared = manager.prepare(releasePath(id));
            } catch (Throwable error) {
                finishUpdate();
                String code = error instanceof HotSwapManager.RestartRequiredException ? "RESTART_REQUIRED" : "INVALID_RELEASE";
                outcome.complete(new HotSwapManager.Result(false, code, id, "Release validation failed; active module is unchanged"));
                plugin.getLogger().log(Level.WARNING, "Hot update rejected: " + id, error);
                return;
            }
            if (!platform.executeMain(() -> {
                try {
                    var result = manager.activate(prepared, expected);
                    finishUpdate();
                    outcome.complete(result);
                }
                catch (Throwable error) {
                    try { prepared.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                    finishUpdate();
                    outcome.completeExceptionally(error);
                }
            })) {
                try { prepared.close(); } catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Prepared module cleanup failed", error); }
                finishUpdate();
                outcome.complete(new HotSwapManager.Result(false, "STOPPED", id, "Server stopped before activation"));
            }
        });
        return outcome;
    }
    private void finishUpdate() {
        updating.set(false); phase = stopped ? "stopped" : "ready";
        // A receipt must not race the next deployer's expectedActive snapshot by one heartbeat.
        try { writeStatus(); }
        catch (IOException error) { plugin.getLogger().log(Level.WARNING, "Could not publish updated hot-host status", error); }
    }
    private void registerCommand() {
        var command = plugin.getCommand("qhot");
        if (command == null) throw new IllegalStateException("plugin.yml must declare qhot");
        command.setExecutor((sender, ignored, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
                var status = manager.status();
                sender.sendMessage("QPlugin hot: " + status.activeRelease() + " | previous=" + status.previousRelease() +
                    " | " + phase + (status.restartRequired() ? " | RESTART_REQUIRED" : "") + " | " + status.modules());
                return true;
            }
            String release = args.length == 1 && args[0].equalsIgnoreCase("rollback") ? manager.status().previousRelease()
                : args.length == 2 && args[0].equalsIgnoreCase("apply") ? args[1] : null;
            if (release == null) { sender.sendMessage("/qhot status | apply <releaseId> | rollback（需要已有 previous 版本）"); return true; }
            try { ReleaseManifest.requireName(release); }
            catch (IllegalArgumentException error) { sender.sendMessage("无效的 releaseId"); return true; }
            apply(release, manager.status().activeRelease()).whenComplete((result, error) -> platform.executeMain(() ->
                sender.sendMessage(error == null ? result.code() + ": " + result.message() : "模块更新失败，请查看日志")));
            return true;
        });
        command.setTabCompleter((sender, ignored, label, args) -> args.length == 1 ?
            List.of("status", "apply", "rollback").stream().filter(value -> value.startsWith(args[0])).toList() : List.of());
    }
    private Path installBuiltin() throws Exception {
        Path temporary = Files.createTempDirectory(root.resolve("releases"), ".builtin-");
        try {
            List<ReleaseManifest.Artifact> artifacts = new ArrayList<>();
            for (String name : builtinModuleNames()) {
                Path jar = temporary.resolve(name + ".jar");
                try (var input = plugin.getResource("hot-modules/" + name + ".jar")) {
                    if (input == null) throw new IOException("Plugin JAR has no bundled " + name + " module");
                    Files.copy(input, jar);
                }
                ModuleDescriptor descriptor = ModuleDescriptor.read(jar);
                artifacts.add(ReleasePackager.artifact(descriptor.id(), descriptor.version(), jar));
            }
            String modulesHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(ReleaseManifest.JSON.toJson(artifacts).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String id = "builtin-" + fingerprint.substring(0, 12) + "-" + modulesHash.substring(0, 12);
            var manifest = new ReleaseManifest(1, HotModule.API_VERSION, id, Map.of(platform.hostName(), fingerprint), artifacts, List.of());
            AtomicFiles.write(temporary.resolve("manifest.json"), ReleaseManifest.JSON.toJson(manifest) + "\n");
            Path destination = root.resolve("releases").resolve(id);
            if (Files.exists(destination)) {
                if (!ReleaseManifest.read(destination).equals(manifest)) throw new IOException("Bundled release directory was modified");
                for (var artifact : artifacts) ReleaseManifest.verify(destination, artifact);
            } else Files.move(temporary, destination);
            return destination;
        } finally {
            if (Files.exists(temporary)) try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
    private List<String> builtinModuleNames() throws IOException {
        Properties index = new Properties();
        try (var input = plugin.getResource("hot-modules/modules.properties")) {
            if (input == null) throw new IOException("Plugin JAR has no module index");
            index.load(input);
        }
        String[] names = index.getProperty("modules", "").split(",", -1);
        if (names.length < 1 || names.length > 16) throw new IOException("Invalid bundled module count");
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            ReleaseManifest.requireName(name);
            if (!unique.add(name)) throw new IOException("Duplicate bundled module");
        }
        return List.of(names);
    }
    private synchronized void writeStatus() throws IOException {
        var state = manager.status();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("formatVersion", 1); status.put("apiVersion", HotModule.API_VERSION);
        status.put("instanceId", instanceId); status.put("hostName", platform.hostName()); status.put("hostCodeSha256", fingerprint);
        status.put("running", !stopped); status.put("phase", phase); status.put("updatedAt", System.currentTimeMillis());
        status.put("activeRelease", state.activeRelease()); status.put("previousRelease", state.previousRelease());
        status.put("restartRequired", state.restartRequired()); status.put("modules", state.modules());
        AtomicFiles.write(root.resolve("status.json"), ReleaseManifest.JSON.toJson(status) + "\n");
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) { if (event.getPlugin() == plugin) close(); }
    @Override public void close() {
        if (stopped) return;
        platform.requireMainThread(); stopped = true; phase = "stopped";
        manager.close();
        io.shutdownNow();
        try { writeStatus(); } catch (IOException error) { plugin.getLogger().log(Level.WARNING, "Could not write stopped hot-host status", error); }
    }
}
