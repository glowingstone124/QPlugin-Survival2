package vip.qoriginal.quantumplugin

import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path

object AuthUtils {
	fun getToken(): String{
		return Files.readString(Path("token.txt"))
	}
}