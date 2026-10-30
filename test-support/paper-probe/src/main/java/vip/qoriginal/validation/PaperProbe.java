package vip.qoriginal.validation;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only console probe. Real Bukkit scheduler, event registry, CraftPlayer/PDC and running host. */
public final class PaperProbe extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private final ArrayDeque<Long> ticks = new ArrayDeque<>();
    private final List<WeakReference<ClassLoader>> loaders = new ArrayList<>();

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("qprobe")).setExecutor((sender, command, label, args) -> {
            try {
                Object result = execute(sender, args);
                if (result != null) sender.sendMessage(gson.toJson(result));
            } catch (Throwable error) {
                sender.sendMessage(gson.toJson(Map.of("error", error.toString())));
                error.printStackTrace();
            }
            return true;
        });
    }
    private Plugin host() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("QuantumPlugin");
        return plugin != null ? plugin : Bukkit.getPluginManager().getPlugin("QuantumPlugin-Creative");
    }
    private Object execute(CommandSender sender, String[] args) throws Exception {
        String action = args.length == 0 ? "resources" : args[0];
        return switch (action) {
            case "resources" -> resources();
            case "track" -> trackLoaders();
            case "gc" -> { System.gc(); yield Map.of("gcRequested", true); }
            case "tick-reset" -> { ticks.clear(); yield Map.of("reset", true); }
            case "ticks" -> distribution(ticks.stream().mapToLong(Long::longValue).toArray(), 1_000_000.0);
            case "spawn" -> spawn(args.length > 1 ? args[1] : "QHotTester");
            case "join" -> join(args.length > 1 ? args[1] : "QHotTester", false, false);
            case "hot-event" -> join(args.length > 1 ? args[1] : "QHotTester", true, args.length > 2 && args[2].equals("clear"));
            case "module-command" -> moduleCommand(args);
            case "module-tab" -> moduleTab(args);
            case "combat-event" -> combatEvent(args[1]);
            case "text-target" -> textTarget(args[1]);
            case "text-count" -> Map.of("texts", Objects.requireNonNull(Bukkit.getWorld("world")).getEntitiesByClass(org.bukkit.entity.TextDisplay.class)
                .stream().map(display -> PlainTextComponentSerializer.plainText().serialize(display.text())).toList());
            case "benchmark" -> { benchmark(sender, args.length > 1 ? Integer.parseInt(args[1]) : 20000, args.length > 2 ? Integer.parseInt(args[2]) : 60); yield null; }
            default -> Map.of("error", "Unknown probe command");
        };
    }
    private Player view(Player real, List<String> messages) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, values) -> {
            if ((method.getName().equals("sendMessage") || method.getName().equals("sendActionBar")) && values != null && values.length > 0) {
                Object value = values[0];
                if (value instanceof Component text) messages.add(PlainTextComponentSerializer.plainText().serialize(text));
                else if (value instanceof String text) messages.add(text);
                return null;
            }
            return method.invoke(real, values);
        });
    }
    private Object moduleCommand(String[] args) {
        Player real = Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));
        List<String> messages = new ArrayList<>();
        var command = Objects.requireNonNull(((JavaPlugin) Objects.requireNonNull(host())).getCommand(args[2]));
        boolean result = command.execute(view(real, messages), args[2], Arrays.copyOfRange(args, 3, args.length));
        return Map.of("result", result, "messages", messages, "indicator", real.getScoreboardTags().contains("di"));
    }
    private Object moduleTab(String[] args) {
        Player player = Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));
        var command = Objects.requireNonNull(((JavaPlugin) Objects.requireNonNull(host())).getCommand(args[2]));
        return Map.of("suggestions", command.tabComplete(player, args[2], Arrays.copyOfRange(args, 3, args.length)));
    }
    private Object combatEvent(String name) throws Exception {
        Player real = Objects.requireNonNull(Bukkit.getPlayerExact(name));
        List<String> messages = new ArrayList<>();
        Player view = view(real, messages);
        var arrow = (org.bukkit.entity.Arrow) Proxy.newProxyInstance(org.bukkit.entity.Arrow.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Arrow.class},
            (proxy, method, values) -> method.getName().equals("getShooter") ? view : null);
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.entity.ProjectileHitEvent(arrow, real));
        return Map.of("messages", messages);
    }
    private Object textTarget(String name) {
        Player player = Objects.requireNonNull(Bukkit.getPlayerExact(name));
        var world = Objects.requireNonNull(Bukkit.getWorld("world"));
        var point = world.getSpawnLocation().add(0.5, 0, 0.5);
        point.setYaw(0); point.setPitch(0);
        player.teleport(point);
        var eye = player.getEyeLocation();
        world.getBlockAt(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ() + 3).setType(org.bukkit.Material.STONE);
        return Map.of("target", true);
    }
    private Object spawn(String name) throws Exception {
        if (Bukkit.getPlayerExact(name) != null) return Map.of("player", name, "existing", true);
        Object manager = field(Objects.requireNonNull(host()), "fakePlayerManager");
        var method = Arrays.stream(manager.getClass().getMethods()).filter(value -> value.getName().equals("spawn")).findFirst().orElseThrow();
        Class<?> skinType = method.getParameterTypes()[2];
        Object properties = skinType.getField("EMPTY").get(null);
        var location = Objects.requireNonNull(Bukkit.getWorld("world")).getSpawnLocation();
        method.invoke(manager, name, location, properties);
        // FakePlayerManager creates a fresh entity; emulate the normal player's Bukkit data-load path.
        Objects.requireNonNull(Bukkit.getPlayerExact(name)).loadData();
        return Map.of("player", name, "online", Bukkit.getPlayerExact(name) != null);
    }
    private Object join(String name, boolean moduleOnly, boolean clearDaily) throws Exception {
        Player real = Objects.requireNonNull(Bukkit.getPlayerExact(name), "Spawn a probe player first");
        Plugin owner = Objects.requireNonNull(host());
        NamespacedKey key = new NamespacedKey(owner, "daily_login_ad_last_shown");
        if (clearDaily) real.getPersistentDataContainer().remove(key);
        List<String> messages = new ArrayList<>();
        Player view = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, values) -> {
            if (method.getName().equals("sendMessage") && values != null && values.length > 0 && values[0] instanceof Component text) {
                messages.add(PlainTextComponentSerializer.plainText().serialize(text)); return null;
            }
            return method.invoke(real, values);
        });
        var event = new PlayerJoinEvent(moduleOnly ? view : real, Component.empty());
        int invoked = 0;
        if (moduleOnly) {
            for (var listener : PlayerJoinEvent.getHandlerList().getRegisteredListeners()) {
                if (listener.getPlugin() == owner && isModuleListener(listener)) { listener.callEvent(event); invoked++; }
            }
        } else Bukkit.getPluginManager().callEvent(event);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("player", name); result.put("display", PlainTextComponentSerializer.plainText().serialize(real.displayName()));
        result.put("moduleHandlers", invoked); result.put("messages", messages);
        result.put("dailyMarker", real.getPersistentDataContainer().getOrDefault(key, PersistentDataType.STRING, ""));
        result.put("guest", real.getScoreboardTags().contains("guest"));
        return result;
    }
    private static boolean isModuleListener(RegisteredListener listener) {
        return listener.getListener().getClass().getName().startsWith("vip.qoriginal.quantumplugin.hot.BukkitModulePlatform$");
    }
    private Map<String, Object> resources() {
        Plugin owner = host();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var classes = ManagementFactory.getClassLoadingMXBean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nanoTime", System.nanoTime()); result.put("processCpuNanos", os.getProcessCpuTime());
        result.put("heapUsed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        var threadBean = ManagementFactory.getThreadMXBean();
        for (long threadId : threadBean.getAllThreadIds()) {
            var info = threadBean.getThreadInfo(threadId);
            if (info != null && info.getThreadName().equals("QPlugin-HotUpdate")) result.put("hotIoCpuNanos", threadBean.getThreadCpuTime(threadId));
        }
        result.put("loadedClasses", classes.getLoadedClassCount()); result.put("unloadedClasses", classes.getUnloadedClassCount());
        result.put("trackedLoaders", loaders.size()); result.put("aliveTrackedLoaders", loaders.stream().filter(reference -> reference.get() != null).count());
        if (owner != null) {
            result.put("hostEnabled", owner.isEnabled());
            result.put("hostTasks", Bukkit.getScheduler().getPendingTasks().stream().filter(task -> task.getOwner() == owner).count());
            result.put("modulePlayerJoinHandlers", Arrays.stream(PlayerJoinEvent.getHandlerList().getRegisteredListeners()).filter(listener -> listener.getPlugin() == owner && isModuleListener(listener)).count());
            result.put("moduleCommandHandlers", Arrays.stream(ServerCommandEvent.getHandlerList().getRegisteredListeners()).filter(listener -> listener.getPlugin() == owner && isModuleListener(listener)).count());
        }
        System.getProperties().forEach((key, value) -> {
            if (key instanceof String name && name.startsWith("qhot.validation.")) result.put(name, value.toString());
        });
        return result;
    }
    private Object trackLoaders() throws Exception {
        for (var listener : PluginDisableEvent.getHandlerList().getRegisteredListeners()) {
            Object instance = listener.getListener();
            if (!instance.getClass().getName().equals("vip.qoriginal.quantumplugin.hot.BukkitHotModules")) continue;
            Object manager = field(instance, "manager"), active = field(manager, "active");
            if (active == null) continue;
            for (Object loaded : (List<?>) field(active, "loaded")) {
                ClassLoader loader = (ClassLoader) field(loaded, "loader");
                if (loaders.stream().noneMatch(reference -> reference.get() == loader)) loaders.add(new WeakReference<>(loader));
            }
        }
        return resources();
    }
    private static Object field(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private void benchmark(CommandSender sender, int calls, int rounds) {
        if (calls < 100 || calls > 100000 || rounds < 5 || rounds > 200) throw new IllegalArgumentException("Invalid benchmark size");
        Plugin owner = Objects.requireNonNull(host());
        var managed = Arrays.stream(ServerCommandEvent.getHandlerList().getRegisteredListeners())
            .filter(listener -> listener.getPlugin() == owner && isModuleListener(listener)).findFirst().orElseThrow();
        AtomicLong counter = (AtomicLong) Objects.requireNonNull(System.getProperties().get("qhot.validation.events"), "Activate validation fixture first");
        var event = new ServerCommandEvent(Bukkit.getConsoleSender(), "__qhot_bench__");
        var direct = new RegisteredListener(new Listener() { }, (listener, incoming) -> {
            if (incoming instanceof ServerCommandEvent command && command.getCommand().equals("__qhot_bench__")) counter.incrementAndGet();
        }, EventPriority.NORMAL, this, false);
        long[] baseline = new long[rounds], guarded = new long[rounds];
        int[] round = { -8 };
        org.bukkit.scheduler.BukkitTask[] task = { null };
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                int index = round[0]++;
                long first, second;
                if ((index & 1) == 0) { first = run(direct, event, calls); second = run(managed, event, calls); }
                else { second = run(managed, event, calls); first = run(direct, event, calls); }
                if (index >= 0) { baseline[index] = first; guarded[index] = second; }
                if (index == rounds - 1) {
                    task[0].cancel();
                    var result = Map.of("callsPerRound", calls, "rounds", rounds, "directNanosPerCall", distribution(baseline, calls),
                        "managedNanosPerCall", distribution(guarded, calls));
                    Files.writeString(getDataFolder().toPath().resolve("benchmark.json"), gson.toJson(result));
                }
            } catch (Throwable error) { task[0].cancel(); getLogger().severe(error.toString()); }
        }, 1, 1);
        getDataFolder().mkdirs();
        try { Files.deleteIfExists(getDataFolder().toPath().resolve("benchmark.json")); } catch (Exception ignored) { }
        sender.sendMessage(gson.toJson(Map.of("benchmark", "started")));
    }
    private static long run(RegisteredListener listener, ServerCommandEvent event, int calls) throws Exception {
        long start = System.nanoTime(); for (int i = 0; i < calls; i++) listener.callEvent(event); return System.nanoTime() - start;
    }
    private static Map<String, Object> distribution(long[] values, double divisor) {
        if (values.length == 0) return Map.of("count", 0);
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return Map.of("count", sorted.length, "mean", Arrays.stream(sorted).average().orElse(0) / divisor,
            "p50", sorted[sorted.length / 2] / divisor, "p95", sorted[Math.min(sorted.length - 1, (int) (sorted.length * .95))] / divisor,
            "p99", sorted[Math.min(sorted.length - 1, (int) (sorted.length * .99))] / divisor, "max", sorted[sorted.length - 1] / divisor);
    }
    @EventHandler public void endTick(ServerTickEndEvent event) {
        if (ticks.size() == 2000) ticks.removeFirst();
        ticks.addLast((long) (event.getTickDuration() * 1_000_000));
    }
}
