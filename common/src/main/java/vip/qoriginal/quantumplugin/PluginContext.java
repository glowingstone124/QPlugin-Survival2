package vip.qoriginal.quantumplugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class PluginContext {
    private static JavaPlugin plugin;

    private PluginContext() {
    }

    public static void setPlugin(JavaPlugin plugin) {
        setPlugin(plugin, plugin.getName());
    }

    public static void setPlugin(JavaPlugin plugin, String hotHostName) {
        PluginContext.plugin = plugin;
        BuilderCommand.register(plugin);
        QModBridge.register(plugin);
        vip.qoriginal.quantumplugin.hot.BukkitHotModules.register(plugin, hotHostName);
    }

    public static JavaPlugin getPlugin() {
        if (plugin == null) {
            throw new IllegalStateException("PluginContext is not initialized.");
        }
        return plugin;
    }
}
