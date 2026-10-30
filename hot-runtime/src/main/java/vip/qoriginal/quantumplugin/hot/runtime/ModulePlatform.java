package vip.qoriginal.quantumplugin.hot.runtime;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Platform adapters never invoke business callbacks outside the main thread. */
public interface ModulePlatform {
    void requireMainThread();
    String hostName();
    Path serverDirectory();
    Path dataDirectory();
    Logger logger();
    <E extends Event> AutoCloseable listen(Class<E> type, EventPriority priority, boolean ignoreCancelled, Consumer<E> callback);
    AutoCloseable repeatSync(Runnable callback, long delayTicks, long periodTicks);
    default AutoCloseable command(String name, CommandExecutor executor, TabCompleter completer) throws Exception {
        throw new UnsupportedOperationException("Platform does not support managed commands");
    }
    AutoCloseable watchTextFile(Path file, int maxBytes, long periodTicks, Consumer<String> callback);
}
