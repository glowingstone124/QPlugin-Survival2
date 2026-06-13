package vip.qoriginal.quantumplugin
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
class Logger {
    val file = File(Bukkit.getServer().pluginsFolder, "log.log")
    val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    init {
        if (!file.exists()) {
            file.createNewFile()
        }
    }
    @Synchronized
    fun log(input:String, from:String) {
        coroutineScope.launch {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            val time = sdf.format(Date())
            FileWriter(file).use {
                it.write("[$time][$from]${input}")
                it.appendLine()
            }
        }
    }
}