package vip.qoriginal.quantumplugin.chambers

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import vip.qoriginal.quantumplugin.chambers.data.VoidChunkGenerator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID

class ChamberWorldManager(
    private val plugin: ChambersPlugin,
    private val templateWorldName: String,
    private val instancePrefix: String,
) {
    private val worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize()
    private val voidGenerator = VoidChunkGenerator()
    private var loadedTemplateWorld: World? = null

    fun initialize() {
        cleanupStaleInstances()
        loadedTemplateWorld = Bukkit.createWorld(
            WorldCreator(templateWorldName)
                .generator(voidGenerator)
                .generateStructures(false),
        )?.also { it.isAutoSave = true }
            ?: throw IllegalStateException("unable to create or load template world $templateWorldName")
    }

    fun templateWorld(): World = loadedTemplateWorld
        ?: throw IllegalStateException("chamber template world is not initialized")

    fun createInstance(playerId: UUID): World {
        val worldName = instancePrefix + playerId.toString().replace("-", "")
        val destination = safeWorldPath(worldName)
        try {
            deleteDirectory(destination)
        } catch (exception: IOException) {
            throw IllegalStateException("unable to reset chamber instance directory", exception)
        }
        return Bukkit.createWorld(
            WorldCreator(worldName)
                .generator(voidGenerator)
                .generateStructures(false),
        )?.also { it.isAutoSave = false } ?: run {
            tryDelete(destination)
            throw IllegalStateException("unable to load chamber instance $worldName")
        }
    }

    fun destroyInstance(world: World) {
        if (!isInstanceWorldName(world.name)) {
            plugin.logger.severe("Refusing to delete non-instance world ${world.name}")
            return
        }
        val folder = world.worldFolder.toPath()
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.logger.warning("Unable to unload chamber instance ${world.name}")
            return
        }
        tryDelete(folder)
    }

    private fun cleanupStaleInstances() {
        Bukkit.getWorlds()
            .filter { isInstanceWorldName(it.name) }
            .forEach { world ->
                if (world.players.isNotEmpty() || !Bukkit.unloadWorld(world, false)) {
                    throw IllegalStateException("stale chamber world is still in use: ${world.name}")
                }
            }
        try {
            Files.list(worldContainer).use { children ->
                children
                    .filter(Files::isDirectory)
                    .filter { isInstanceWorldName(it.fileName.toString()) }
                    .forEach(::tryDelete)
            }
        } catch (exception: IOException) {
            throw IllegalStateException("unable to inspect stale chamber instances", exception)
        }
    }

    private fun isInstanceWorldName(worldName: String): Boolean {
        if (!worldName.startsWith(instancePrefix)) return false
        return worldName.substring(instancePrefix.length).matches(Regex("[0-9a-f]{32}"))
    }

    private fun safeWorldPath(worldName: String): Path {
        val path = worldContainer.resolve(worldName).normalize()
        require(path.startsWith(worldContainer) && path != worldContainer) {
            "unsafe chamber world path"
        }
        return path
    }

    private fun tryDelete(directory: Path) {
        try {
            deleteDirectory(directory)
        } catch (exception: IOException) {
            plugin.logger.warning(
                "Unable to delete chamber instance $directory: ${exception.message}",
            )
        }
    }

    private fun deleteDirectory(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
