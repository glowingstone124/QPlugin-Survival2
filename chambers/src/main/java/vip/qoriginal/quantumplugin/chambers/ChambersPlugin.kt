package vip.qoriginal.quantumplugin.chambers

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import vip.qoriginal.quantumplugin.PluginContext
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest
import java.io.File

class ChambersPlugin : JavaPlugin() {
    private var chamberManager: ChamberManager? = null
    private var registrationTest: ChambersRegistrationTest? = null

    override fun onEnable() {
        PluginContext.setPlugin(this)
        saveBundledConfiguration()

        val manager = ChamberManager(this)
        try {
            manager.reload()
        } catch (exception: RuntimeException) {
            logger.severe("无法加载 chambers.yml: ${exception.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        chamberManager = manager
        manager.start()
        server.pluginManager.registerEvents(manager, this)

        val commandHandler = ChambersCommand(manager)
        val command = getCommand("chambers")
            ?: throw IllegalStateException(
                "plugin.yml is missing the chambers command",
            )
        command.setExecutor(commandHandler)
        command.tabCompleter = commandHandler

        val test = ChambersRegistrationTest(this, manager)
        registrationTest = test
        server.servicesManager.register(
            MinecraftRegistrationTest::class.java,
            test,
            this,
            ServicePriority.Normal,
        )
        server.pluginManager.registerEvents(ChambersJoinGate(this, test), this)
        logger.info(
            "QuantumPlugin chambers target started with " +
                "${manager.chamberCount()} configured chambers.",
        )
    }

    override fun onDisable() {
        registrationTest?.let {
            server.servicesManager.unregister(
                MinecraftRegistrationTest::class.java,
                it,
            )
        }
        chamberManager?.shutdown()
    }

    private fun saveBundledConfiguration() {
        val file = File(dataFolder, "chambers.yml")
        if (!file.isFile) saveResource("chambers.yml", false)
    }
}
