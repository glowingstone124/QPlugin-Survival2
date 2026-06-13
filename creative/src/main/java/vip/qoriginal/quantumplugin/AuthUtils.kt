package vip.qoriginal.quantumplugin

import java.nio.file.Files
import java.nio.file.Path

object AuthUtils {
	fun getToken(): String{
		val content = Files.readString(Path.of("token.txt"))
		return content.replace("\n", "")
	}
}