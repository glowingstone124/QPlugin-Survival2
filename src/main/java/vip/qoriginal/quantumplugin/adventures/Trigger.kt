package vip.qoriginal.quantumplugin.adventures

import io.github.classgraph.ClassGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.bukkit.potion.PotionEffectType
import vip.qoriginal.quantumplugin.Logger
import vip.qoriginal.quantumplugin.LoggerProvider
import vip.qoriginal.quantumplugin.QuantumPlugin.WORLD_MAIN
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

class Trigger : Listener {
	private val lastTriggerTime = mutableMapOf<UUID, Long>()
	private val COOLDOWN = 10_000L
	private val logger = LoggerProvider.getLogger("AdventuresTrigger")
	companion object {
		private val triggerMap: MutableMap<TriggerType, MutableList<Pair<Any, java.lang.reflect.Method>>> =
			mutableMapOf()
	}

	@EventHandler
	fun onPlayerEnchant(event: EnchantItemEvent) = runBlocking {
		//帕秋莉岛
		if (event.enchanter.isInZone2D(
				Location(WORLD_MAIN, -2401.0, -64.0, 1432.0),
				Location(WORLD_MAIN, -2197.0, 320.0, 1624.0)
			)
		) {
			logger.debug("triggered eventhandler")
			call(TriggerType.PATCHOULI, event.enchanter)
		}
	}

	@EventHandler
	fun onPlayerMove(event: org.bukkit.event.player.PlayerMoveEvent) {
		val player = event.player

		val currentZone = Zones.ZONE_LIST.find { it.isInZone(player) }

		if (currentZone != null) {
			currentZone.executeEnter(player)

			Zones.ZONE_LIST
				.filter { it != currentZone && player.scoreboardTags.contains("in${it.name}") }
				.forEach { it.executeExit(player) }
		} else {
			Zones.ZONE_LIST
				.filter { player.scoreboardTags.contains("in${it.name}") }
				.forEach { it.executeExit(player) }
		}
	}


	@EventHandler
	fun onZombieDeath(event: EntityDeathEvent) = runBlocking {
		val killer = event.entity.killer ?: return@runBlocking

		if (event.entity.type != EntityType.ZOMBIE) return@runBlocking

		val weapon = killer.inventory.itemInMainHand
		if (weapon.type != Material.IRON_SWORD) return@runBlocking
		if (!killer.hasPotionEffect(PotionEffectType.INVISIBILITY)) return@runBlocking
		logger.debug("triggered eventhandler")
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
				logger.debug("扫描到类数量: ${classesWithAnnotation.size}")

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
		logger.debug("calling eventhandler")
		for ((instance, method) in methods) {
			logger.debug("calling eventhandler ${method.name}")
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

	fun Player.isInZone2D(coordStart: Location, coordEnd: Location): Boolean {
		val minX = min(coordStart.x, coordEnd.x)
		val maxX = max(coordStart.x, coordEnd.x)
		val minZ = min(coordStart.z, coordEnd.z)
		val maxZ = max(coordStart.z, coordEnd.z)

		val loc = this.location
		return loc.x in minX..maxX &&
				loc.z in minZ..maxZ
	}
}

enum class TriggerType {
	PATCHOULI,
	KOISHI,
	REIMU_AND_MARISA,
	ORIN
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SubscribeTrigger(val value: TriggerType)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class NoVisitor