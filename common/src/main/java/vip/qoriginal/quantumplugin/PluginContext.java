package vip.qoriginal.quantumplugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class PluginContext {
    private static JavaPlugin plugin;

    private PluginContext() {
    }

    public static void setPlugin(JavaPlugin plugin) {
        PluginContext.plugin = plugin;
    }

    public static JavaPlugin getPlugin() {
        if (plugin == null) {
            throw new IllegalStateException("PluginContext is not initialized.");
        }
        return plugin;
    }
}
