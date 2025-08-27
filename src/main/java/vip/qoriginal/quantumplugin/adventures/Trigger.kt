package vip.qoriginal.quantumplugin.adventures

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.player.PlayerMoveEvent
import vip.qoriginal.quantumplugin.ClassScanner
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

class Trigger : Listener {
	companion object {
		private val triggerMap: MutableMap<TriggerType, MutableList<Pair<Any, java.lang.reflect.Method>>> = mutableMapOf()
	}
	@EventHandler
	fun onPlayerEnchant(event: EnchantItemEvent) {
		if (event.isCancelled) {
			return
		}
		if (event.enchanter.scoreboardTags.contains("visitor")) {
			return
		}
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
				triggerMap.computeIfAbsent(type) { mutableListOf() }
					.add(instance to method)

				println("[TriggerManager] 注册触发器: ${clazz.name}.${method.name} -> $type")
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

}

enum class TriggerType {
	PATCHOULI,
	KOISHI,
	REIMU_AND_MARISA
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SubscribeTrigger(val value: TriggerType)