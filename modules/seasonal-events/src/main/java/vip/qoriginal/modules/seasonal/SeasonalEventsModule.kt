package vip.qoriginal.modules.seasonal

import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import vip.qoriginal.quantumplugin.hot.api.HotModule
import vip.qoriginal.quantumplugin.hot.api.ModuleContext
import java.nio.file.Files

class SeasonalEventsModule : HotModule {
    private val runtime = SeasonalRuntime()
    override fun prepare(context: ModuleContext) {
        if (context.hostName() != "QuantumPlugin") return
        val file = context.serverDirectory().resolve("newyear.txt")
        if (Files.isRegularFile(file)) Files.newInputStream(file).use {
            val bytes = it.readNBytes(65537)
            require(bytes.size <= 65536) { "newyear.txt exceeds 64 KiB" }
            runtime.reload(String(bytes, Charsets.UTF_8))
        }
        context.watchTextFile(file, 65536, 100) { runtime.reload(it) }
        val snowballs = BuffSnowball(runtime)
        val tnt = FriendlyTnt()
        val fireworks = Firework(runtime)
        context.command("firework", fireworks) { _, _, _, args ->
            val choices = when {
                args.size == 1 -> listOf("get", "launch")
                args.size == 2 && args[0].equals("get", true) -> listOf("1", "2", "3", "4")
                else -> emptyList()
            }
            choices.filter { it.startsWith(args.lastOrNull().orEmpty(), true) }
        }
        context.command("newyeartnt", tnt) { _, _, _, _ -> emptyList() }
        context.command("newyeardumplings", snowballs) { _, _, _, _ -> emptyList() }
        context.listen(BlockPlaceEvent::class.java, EventPriority.NORMAL, false, tnt::onBlockPlace)
        context.listen(EntityExplodeEvent::class.java, EventPriority.NORMAL, false, tnt::onTntExplode)
        context.listen(EntityDamageEvent::class.java, EventPriority.NORMAL, false) {
            tnt.onEntityDamage(it); snowballs.onEntityDamage(it)
        }
        context.listen(ProjectileLaunchEvent::class.java, EventPriority.NORMAL, false, snowballs::onProjectileLaunch)
        context.listen(ProjectileHitEvent::class.java, EventPriority.NORMAL, false, snowballs::onProjectileHit)
        context.repeatSync(runtime::tick, 1, 1)
    }
    override fun close() { runtime.clear() }
}
