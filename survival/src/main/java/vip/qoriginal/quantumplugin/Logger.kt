package vip.qoriginal.quantumplugin

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.bukkit.Bukkit
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

object LoggerProvider {
	private val loggerMap = ConcurrentHashMap<String, Logger>()

	fun getLogger(name: String): Logger {
		return loggerMap.computeIfAbsent(name) { Logger(name) }
	}


	fun closeAll() = runBlocking {
		loggerMap.forEach { (_, logger) ->
			logger.close()
		}
	}
}

class Logger(private val clazz: String) {

	private val file = File(Bukkit.getServer().pluginsFolder, "log.log")

	private val channel = Channel<String>(capacity = Channel.UNLIMITED)
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val sdf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

	init {
		if (!file.exists()) file.createNewFile()
		scope.launch {
			val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
			var count = 0
			for (msg in channel) {
				writer.write(msg)
				writer.newLine()
				if (++count >= 100) {
					writer.flush()
					count = 0
				}
			}
			writer.flush()

		}
	}

	fun log(input: String) {
		val time = LocalDateTime.now().format(sdf)

		channel.trySend("[$time][$clazz]$input")
	}

	fun debug(input: String){
		if(!QuantumPlugin.DEBUG_FLAG) return
		val time = LocalDateTime.now().format(sdf)
		println("[DEBUG][$time][$clazz]$input")
		channel.trySend("[DEBUG][$time][$clazz]$input")
	}

	fun strWithDebugPrint(input: String): String{
		if(!QuantumPlugin.DEBUG_FLAG) return input
		val time = LocalDateTime.now().format(sdf)
		println("[DEBUG][$time][$clazz]$input")
		channel.trySend("[DEBUG][$time][$clazz]$input")
		return input
	}

	suspend fun close() {
		channel.close()
		scope.coroutineContext[Job]?.cancelAndJoin()
	}

}
