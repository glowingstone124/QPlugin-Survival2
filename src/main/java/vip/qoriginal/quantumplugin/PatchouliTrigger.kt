package vip.qoriginal.quantumplugin
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.adventures.NoVisitor
import vip.qoriginal.quantumplugin.adventures.SubscribeTrigger
import vip.qoriginal.quantumplugin.adventures.TriggerType
import vip.qoriginal.quantumplugin.patch.Utils
import java.util.concurrent.ConcurrentHashMap

class CardsTrigger {

	private val achievementCache = ConcurrentHashMap<String, MutableSet<Int>>()
	private val headerMap = mapOf("Token" to Config.API_SECRET)

	@NoVisitor
	@SubscribeTrigger(TriggerType.PATCHOULI)
	fun onPatchouliEventGet(player: Player) {
		sendAchievementIfNotCached(player, 2, Advancements.Patchouli_Achievement)
	}

	@NoVisitor
	@SubscribeTrigger(TriggerType.KOISHI)
	fun onKoishiEventGet(player: Player) {
		sendAchievementIfNotCached(player, 3, Advancements.Koishi_Achievement)
	}

	@NoVisitor
	@SubscribeTrigger(TriggerType.REIMU_AND_MARISA)
	fun onReimuEvent(player: Player) {
		sendAchievementIfNotCached(player, 4, Advancements.Reimu_Achievement)
	}

	private fun sendAchievementIfNotCached(player: Player, advancementId: Int, achievement: Achievement) {
		val playerCache = achievementCache.computeIfAbsent(player.name) { mutableSetOf() }

		if (playerCache.contains(advancementId)) return

		CoroutineScope(Dispatchers.IO).launch {
			try {
				val result = Request.sendPostRequest(
					Config.API_ENDPOINT + "/qo/advancement/upload",
					JsonObject().apply {
						addProperty("player", player.name)
						addProperty("advancement", advancementId)
					}.toString()
				).get().asJsonObject()

				if (result.get("error").asString == "already achieved advancement") {
					playerCache.add(advancementId)
					return@launch
				}
				if (result.get("result").asBoolean) {
					playerCache.add(advancementId)
					Utils.runTaskOnMainThread {
						player.sendMessage(composeAchievementMessage(achievement))
					}
				}
			} catch (e: Exception) {
				println("网络请求失败: ${e.message}")
			}
		}
	}

	private fun composeAchievementMessage(achievement: Achievement): Component {
		return Component.text("=====成就达成=====").color(NamedTextColor.GREEN)
			.append(Component.newline())
			.append(Component.text("达成成就："))
			.append(
				Component.text(achievement.name)
					.color(NamedTextColor.YELLOW)
					.decorate(TextDecoration.UNDERLINED)
			)
			.hoverEvent(Component.text(achievement.description))
	}
}

data class Achievement(
	val name: String,
	val description: String,
)

object Advancements {
	val Patchouli_Achievement = Achievement(
		"帕秋莉的魔法",
		"在帕秋莉岛使用附魔台附魔一个物品"
	)
	val Koishi_Achievement = Achievement(
		"在你身后哦",
		"在隐身时使用铁剑击杀一只僵尸"
	)
	val Reimu_Achievement = Achievement(
		"畅玩于幻想之庭",
		"造访普罗米斯"
	)
}
