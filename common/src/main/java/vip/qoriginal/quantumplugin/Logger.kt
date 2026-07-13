package vip.qoriginal.quantumplugin

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.bukkit.Bukkit
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

object LoggerProvider {
	private val loggers = ConcurrentHashMap<String, Logger>()

	fun getLogger(name: String): Logger = loggers.computeIfAbsent(name, ::Logger)

	fun closeAll() = runBlocking {
		loggers.values.forEach { it.close() }
		loggers.clear()
	}
}

class Logger @JvmOverloads constructor(private val source: String = "QuantumPlugin") {
	private val file = File(Bukkit.getServer().pluginsFolder, "log.log")
	private val channel = Channel<String>(Channel.UNLIMITED)
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

	init {
		file.parentFile?.mkdirs()
		if (!file.exists()) file.createNewFile()
		scope.launch {
			BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).use { writer ->
				var pending = 0
				for (message in channel) {
					writer.write(message)
					writer.newLine()
					if (++pending >= 100) {
						writer.flush()
						pending = 0
					}
				}
				writer.flush()
			}
		}
	}

	fun log(input: String) {
		channel.trySend("[${LocalDateTime.now().format(formatter)}][$source]$input")
	}

	@Deprecated("Use LoggerProvider.getLogger(source).log(input)")
	fun log(input: String, from: String) {
		LoggerProvider.getLogger(from).log(input)
	}

	fun debug(input: String) {
		if (!java.lang.Boolean.getBoolean("qplugin.debug") && !"true".equals(System.getenv("DEBUG"), true)) return
		val message = "[DEBUG][${LocalDateTime.now().format(formatter)}][$source]$input"
		println(message)
		channel.trySend(message)
	}

	fun strWithDebugPrint(input: String): String {
		debug(input)
		return input
	}

	suspend fun close() {
		channel.close()
		scope.coroutineContext[Job]?.join()
	}
}
