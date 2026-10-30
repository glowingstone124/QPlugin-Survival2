package vip.qoriginal.quantumplugin.hot.runtime

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.function.Consumer
import java.util.logging.Logger
import kotlin.test.*

class ManagedCommandsTest {
    private val sender = Proxy.newProxyInstance(CommandSender::class.java.classLoader, arrayOf(CommandSender::class.java)) { _, _, _ -> null } as CommandSender
    private val command = object : Command("demo") { override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true }

    @Test fun `command and completion references from suspended generations cannot invoke business code`() {
        val platform = Commands()
        val context = ManagedModuleContext(platform, "demo")
        var calls = 0
        context.command("demo", { _, _, _, _ -> calls++; true }, { _, _, _, _ -> listOf("v1") })
        context.prepared()
        assertNull(platform.active)
        context.activate()
        val old = platform.active!!
        assertTrue(old.first.onCommand(sender, command, "demo", emptyArray()))
        assertEquals(listOf("v1"), old.second.onTabComplete(sender, command, "demo", emptyArray()))
        context.suspend()
        assertNull(platform.active)
        old.first.onCommand(sender, command, "demo", emptyArray())
        assertTrue(old.second.onTabComplete(sender, command, "demo", emptyArray())!!.isEmpty())
        assertEquals(1, calls)
        context.activate()
        old.first.onCommand(sender, command, "demo", emptyArray())
        assertEquals(1, calls)
        platform.active!!.first.onCommand(sender, command, "demo", emptyArray())
        assertEquals(2, calls)
        context.suspend()
    }

    @Test fun `partial command attachment failure removes earlier candidate bindings`() {
        val platform = Commands()
        val context = ManagedModuleContext(platform, "demo")
        context.command("demo", { _, _, _, _ -> true }, null)
        context.command("demo", { _, _, _, _ -> true }, null)
        context.prepared()
        assertFailsWith<IllegalStateException> { context.activate() }
        assertNull(platform.active)
    }

    private class Commands : ModulePlatform {
        var active: Pair<CommandExecutor, TabCompleter>? = null
        override fun requireMainThread() = Unit
        override fun hostName() = "QuantumPlugin"
        override fun serverDirectory(): Path = Path.of(".")
        override fun dataDirectory(): Path = Path.of(".")
        override fun logger() = Logger.getAnonymousLogger()
        override fun command(name: String, executor: CommandExecutor, completer: TabCompleter): AutoCloseable {
            check(active == null) { "Duplicate command" }
            active = executor to completer
            return AutoCloseable { active = null }
        }
        override fun <E : Event> listen(type: Class<E>, priority: EventPriority, ignoreCancelled: Boolean, callback: Consumer<E>): AutoCloseable = error("unused")
        override fun repeatSync(callback: Runnable, delayTicks: Long, periodTicks: Long): AutoCloseable = error("unused")
        override fun watchTextFile(file: Path, maxBytes: Int, periodTicks: Long, callback: Consumer<String>): AutoCloseable = error("unused")
    }
}
