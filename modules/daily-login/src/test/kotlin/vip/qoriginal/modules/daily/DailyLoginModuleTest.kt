package vip.qoriginal.modules.daily

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.persistence.PersistentDataContainer
import vip.qoriginal.quantumplugin.hot.api.ModuleContext
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.function.Consumer
import java.util.logging.Logger
import kotlin.test.*

class DailyLoginModuleTest {
    private val directory = Files.createTempDirectory("qhot-daily")
    @AfterTest fun cleanup() { directory.toFile().deleteRecursively() }

    @Test fun `player day marker survives module replacement and rollback without duplicate announcements`() {
        val player = TestPlayer()
        val first = Context(directory)
        DailyLoginModule().prepare(first)
        first.join(player); first.join(player)
        assertEquals(1, player.messages.size)
        assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), player.data[NamespacedKey("quantumplugin", "daily_login_ad_last_shown")])
        val next = Context(directory)
        DailyLoginModule().prepare(next)
        next.join(player); first.join(player)
        assertEquals(1, player.messages.size)
        player.data.clear(); next.join(player)
        assertEquals(2, player.messages.size)
    }

    @Test fun `live config changes preserve last valid value after malformed JSON or timezone`() {
        val context = Context(directory)
        DailyLoginModule().prepare(context)
        val player = TestPlayer()
        context.file!!.accept("""{"template":"Updated {url}!","url":"https://example.org/event","timezone":"UTC"}""")
        context.join(player)
        assertEquals("Updated https://example.org/event!", plain(player.messages.last()))
        for (invalid in listOf("{broken", """{"timezone":"not-a-zone","template":"bad"}""")) {
            context.file!!.accept(invalid); player.data.clear(); context.join(player)
            assertEquals("Updated https://example.org/event!", plain(player.messages.last()))
        }
    }

    @Test fun `disabled config does not consume day marker and reenable works`() {
        Files.writeString(directory.resolve("daily-login-ad.json"), """{"enabled":false}""")
        val context = Context(directory); DailyLoginModule().prepare(context)
        val player = TestPlayer(); context.join(player)
        assertTrue(player.data.isEmpty()); assertTrue(player.messages.isEmpty())
        context.file!!.accept("""{"enabled":true,"template":"Welcome"}""")
        context.join(player)
        assertEquals("Welcome", plain(player.messages.single()))
    }

    @Test fun `creative namespace stays compatible and chambers installs no announcements`() {
        val context = Context(directory, "QuantumPlugin-Creative")
        DailyLoginModule().prepare(context)
        val player = TestPlayer(); context.join(player)
        assertTrue(player.data.containsKey(NamespacedKey("quantumplugin", "daily_login_ad_last_shown")))
        assertFalse(player.data.containsKey(NamespacedKey("quantumplugin-creative", "daily_login_ad_last_shown")))
        val chambers = Context(directory, "QuantumPlugin-Chambers")
        DailyLoginModule().prepare(chambers)
        assertNull(chambers.handler); assertNull(chambers.file)
    }

    @Test fun `announcement retains clickable URL formatting`() {
        val message = DailyLoginModule.render("Before {url} after", "https://example.org/event")
        assertEquals("Before https://example.org/event after", plain(message))
        assertEquals(ClickEvent.openUrl("https://example.org/event"), message.children().first().clickEvent())
        assertEquals("No placeholder", plain(DailyLoginModule.render("No placeholder", "unused")))
    }

    @Test fun `creative host honors the existing QuantumPlugin daily marker`() {
        val player = TestPlayer()
        val marker = NamespacedKey("quantumplugin", "daily_login_ad_last_shown")
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        player.data[marker] = today
        val context = Context(directory, "QuantumPlugin-Creative")
        DailyLoginModule().prepare(context)
        context.join(player)
        assertTrue(player.messages.isEmpty())
        assertEquals(mapOf(marker to today), player.data)
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    private class Context(private val root: Path, private val name: String = "QuantumPlugin") : ModuleContext {
        var handler: Consumer<PlayerJoinEvent>? = null
        var file: Consumer<String>? = null
        override fun hostName() = name
        override fun serverDirectory() = root
        override fun dataDirectory() = root.resolve("data")
        override fun logger() = Logger.getAnonymousLogger()
        @Suppress("UNCHECKED_CAST")
        override fun <E : Event> listen(type: Class<E>, priority: EventPriority, ignoreCancelled: Boolean, callback: Consumer<E>) {
            assertEquals(PlayerJoinEvent::class.java.name, type.name); assertEquals(EventPriority.MONITOR, priority)
            handler = callback as Consumer<PlayerJoinEvent>
        }
        override fun repeatSync(callback: Runnable, delayTicks: Long, periodTicks: Long) = error("No main-thread polling")
        override fun watchTextFile(path: Path, maxBytes: Int, periodTicks: Long, callback: Consumer<String>) { file = callback }
        fun join(player: TestPlayer) { handler!!.accept(PlayerJoinEvent(player.proxy, Component.empty())) }
    }
    private class TestPlayer {
        val data = mutableMapOf<NamespacedKey, String>()
        val messages = mutableListOf<Component>()
        val pdc = Proxy.newProxyInstance(PersistentDataContainer::class.java.classLoader, arrayOf(PersistentDataContainer::class.java)) { _, method, args ->
            when (method.name) {
                "get" -> data[args!![0]]
                "set" -> { data[args!![0] as NamespacedKey] = args[2] as String; null }
                else -> error("Unexpected PDC access: ${method.name}")
            }
        } as PersistentDataContainer
        val proxy = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPersistentDataContainer" -> pdc
                "sendMessage" -> { messages += args!![0] as Component; null }
                else -> error("Unexpected player access: ${method.name}")
            }
        } as Player
    }
}
