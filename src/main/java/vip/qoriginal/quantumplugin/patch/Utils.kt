package vip.qoriginal.quantumplugin.patch

import org.bukkit.Bukkit
import vip.qoriginal.quantumplugin.QuantumPlugin
import kotlin.collections.forEach

object Utils {
	fun runTaskOnMainThread(runnable: Runnable) {
		Bukkit.getScheduler().runTask(QuantumPlugin.getInstance(), runnable)
	}
	fun avg(input: List<Number>): Double {
		if (input.isEmpty()) return 0.0
		val sum = input.sumOf { it.toDouble() }
		return sum / input.size
	}
}