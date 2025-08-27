package vip.qoriginal.quantumplugin.adventures

import io.github.classgraph.ClassGraph
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
			println("triggered eventhandler")
			call(TriggerType.PATCHOULI, event.enchanter)
		}
	}
	@EventHandler
	fun onZombieDeath(event: EntityDeathEvent) = runBlocking{
		val killer = event.entity.killer ?: return@runBlocking

		if (event.entity.type != EntityType.ZOMBIE) return@runBlocking

		val weapon = killer.inventory.itemInMainHand
		if (weapon.type != Material.IRON_SWORD) return@runBlocking
		println("triggered eventhandler")
		call(TriggerType.KOISHI, killer)
	}

	fun scan(packageName: String) {
		val result = mutableListOf<Pair<Any, java.lang.reflect.Method>>()

		ClassGraph()
			.acceptPackages(packageName)
			.enableClassInfo()
			.enableMethodInfo()
			.enableAnnotationInfo()
			.scan().use { scanResult ->
				val classesWithAnnotation = scanResult.getClassesWithMethodAnnotation(SubscribeTrigger::class.java.name)
				println("扫描到类数量: ${classesWithAnnotation.size}")

				for (classInfo in classesWithAnnotation) {
					val clazz = classInfo.loadClass()
					for (methodInfo in classInfo.methodInfo.filter { it.hasAnnotation(SubscribeTrigger::class.java.name) }) {
						val method = methodInfo.loadClassAndGetMethod()
						val anno = method.getAnnotation(SubscribeTrigger::class.java) ?: continue
						val type = anno.value

						val instance = clazz.kotlin.objectInstance ?: clazz.getDeclaredConstructor().newInstance()

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
	}
	suspend fun call(type: TriggerType, vararg args: Any?) {
		val methods = triggerMap[type] ?: return
		println("calling eventhandler")
		for ((instance, method) in methods) {
			println("calling eventhandler ${method.name}")
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
