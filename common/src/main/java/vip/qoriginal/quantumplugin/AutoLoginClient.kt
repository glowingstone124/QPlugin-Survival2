package vip.qoriginal.quantumplugin

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.bukkit.entity.Player
import java.util.Optional

object AutoLoginClient {
	private val gson = Gson()

	fun playerIp(player: Player): String? = player.address?.address?.hostAddress

	fun canAutoLogin(username: String, ip: String): Boolean = runCatching {
		val response = Request.sendPostRequestWithStatus(
			Config.API_ENDPOINT + "/qo/authorization/auto-login",
			gson.toJson(AutoLoginRequest(username, ip)),
			Optional.of(mapOf("Token" to Config.API_SECRET))
		).get()
		response.status == 200 && JsonParser.parseString(response.body)
			.asJsonObject["ok"]
			?.asBoolean == true
	}.getOrDefault(false)

	private data class AutoLoginRequest(
		val username: String,
		val ip: String
	)
}
