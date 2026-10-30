package vip.qoriginal.modules.sponsors

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerJoinEvent
import vip.qoriginal.quantumplugin.hot.api.ModuleContext
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import java.util.logging.Logger
import kotlin.test.*

class SponsorPrefixModuleTest {
    @Test fun `business module preserves sponsor and account roles without owning a timer`() {
        val directory = Files.createTempDirectory("qhot-prefix")
        try {
            Files.writeString(directory.resolve("sponsors.txt"), "Sponsor\r\n")
            val context = Context(directory)
            SponsorPrefixModule().prepare(context)
            assertEquals("§b[SPONSOR]§fSponsor", context.join("Sponsor"))
            assertEquals("§a[ADMIN]§fglowingstone124", context.join("glowingstone124"))
            assertEquals("§a[Master]§fMineCreeper2086", context.join("MineCreeper2086"))
            assertEquals("Player", context.join("Player"))
            context.fileCallback!!.accept("Player\n")
            assertEquals("§b[SPONSOR]§fPlayer", context.join("Player"))
            assertEquals("Sponsor", context.join("Sponsor"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `chambers host keeps its existing behavior without player prefixes`() {
        val directory = Files.createTempDirectory("qhot-prefix")
        try {
            val context = Context(directory, "QuantumPlugin-Chambers")
            SponsorPrefixModule().prepare(context)
            assertNull(context.joinCallback)
            assertNull(context.fileCallback)
        } finally { directory.toFile().deleteRecursively() }
    }

    private class Context(private val directory: Path, private val host: String = "QuantumPlugin") : ModuleContext {
        var joinCallback: Consumer<PlayerJoinEvent>? = null
        var fileCallback: Consumer<String>? = null
        override fun hostName() = host
        override fun serverDirectory() = directory
        override fun dataDirectory() = directory.resolve("data")
        override fun logger() = Logger.getAnonymousLogger()
        @Suppress("UNCHECKED_CAST")
        override fun <E : Event> listen(type: Class<E>, priority: EventPriority, ignoreCancelled: Boolean, handler: Consumer<E>) {
            assertEquals(PlayerJoinEvent::class.java.name, type.name)
            joinCallback = handler as Consumer<PlayerJoinEvent>
        }
        override fun repeatSync(callback: Runnable, delayTicks: Long, periodTicks: Long) = error("Module must not poll on the main thread")
        override fun watchTextFile(file: Path, maxBytes: Int, periodTicks: Long, callback: Consumer<String>) { fileCallback = callback }
        fun join(name: String): String? {
            var display: String? = null
            val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                when (method.name) {
                    "getName" -> name
                    "displayName" -> { display = (args!![0] as TextComponent).content(); null }
                    else -> error("Unexpected player access: ${method.name}")
                }
            } as Player
            joinCallback!!.accept(PlayerJoinEvent(player, Component.empty()))
            return display
        }
    }
}
