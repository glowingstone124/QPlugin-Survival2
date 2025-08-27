package vip.qoriginal.quantumplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import vip.qoriginal.quantumplugin.adventures.NoVisitor
import vip.qoriginal.quantumplugin.adventures.SubscribeTrigger
import vip.qoriginal.quantumplugin.adventures.TriggerType

class CardsTrigger {
	@NoVisitor
	@SubscribeTrigger(TriggerType.PATCHOULI)
	fun onPatchouliEventGet(player: Player){
		println("triggered sender")
		val achievement = Achievement(
			"帕秋莉的魔法",
			"在帕秋莉岛使用附魔台附魔一个物品"
		)
		player.sendMessage(composeAchievementMessage(achievement))
	}

	@NoVisitor
	@SubscribeTrigger(TriggerType.KOISHI)
	fun onKoishiEventGet(player: Player){
		println("triggered sender")
		val achievement = Achievement(
			"无意识的存在",
			"在隐身时使用铁剑击杀一只僵尸"
		)
		player.sendMessage(composeAchievementMessage(achievement))
	}

	fun composeAchievementMessage(achievement: Achievement): Component {
		return Component.text("=====成就达成=====").color(NamedTextColor.GREEN)
			.append(Component.newline())
			.append(Component.text("达成成就："))
			.append(Component.text(achievement.name).color(NamedTextColor.YELLOW).decorate(TextDecoration.UNDERLINED))
			.hoverEvent(Component.text(achievement.description))
	}
}

data class Achievement(
	val name: String,
	val description: String,
)