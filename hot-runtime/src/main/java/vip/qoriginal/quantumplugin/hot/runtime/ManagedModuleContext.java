package vip.qoriginal.quantumplugin.hot.runtime;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import vip.qoriginal.quantumplugin.hot.api.ModuleContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class ManagedModuleContext implements ModuleContext {
    private interface Binding { AutoCloseable attach(long epoch) throws Exception; }
    private final ModulePlatform platform;
    private final String moduleId;
    private final List<Binding> bindings = new ArrayList<>();
    private final List<AutoCloseable> attached = new ArrayList<>();
    private boolean prepared;
    private boolean active;
    private long epoch;

    ManagedModuleContext(ModulePlatform platform, String moduleId) { this.platform = platform; this.moduleId = moduleId; }
    @Override public String hostName() { return platform.hostName(); }
    @Override public Path serverDirectory() { return platform.serverDirectory(); }
    @Override public Path dataDirectory() { return platform.dataDirectory().resolve(moduleId); }
    @Override public Logger logger() { return platform.logger(); }
    private void staging() { if (prepared) throw new IllegalStateException("Register callbacks only during prepare()"); }
    void prepared() { prepared = true; }
    private boolean current(long token) { platform.requireMainThread(); return active && epoch == token; }

    @Override public <E extends Event> void listen(Class<E> type, EventPriority priority, boolean ignoreCancelled, Consumer<E> handler) {
        staging();
        bindings.add(token -> platform.listen(type, priority, ignoreCancelled, event -> { if (current(token)) handler.accept(event); }));
    }
    @Override public void repeatSync(Runnable callback, long delayTicks, long periodTicks) {
        staging();
        if (delayTicks < 0 || periodTicks < 1) throw new IllegalArgumentException("Invalid tick interval");
        bindings.add(token -> platform.repeatSync(() -> { if (current(token)) callback.run(); }, delayTicks, periodTicks));
    }
    @Override public void command(String name, CommandExecutor executor, TabCompleter completer) {
        staging();
        if (name == null || !name.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid command name");
        java.util.Objects.requireNonNull(executor);
        bindings.add(token -> platform.command(name,
            (sender, command, label, args) -> !current(token) || executor.onCommand(sender, command, label, args),
            (sender, command, alias, args) -> current(token) && completer != null
                ? completer.onTabComplete(sender, command, alias, args) : List.of()));
    }
    @Override public void watchTextFile(Path file, int maxBytes, long periodTicks, Consumer<String> callback) {
        staging();
        if (maxBytes < 1 || maxBytes > 1_048_576 || periodTicks < 20) throw new IllegalArgumentException("Invalid file watch limits");
        bindings.add(token -> platform.watchTextFile(file, maxBytes, periodTicks, text -> { if (current(token)) callback.accept(text); }));
    }
    void activate() throws Exception {
        platform.requireMainThread();
        if (active) throw new IllegalStateException("Module is already active");
        active = true;
        long token = ++epoch;
        try { for (Binding binding : bindings) attached.add(binding.attach(token)); }
        catch (Exception | Error error) { try { suspend(); } catch (Exception | Error cleanup) { error.addSuppressed(cleanup); } throw error; }
    }
    void suspend() throws Exception {
        if (active || !attached.isEmpty()) platform.requireMainThread();
        active = false;
        ++epoch;
        Throwable failure = null;
        for (int i = attached.size() - 1; i >= 0; i--) {
            try { attached.get(i).close(); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        attached.clear();
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception error) throw error;
    }
}
