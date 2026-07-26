package vip.qoriginal.quantumplugin.chambers

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import vip.qoriginal.quantumplugin.chambers.data.ChamberDefinition
import vip.qoriginal.quantumplugin.chambers.data.ChamberPosition
import vip.qoriginal.quantumplugin.chambers.data.ChamberRegion
import java.io.File
import java.io.IOException
import java.nio.file.Path

data class ChamberCatalog(
    val chambers: List<ChamberDefinition>,
    val lobby: Location?,
    val templateWorldName: String,
    val instanceWorldPrefix: String,
    val selectionCount: Int,
    val placementOrigin: ChamberPosition,
    val placementGap: Int,
) {
    fun selectForRun(): List<ChamberDefinition> {
        if (chambers.isEmpty()) return emptyList()
        return chambers.shuffled().take(selectionCount.coerceAtMost(chambers.size))
    }

    fun resolveSequence(chamberIds: List<String>): List<ChamberDefinition> {
        val byId = chambers.associateBy(ChamberDefinition::id)
        return chamberIds.map { id ->
            byId[id] ?: throw IllegalStateException(
                "saved progress references missing chamber $id",
            )
        }
    }

    companion object {
        private const val STRUCTURE_FILE_NAME = "structure.nbt"
        private const val GOAL_FILE_NAME = "goal.yml"

        fun load(globalConfigFile: File): ChamberCatalog {
            val config = YamlConfiguration.loadConfiguration(globalConfigFile)
            val templateWorldName =
                config.getString("world.template", "chambers_template")!!.trim()
            val instanceWorldPrefix =
                config.getString("world.instance-prefix", "qchamber_")!!.trim()
            validateWorldNames(templateWorldName, instanceWorldPrefix)

            val selectionCount = config.getInt("selection-count", 1)
            require(selectionCount > 0) { "selection-count must be positive" }
            val placementGap = config.getInt("placement.gap", 32)
            require(placementGap >= 0) { "placement.gap must not be negative" }
            val placementOrigin = readPosition(
                requiredSection(config, "placement.origin", "chambers.yml"),
                "chambers.yml placement.origin",
            )

            val chambersDirectory = File(globalConfigFile.parentFile, "chambers")
            require(chambersDirectory.isDirectory || chambersDirectory.mkdirs()) {
                "unable to create chambers directory $chambersDirectory"
            }
            val chambersRoot = chambersDirectory.toPath().toAbsolutePath().normalize()
            val uniqueIds = mutableSetOf<String>()
            val chambers = config.getStringList("pool").map { id ->
                require(id.matches(Regex("[A-Za-z0-9_.-]+")) && uniqueIds.add(id)) {
                    "pool contains an unsafe or duplicate chamber id: $id"
                }
                val chamberDirectory = chambersRoot.resolve(id).normalize()
                require(
                    chamberDirectory.parent == chambersRoot &&
                        chamberDirectory.toFile().isDirectory,
                ) {
                    "missing chamber directory: $chamberDirectory"
                }
                loadChamber(id, chamberDirectory)
            }

            val lobby = config.getConfigurationSection("lobby")?.let {
                readWorldLocation(it, "chambers.yml lobby")
            }
            return ChamberCatalog(
                chambers = chambers,
                lobby = lobby,
                templateWorldName = templateWorldName,
                instanceWorldPrefix = instanceWorldPrefix,
                selectionCount = selectionCount,
                placementOrigin = placementOrigin,
                placementGap = placementGap,
            )
        }

        private fun loadChamber(id: String, directory: Path): ChamberDefinition {
            val structureFile = directory.resolve(STRUCTURE_FILE_NAME).toFile()
            val goalFile = directory.resolve(GOAL_FILE_NAME).toFile()
            require(structureFile.isFile) { "$id is missing $STRUCTURE_FILE_NAME" }
            require(goalFile.isFile) { "$id is missing $GOAL_FILE_NAME" }

            val structure = try {
                Bukkit.getStructureManager().loadStructure(structureFile)
            } catch (exception: IOException) {
                throw IllegalArgumentException("unable to load structure for $id", exception)
            }

            val goal = YamlConfiguration.loadConfiguration(goalFile)
            val spawn = readPosition(requiredSection(goal, "spawn", id), "$id spawn")
            val goalRegion = readRegion(requiredSection(goal, "goal", id), "$id goal")
            val timeLimitSeconds = goal.getInt("time-limit-seconds")
            require(timeLimitSeconds > 0) { "$id time-limit-seconds must be positive" }
            return ChamberDefinition(
                id = id,
                title = nonBlankText(goal.getString("title"), id),
                objective = nonBlankText(
                    goal.getString("objective"),
                    "抵达测试室出口",
                ),
                structure = structure,
                includeEntities = goal.getBoolean("include-entities", true),
                spawn = spawn,
                goal = goalRegion,
                timeLimitSeconds = timeLimitSeconds,
                scripts = ChamberScripts.load(directory.resolve("scripts")),
            )
        }

        private fun nonBlankText(value: String?, fallback: String): String =
            value?.trim()?.takeIf(String::isNotEmpty) ?: fallback

        private fun validateWorldNames(
            templateWorldName: String,
            instanceWorldPrefix: String,
        ) {
            require(templateWorldName.matches(Regex("[A-Za-z0-9_.-]+"))) {
                "world.template contains unsupported characters"
            }
            require(
                instanceWorldPrefix.matches(Regex("[A-Za-z0-9_.-]+")) &&
                    instanceWorldPrefix.length >= 3,
            ) {
                "world.instance-prefix must contain at least three safe characters"
            }
            require(!templateWorldName.startsWith(instanceWorldPrefix)) {
                "world.template must not start with world.instance-prefix"
            }
        }

        private fun requiredSection(
            parent: ConfigurationSection,
            path: String,
            source: String,
        ): ConfigurationSection = parent.getConfigurationSection(path)
            ?: throw IllegalArgumentException("$source is missing $path")

        private fun readWorldLocation(
            section: ConfigurationSection,
            path: String,
        ): Location {
            val world = section.getString("world")?.let(Bukkit::getWorld)
                ?: throw IllegalArgumentException("$path references an unloaded world")
            return readPosition(section, path).inWorld(world)
        }

        private fun readPosition(
            section: ConfigurationSection,
            path: String,
        ): ChamberPosition {
            requireNumber(section, "x", path)
            requireNumber(section, "y", path)
            requireNumber(section, "z", path)
            return ChamberPosition(
                x = section.getDouble("x"),
                y = section.getDouble("y"),
                z = section.getDouble("z"),
                yaw = section.getDouble("yaw", 0.0).toFloat(),
                pitch = section.getDouble("pitch", 0.0).toFloat(),
            )
        }

        private fun readRegion(
            section: ConfigurationSection,
            path: String,
        ): ChamberRegion {
            val min = requiredSection(section, "min", path)
            val max = requiredSection(section, "max", path)
            val firstX = readBlockCoordinate(min, "x", "$path.min")
            val firstY = readBlockCoordinate(min, "y", "$path.min")
            val firstZ = readBlockCoordinate(min, "z", "$path.min")
            val secondX = readBlockCoordinate(max, "x", "$path.max")
            val secondY = readBlockCoordinate(max, "y", "$path.max")
            val secondZ = readBlockCoordinate(max, "z", "$path.max")
            return ChamberRegion(
                minX = minOf(firstX, secondX),
                minY = minOf(firstY, secondY),
                minZ = minOf(firstZ, secondZ),
                maxX = maxOf(firstX, secondX),
                maxY = maxOf(firstY, secondY),
                maxZ = maxOf(firstZ, secondZ),
            )
        }

        private fun readBlockCoordinate(
            section: ConfigurationSection,
            key: String,
            path: String,
        ): Int {
            requireNumber(section, key, path)
            return section.getInt(key)
        }

        private fun requireNumber(
            section: ConfigurationSection,
            key: String,
            path: String,
        ) {
            require(section.isInt(key) || section.isDouble(key)) {
                "$path is missing numeric $key"
            }
        }
    }
}
