package vip.qoriginal.quantumplugin.patch

import org.bukkit.Bukkit
import vip.qoriginal.quantumplugin.QuantumPlugin

object Utils {
	fun runTaskOnMainThread(runnable: Runnable) {
		Bukkit.getScheduler().runTask(QuantumPlugin.getInstance(), runnable)
	}
}