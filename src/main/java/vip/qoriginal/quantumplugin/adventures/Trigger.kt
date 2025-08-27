package vip.qoriginal.quantumplugin.adventures

import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityDeathEvent
import vip.qoriginal.quantumplugin.ClassScanner
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

class Trigger : Listener {
	val mainWorld: World? = Bukkit.getWorlds().firstOrNull { it.name == "world" } ?: Bukkit.getWorlds().firstOrNull()
	companion object {
		private val triggerMap: MutableMap<TriggerType, MutableList<Pair<Any, java.lang.reflect.Method>>> = mutableMapOf()
	}
	@EventHandler
	fun onPlayerEnchant(event: EnchantItemEvent) = runBlocking{
		//帕秋莉岛
		if (event.enchanter.isInZone(Location(mainWorld,-2421.0, -64.0, 320.0), Location(mainWorld,-2399.0, 32.0, 342.0))) {
			call(TriggerType.PATCHOULI, event.enchanter)
		}
	}
	@EventHandler
	fun onZombieDeath(event: EntityDeathEvent) = runBlocking{
		val killer = event.entity.killer ?: return@runBlocking

		if (event.entity.type != EntityType.ZOMBIE) return@runBlocking

		val weapon = killer.inventory.itemInMainHand
		if (weapon.type != Material.IRON_SWORD) return@runBlocking
		call(TriggerType.KOISHI, killer)
	}

	private fun scanTriggers(): List<Pair<Any, java.lang.reflect.Method>> {
		val classes = ClassScanner.scanPackage("vip.qoriginal.quantumplugin")
		val result = mutableListOf<Pair<Any, java.lang.reflect.Method>>()

		for (clazz in classes) {
			for (method in clazz.declaredMethods) {
				if (method.isAnnotationPresent(SubscribeTrigger::class.java)) {
					val instance = clazz.getDeclaredConstructor().newInstance()
					result.add(instance to method)
					println("[Trigger] 发现触发器: ${clazz.name}.${method.name}")
				}
			}
		}
		return result
	}

	fun scan(packageName: String) {
		val classes = ClassScanner.scanPackage(packageName)

		for (clazz in classes) {
			for (method in clazz.declaredMethods) {
				val anno = method.getAnnotation(SubscribeTrigger::class.java) ?: continue
				val type = anno.value

				val instance = clazz.getDeclaredConstructor().newInstance()
				val noVisitor: NoVisitor? = method.getAnnotation(NoVisitor::class.java)
				val finalMethod = if (noVisitor != null) {
					val wrapperName = "${method.name}_safe"
					clazz.methods.find { it.name == wrapperName } ?: method
				} else {
					method
				}
				triggerMap.computeIfAbsent(type) { mutableListOf() }
					.add(instance to finalMethod)

				println("[TriggerManager] 注册触发器: ${clazz.name}.${finalMethod.name} -> $type")
			}
		}
	}

	suspend fun call(type: TriggerType, vararg args: Any?) {
		val methods = triggerMap[type] ?: return

		for ((instance, method) in methods) {
			try {
				val kfun = method.kotlinFunction
				if (kfun?.isSuspend == true) {
					kfun.callSuspend(instance, *args)
				} else {
					method.invoke(instance, *args)
				}
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}

	fun Player.isInZone(coordStart: Location, coordEnd: Location): Boolean {
		val minX = min(coordStart.x, coordEnd.x)
		val maxX = max(coordStart.x, coordEnd.x)
		val minY = min(coordStart.y, coordEnd.y)
		val maxY = max(coordStart.y, coordEnd.y)
		val minZ = min(coordStart.z, coordEnd.z)
		val maxZ = max(coordStart.z, coordEnd.z)

		val loc = this.location
		return loc.x in minX..maxX &&
				loc.y in minY..maxY &&
				loc.z in minZ..maxZ
	}
}

enum class TriggerType {
	PATCHOULI,
	KOISHI,
	REIMU_AND_MARISA
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SubscribeTrigger(val value: TriggerType)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class NoVisitor
