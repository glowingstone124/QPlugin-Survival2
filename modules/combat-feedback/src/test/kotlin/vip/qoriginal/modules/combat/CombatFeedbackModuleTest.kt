package vip.qoriginal.modules.combat

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.hot.api.ModuleContext
import java.lang.reflect.Proxy
import kotlin.test.*

class CombatFeedbackModuleTest {
    private val command = object : Command("damageindicator") { override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true }
    @Test fun `preference survives replacing module instances and completion belongs to the module`() {
        val tags = mutableSetOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getScoreboardTags" -> tags
                "addScoreboardTag" -> tags.add(args!![0] as String)
                "removeScoreboardTag" -> tags.remove(args!![0] as String)
                "sendMessage" -> null
                else -> error("Unexpected player call ${method.name}")
            }
        } as Player
        var handler: CommandExecutor? = null
        var completer: TabCompleter? = null
        var events = 0
        val context = Proxy.newProxyInstance(ModuleContext::class.java.classLoader, arrayOf(ModuleContext::class.java)) { _, method, args ->
            when (method.name) {
                "hostName" -> "QuantumPlugin"
                "listen" -> { events++; null }
                "command" -> { assertEquals("damageindicator", args!![0]); handler=args[1] as CommandExecutor; completer=args[2] as TabCompleter; null }
                else -> error("Unexpected context call ${method.name}")
            }
        } as ModuleContext
        CombatFeedbackModule().prepare(context)
        handler!!.onCommand(player, command, "damageindicator", arrayOf("enable"))
        assertTrue("di" in tags)
        CombatFeedbackModule().prepare(context)
        assertTrue("di" in tags)
        assertEquals(listOf("disable"), completer!!.onTabComplete(player, command, "damageindicator", arrayOf("d")))
        handler!!.onCommand(player, command, "damageindicator", arrayOf("disable"))
        assertFalse("di" in tags)
        assertEquals(4, events)
    }

    @Test fun `chambers does not acquire combat feedback commands or events`() {
        val context = Proxy.newProxyInstance(ModuleContext::class.java.classLoader, arrayOf(ModuleContext::class.java)) { _, method, _ ->
            check(method.name == "hostName"); "QuantumPlugin-Chambers"
        } as ModuleContext
        CombatFeedbackModule().prepare(context)
    }
}
