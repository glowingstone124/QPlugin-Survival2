package vip.qoriginal.quantumplugin

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import kotlin.concurrent.thread

object Config {
	private val yaml = Yaml()
	private const val CONFIG_FILE_NAME = "config.yml"
	private val configFile = File(CONFIG_FILE_NAME)

	@Volatile
	private var configData: Map<String, Any> = loadConfig()

	init {
		watchFileChanges()
	}

	private fun loadConfig(): Map<String, Any> {
		return configFile.inputStream().use { input ->
			yaml.load(input)
		}
	}

	private fun watchFileChanges() {
		thread(isDaemon = true) {
			val watchService = FileSystems.getDefault().newWatchService()
			val path = configFile.parentFile?.toPath() ?: File(".").toPath()
			path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

			while (true) {
				val key = watchService.take()
				for (event in key.pollEvents()) {
					val changed = event.context().toString()
					if (changed == CONFIG_FILE_NAME) {
						println("[Config] 检测到配置文件更新，重新加载中...")
						try {
							configData = loadConfig()
							println("[Config] 配置已更新: $configData")
						} catch (e: Exception) {
							println("[Config] 加载配置失败: ${e.message}")
						}
					}
				}
				key.reset()
			}
		}
	}

	fun get(key: String): Any? {
		return configData[key]
	}

	val API_ENDPOINT: String
		get() = (configData["API_ENDPOINT"] ?: "http://127.0.0.1:8080") as String
	val API_KEY: String
		get() = (configData["API_SECRET"] ?: "") as String
}
