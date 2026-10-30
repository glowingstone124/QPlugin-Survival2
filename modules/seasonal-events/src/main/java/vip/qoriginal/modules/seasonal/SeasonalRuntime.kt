package vip.qoriginal.modules.seasonal

import org.bukkit.Particle
import org.bukkit.entity.Firework
import org.bukkit.entity.Snowball

/** One host-owned task replaces the old never-cancelled per-projectile timers. */
class SeasonalRuntime {
    var greetings: List<String> = listOf("新年快乐！")
        private set
    private val trails = linkedSetOf<Snowball>()
    private val detonations = linkedSetOf<Firework>()
    fun reload(text: String) {
        greetings = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList().ifEmpty { listOf("新年快乐！") }
    }
    fun track(snowball: Snowball) { trails.add(snowball) }
    fun detonateNextTick(firework: Firework) { detonations.add(firework) }
    fun tick() {
        val pending = detonations.toList()
        detonations.clear()
        pending.filter { !it.isDead }.forEach(Firework::detonate)
        val iterator = trails.iterator()
        while (iterator.hasNext()) {
            val projectile = iterator.next()
            if (projectile.isDead || projectile.isOnGround || !projectile.isValid) iterator.remove()
            else projectile.world.spawnParticle(Particle.FIREWORK, projectile.location, 5, .2, .2, .2, .01)
        }
    }
    fun clear() { trails.clear(); detonations.clear() }
}
