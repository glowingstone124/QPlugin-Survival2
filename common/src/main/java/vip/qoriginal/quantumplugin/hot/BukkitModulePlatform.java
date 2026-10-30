package vip.qoriginal.quantumplugin.hot;

import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import vip.qoriginal.quantumplugin.hot.runtime.ModulePlatform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BukkitModulePlatform implements ModulePlatform {
    private final JavaPlugin plugin;
    private final Path root;
    private final String hostName;
    BukkitModulePlatform(JavaPlugin plugin, Path root, String hostName) {
        this.plugin = plugin; this.root = root; this.hostName = hostName;
    }
    @Override public void requireMainThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Hot module callback requires the server thread"); }
    @Override public String hostName() { return hostName; }
    @Override public Path serverDirectory() { return Path.of("").toAbsolutePath(); }
    @Override public Path dataDirectory() { return root.resolve("data"); }
    @Override public Logger logger() { return plugin.getLogger(); }
    boolean executeMain(Runnable callback) {
        if (!plugin.isEnabled()) return false;
        try { Bukkit.getScheduler().runTask(plugin, callback); return true; }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { return false; }
    }
    @Override public <E extends Event> AutoCloseable listen(Class<E> type, EventPriority priority, boolean ignoreCancelled, Consumer<E> callback) {
        requireMainThread();
        Listener owner = new Listener() { };
        Bukkit.getPluginManager().registerEvent(type, owner, priority, (listener, event) -> {
            if (type.isInstance(event)) callback.accept(type.cast(event));
        }, plugin, ignoreCancelled);
        return () -> HandlerList.unregisterAll(owner);
    }
    @Override public AutoCloseable repeatSync(Runnable callback, long delayTicks, long periodTicks) {
        requireMainThread();
        var task = Bukkit.getScheduler().runTaskTimer(plugin, callback, delayTicks, periodTicks);
        return task::cancel;
    }
    @Override public AutoCloseable watchTextFile(Path file, int maxBytes, long periodTicks, Consumer<String> callback) {
        requireMainThread();
        AtomicBoolean closed = new AtomicBoolean();
        var task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (closed.get()) return;
            try {
                String text;
                try (var input = Files.newInputStream(file)) {
                    byte[] bytes = input.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) throw new IOException("Watched file exceeds module limit");
                    text = new String(bytes, StandardCharsets.UTF_8);
                } catch (NoSuchFileException ignored) { text = ""; }
                String snapshot = text;
                if (!closed.get()) executeMain(() -> { if (!closed.get()) callback.accept(snapshot); });
            } catch (IOException error) { logger().log(Level.WARNING, "Could not refresh hot-module text file: " + file.getFileName(), error); }
        }, 1L, periodTicks);
        return () -> { closed.set(true); task.cancel(); };
    }
}
