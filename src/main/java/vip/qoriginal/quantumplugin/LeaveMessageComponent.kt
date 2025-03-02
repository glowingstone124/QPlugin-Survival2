package vip.qoriginal.quantumplugin

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class LeaveMessageComponent {
	fun handlePlayerMessageUpload(player: Player,target:String, message: String): Boolean {
		if(player.name == target) {
			player.sendMessage("您不能向自己发送留言。")
			return false;
		}

		if (Bukkit.getPlayer(target)?.isConnected == true) {
			player.sendMessage(Component.text("您不能向当前正在线的玩家 $target 发送留言。"))
			return false;
		}

		val result = Bukkit.getOfflinePlayers().filter { it.name.equals(target) }
		if (result.isEmpty()) {
			player.sendMessage(Component.text("您指定的玩家不存在。"))
			return false;
		}

		val res = Request.sendPostRequest(Config.API_ENDPOINT + "/qo/leavemessage/upload?from=${player.name}&to=${target}&message=${message}", "").get().asJsonObject()
		when(res.get("code").asInt) {
			0 -> player.sendMessage("发送成功。"); return true,
			1 -> player.sendMessage("您不被允许发送消息。")
			2 -> player.sendMessage("您发送的留言太多了，您最多只能同时拥有五条留言。")
			3 -> player.sendMessage("对方的留言箱已满！")
		}
		return false;
	}

	fun getMessages(player: Player): List<Component> {
		val result = Request.sendGetRequest(Config.API_ENDPOINT + "/qo/leavemessage/get?receiver=${player.name}")
			.get().asJsonArray()


		val gson = Gson()
		val listType = object : TypeToken<List<LeaveMessage>>() {}.type
		val messages: List<LeaveMessage> = gson.fromJson(result, listType)

		return messages.map { msg ->
			Component.text("${msg.from}:").color(TextColor.fromCSSHexString("#008000")).append(Component.text(msg.message))
		}
	}
}
data class LeaveMessage(
	val from: String,
	val to: String,
	val message: String,
)
fun String.asJsonObject(): JsonObject {
	return JsonParser.parseString(this).asJsonObject
}
fun String.asJsonArray(): JsonArray {
	return JsonParser.parseString(this).asJsonArray
}