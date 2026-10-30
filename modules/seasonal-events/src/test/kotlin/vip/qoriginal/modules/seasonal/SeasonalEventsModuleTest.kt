package vip.qoriginal.modules.seasonal

import org.bukkit.command.CommandExecutor
import vip.qoriginal.quantumplugin.hot.api.ModuleContext
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class SeasonalEventsModuleTest {
    @Test fun `prepare stages all commands events and a single shared tick task without requiring a greetings file`() {
        val root = Files.createTempDirectory("seasonal-test")
        val commands = linkedMapOf<String, CommandExecutor>()
        var tasks = 0
        var events = 0
        val context = Proxy.newProxyInstance(ModuleContext::class.java.classLoader, arrayOf(ModuleContext::class.java)) { _, method, args ->
            when (method.name) {
                "hostName" -> "QuantumPlugin"
                "serverDirectory" -> root
                "watchTextFile" -> null
                "command" -> { commands[args!![0] as String] = args[1] as CommandExecutor; null }
                "listen" -> { events++; null }
                "repeatSync" -> { tasks++; null }
                else -> error("Unexpected call ${method.name}")
            }
        } as ModuleContext
        try {
            SeasonalEventsModule().use { it.prepare(context) }
            assertEquals(setOf("firework", "newyeartnt", "newyeardumplings"), commands.keys)
            assertEquals(5, events)
            assertEquals(1, tasks)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `activity window is preserved and evaluated against an explicit instant`() {
        assertTrue(EventTiming.isEventActive(Events.NEWYEAR_2026, now=Instant.parse("2026-01-01T00:00:00Z")))
        assertFalse(EventTiming.isEventActive(Events.NEWYEAR_2026, now=Instant.parse("2026-10-30T00:00:00Z")))
    }
    @Test fun `empty greeting reload keeps a usable fallback`() {
        val runtime = SeasonalRuntime()
        runtime.reload("one\r\ntwo\n")
        assertEquals(listOf("one", "two"), runtime.greetings)
        runtime.reload("\r\n")
        assertEquals(listOf("新年快乐！"), runtime.greetings)
    }
    @Test fun `dead projectile references are removed and closing drops queued visual work`() {
        val runtime = SeasonalRuntime()
        var inspections = 0
        var detonations = 0
        val snowball = Proxy.newProxyInstance(org.bukkit.entity.Snowball::class.java.classLoader, arrayOf(org.bukkit.entity.Snowball::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "isDead" -> { inspections++; true }
                else -> error("Unexpected projectile call ${method.name}")
            }
        } as org.bukkit.entity.Snowball
        val firework = Proxy.newProxyInstance(org.bukkit.entity.Firework::class.java.classLoader, arrayOf(org.bukkit.entity.Firework::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "isDead" -> false
                "detonate" -> { detonations++; null }
                else -> error("Unexpected firework call ${method.name}")
            }
        } as org.bukkit.entity.Firework
        runtime.track(snowball); runtime.detonateNextTick(firework)
        runtime.tick(); runtime.tick()
        assertEquals(1, inspections); assertEquals(1, detonations)
        runtime.detonateNextTick(firework); runtime.clear(); runtime.tick()
        assertEquals(1, detonations)
    }
    @Test fun `creative and chambers do not acquire survival activity handlers`() {
        for (host in listOf("QuantumPlugin-Creative", "QuantumPlugin-Chambers")) {
            val context = Proxy.newProxyInstance(ModuleContext::class.java.classLoader, arrayOf(ModuleContext::class.java)) { _, method, _ ->
                check(method.name == "hostName"); host
            } as ModuleContext
            SeasonalEventsModule().use { it.prepare(context) }
        }
    }
}
