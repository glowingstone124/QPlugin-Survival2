package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import vip.qoriginal.quantumplugin.PluginContext;
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest;

import java.io.File;

public final class ChambersPlugin extends JavaPlugin {
    private ChamberManager chamberManager;
    private ChambersRegistrationTest registrationTest;

    @Override
    public void onEnable() {
        PluginContext.setPlugin(this);
        saveBundledConfiguration();

        chamberManager = new ChamberManager(this);
        try {
            chamberManager.reload();
        } catch (RuntimeException exception) {
            getLogger().severe("无法加载 chambers.yml: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        chamberManager.start();
        getServer().getPluginManager().registerEvents(chamberManager, this);

        ChambersCommand commandHandler = new ChambersCommand(chamberManager);
        PluginCommand command = getCommand("chambers");
        if (command == null) {
            throw new IllegalStateException("plugin.yml is missing the chambers command");
        }
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        registrationTest = new ChambersRegistrationTest(this, chamberManager);
        getServer().getServicesManager().register(
                MinecraftRegistrationTest.class,
                registrationTest,
                this,
                ServicePriority.Normal
        );
        getServer().getPluginManager().registerEvents(
                new ChambersJoinGate(this, registrationTest),
                this
        );
        getLogger().info("QuantumPlugin chambers target started with "
                + chamberManager.chamberCount() + " configured chambers.");
    }

    @Override
    public void onDisable() {
        if (registrationTest != null) {
            getServer().getServicesManager().unregister(
                    MinecraftRegistrationTest.class,
                    registrationTest
            );
        }
        if (chamberManager != null) {
            chamberManager.shutdown();
        }
    }

    private void saveBundledConfiguration() {
        File file = new File(getDataFolder(), "chambers.yml");
        if (!file.isFile()) {
            saveResource("chambers.yml", false);
        }
    }
}
