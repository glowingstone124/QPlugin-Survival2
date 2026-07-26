package vip.qoriginal.quantumplugin.chambers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import vip.qoriginal.quantumplugin.chambers.data.ChamberDefinition
import vip.qoriginal.quantumplugin.chambers.data.ChamberRegion
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.function.BooleanSupplier

class ChamberScripts private constructor(
    private val scripts: List<MessageScript>,
) {
    fun fireEvent(
        type: TriggerType,
        context: ScriptContext,
        firedScripts: MutableSet<String>,
    ) {
        scripts
            .filter { it.trigger.type == type }
            .forEach { fire(it, context, firedScripts) }
    }

    fun fireRegions(
        playerLocation: Location,
        context: ScriptContext,
        firedScripts: MutableSet<String>,
    ) {
        scripts
            .filter { it.trigger.matchesRegion(playerLocation, context.origin) }
            .forEach { fire(it, context, firedScripts) }
    }

    fun fireInteraction(
        action: Action,
        clickedBlock: Block?,
        context: ScriptContext,
        firedScripts: MutableSet<String>,
    ) {
        if (clickedBlock == null) return
        scripts
            .filter {
                it.trigger.matchesInteraction(action, clickedBlock, context.origin)
            }
            .forEach { fire(it, context, firedScripts) }
    }

    private fun fire(
        script: MessageScript,
        context: ScriptContext,
        firedScripts: MutableSet<String>,
    ) {
        val firedKey = "${context.chamber.id}/${script.id}"
        if (script.trigger.once && !firedScripts.add(firedKey)) return
        script.messages.forEach { line ->
            val delivery = Runnable {
                if (!context.player.isOnline || !context.isValid()) {
                    return@Runnable
                }
                val component = MINI_MESSAGE.deserialize(expand(line.text, context))
                when (line.channel) {
                    MessageChannel.CHAT -> context.player.sendMessage(component)
                    MessageChannel.ACTION_BAR -> context.player.sendActionBar(component)
                    MessageChannel.TITLE -> context.player.showTitle(
                        Title.title(component, Component.empty()),
                    )
                }
            }
            if (line.delayTicks == 0L) {
                delivery.run()
            } else {
                context.plugin.server.scheduler.runTaskLater(
                    context.plugin,
                    delivery,
                    line.delayTicks,
                )
            }
        }
    }

    data class ScriptContext(
        val plugin: ChambersPlugin,
        val player: Player,
        val chamber: ChamberDefinition,
        val origin: Location,
        val completed: Int,
        val total: Int,
        val validity: BooleanSupplier,
    ) {
        fun isValid(): Boolean = validity.asBoolean
    }

    enum class TriggerType {
        ENTER,
        REGION,
        INTERACT_BLOCK,
        COMPLETE,
        FAIL;

        companion object {
            fun parse(value: String): TriggerType = try {
                valueOf(value.trim().replace('-', '_').uppercase(Locale.ROOT))
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("unknown message trigger type: $value")
            }
        }
    }

    private data class MessageScript(
        val id: String,
        val trigger: Trigger,
        val messages: List<MessageLine>,
    )

    private data class MessageLine(
        val delayTicks: Long,
        val channel: MessageChannel,
        val text: String,
    )

    private data class RelativeBlock(
        val x: Int,
        val y: Int,
        val z: Int,
    ) {
        fun matches(block: Block, origin: Location): Boolean =
            block.x == origin.blockX + x &&
                block.y == origin.blockY + y &&
                block.z == origin.blockZ + z
    }

    private data class Trigger(
        val type: TriggerType,
        val once: Boolean,
        val region: ChamberRegion?,
        val action: Action?,
        val material: Material?,
        val position: RelativeBlock?,
    ) {
        fun matchesRegion(location: Location, origin: Location): Boolean =
            type == TriggerType.REGION &&
                region?.contains(location, origin.world, origin) == true

        fun matchesInteraction(
            actualAction: Action,
            block: Block,
            origin: Location,
        ): Boolean = type == TriggerType.INTERACT_BLOCK &&
            (action == null || action == actualAction) &&
            (material == null || material == block.type) &&
            (position == null || position.matches(block, origin))

        companion object {
            fun load(section: ConfigurationSection, file: Path): Trigger {
                val type = TriggerType.parse(section.getString("type", "")!!)
                val once = section.getBoolean("once", true)
                var region: ChamberRegion? = null
                var action: Action? = null
                var material: Material? = null
                var position: RelativeBlock? = null
                when (type) {
                    TriggerType.REGION -> region = readRegion(section, file)
                    TriggerType.INTERACT_BLOCK -> {
                        section.getString("action")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { actionName ->
                                action = try {
                                    Action.valueOf(
                                        actionName.trim().uppercase(Locale.ROOT),
                                    )
                                } catch (exception: IllegalArgumentException) {
                                    throw IllegalArgumentException(
                                        "$file has an invalid interaction action",
                                    )
                                }
                            }
                        section.getString("material")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { materialName ->
                                material = Material.matchMaterial(materialName)
                                    ?: throw IllegalArgumentException(
                                        "$file has an invalid interaction material",
                                    )
                            }
                        section.getConfigurationSection("position")?.let {
                            position = RelativeBlock(
                                requiredInt(it, "x", file),
                                requiredInt(it, "y", file),
                                requiredInt(it, "z", file),
                            )
                        }
                    }
                    else -> Unit
                }
                return Trigger(type, once, region, action, material, position)
            }

            private fun readRegion(
                section: ConfigurationSection,
                file: Path,
            ): ChamberRegion {
                val min = section.getConfigurationSection("min")
                val max = section.getConfigurationSection("max")
                if (min == null || max == null) {
                    throw IllegalArgumentException(
                        "$file region trigger requires min and max",
                    )
                }
                val firstX = requiredInt(min, "x", file)
                val firstY = requiredInt(min, "y", file)
                val firstZ = requiredInt(min, "z", file)
                val secondX = requiredInt(max, "x", file)
                val secondY = requiredInt(max, "y", file)
                val secondZ = requiredInt(max, "z", file)
                return ChamberRegion(
                    minOf(firstX, secondX),
                    minOf(firstY, secondY),
                    minOf(firstZ, secondZ),
                    maxOf(firstX, secondX),
                    maxOf(firstY, secondY),
                    maxOf(firstZ, secondZ),
                )
            }

            private fun requiredInt(
                section: ConfigurationSection,
                key: String,
                file: Path,
            ): Int {
                require(section.isInt(key)) { "$file is missing integer $key" }
                return section.getInt(key)
            }
        }
    }

    private enum class MessageChannel {
        CHAT,
        ACTION_BAR,
        TITLE;

        companion object {
            fun parse(value: String): MessageChannel = try {
                valueOf(value.trim().replace('-', '_').uppercase(Locale.ROOT))
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("unknown message channel: $value")
            }
        }
    }

    companion object {
        private val MINI_MESSAGE = MiniMessage.miniMessage()
        private const val MAX_SCRIPTS = 128
        private const val MAX_MESSAGES_PER_SCRIPT = 64
        private const val MAX_DELAY_TICKS = 20L * 60L * 10L

        fun load(scriptsDirectory: Path): ChamberScripts {
            if (!Files.exists(scriptsDirectory)) return ChamberScripts(emptyList())
            require(Files.isDirectory(scriptsDirectory)) {
                "$scriptsDirectory must be a directory"
            }
            val scriptFiles = try {
                Files.list(scriptsDirectory).use { files ->
                    files
                        .filter(Files::isRegularFile)
                        .filter {
                            val name = it.fileName.toString().lowercase(Locale.ROOT)
                            name.endsWith(".yml") || name.endsWith(".yaml")
                        }
                        .sorted(compareBy { it.fileName.toString() })
                        .toList()
                }
            } catch (exception: IOException) {
                throw IllegalArgumentException(
                    "unable to read $scriptsDirectory",
                    exception,
                )
            }
            require(scriptFiles.size <= MAX_SCRIPTS) {
                "$scriptsDirectory contains too many message scripts"
            }
            return ChamberScripts(scriptFiles.map(::loadScript))
        }

        private fun loadScript(file: Path): MessageScript {
            val config = YamlConfiguration.loadConfiguration(file.toFile())
            val triggerSection = config.getConfigurationSection("trigger")
                ?: throw IllegalArgumentException("$file is missing trigger")
            val trigger = Trigger.load(triggerSection, file)
            val rawMessages = config.getMapList("messages")
            require(rawMessages.isNotEmpty()) { "$file is missing messages" }
            require(rawMessages.size <= MAX_MESSAGES_PER_SCRIPT) {
                "$file contains too many messages"
            }
            val messages = rawMessages.map { raw ->
                val text = raw["text"]?.toString().orEmpty()
                require(text.isNotBlank()) { "$file contains a blank message" }
                val delay = number(raw["delay-ticks"], 0L)
                require(delay in 0..MAX_DELAY_TICKS) {
                    "$file contains an invalid delay-ticks"
                }
                MessageLine(
                    delayTicks = delay,
                    channel = MessageChannel.parse(
                        raw["channel"]?.toString() ?: "chat",
                    ),
                    text = text,
                )
            }
            val fileName = file.fileName.toString()
            return MessageScript(
                id = fileName.substringBeforeLast('.'),
                trigger = trigger,
                messages = messages,
            )
        }

        private fun expand(text: String, context: ScriptContext): String = text
            .replace("{player}", context.player.name)
            .replace("{chamber}", context.chamber.id)
            .replace("{completed}", context.completed.toString())
            .replace("{total}", context.total.toString())

        private fun number(value: Any?, defaultValue: Long): Long = when (value) {
            null -> defaultValue
            is Number -> value.toLong()
            else -> value.toString().toLongOrNull()
                ?: throw IllegalArgumentException("expected a number, got $value")
        }
    }
}
