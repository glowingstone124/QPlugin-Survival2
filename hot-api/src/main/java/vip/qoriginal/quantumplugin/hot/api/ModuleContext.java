package vip.qoriginal.quantumplugin.hot.api;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Registrations are staged during prepare, then owned and revoked by the permanent host. */
public interface ModuleContext {
    /** Release host identity; may differ from Bukkit's plugin name (the creative host retains QuantumPlugin). */
    String hostName();
    Path serverDirectory();
    Path dataDirectory();
    Logger logger();
    /** Synchronous Bukkit events only; asynchronous events need a dedicated host adapter. */
    <E extends Event> void listen(Class<E> eventType, EventPriority priority, boolean ignoreCancelled, Consumer<E> handler);
    void repeatSync(Runnable callback, long delayTicks, long periodTicks);
    /** Bind an existing plugin.yml command. Owned by the host and revoked together with events/tasks. */
    default void command(String name, CommandExecutor executor, TabCompleter completer) {
        throw new UnsupportedOperationException("Managed commands require hot API 2");
    }
    /** File I/O runs in the host; only the bounded text callback runs in module code on the main thread. */
    void watchTextFile(Path file, int maxBytes, long periodTicks, Consumer<String> callback);
}
