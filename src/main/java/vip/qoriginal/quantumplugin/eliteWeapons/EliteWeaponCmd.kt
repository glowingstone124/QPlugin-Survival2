package vip.qoriginal.quantumplugin.eliteWeapons

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class EliteWeaponCmd: CommandExecutor {
	val eliteWeaponData = EliteWeaponData()
	override fun onCommand(
		p0: CommandSender,
		p1: Command,
		p2: String,
		p3: Array<out String>
	): Boolean {
		val player = p0 as Player
		if (p3.isEmpty()) {
			player.sendMessage(Component.text("您需要为elite weapon指定一个名字和对应的铭文，格式为/elite <name> <inscriptions>。若有空格，使用“”包裹整段文字。"))
			return true
		}
		val name = p3[0]
		val inscription = p3.drop(1).joinToString(" ").trim('"')
		val result = eliteWeaponData.applyWeaponData(player.inventory.itemInMainHand, player, inscription, name)
		when (result.second) {
			EliteWeaponData.WeaponReason.NOT_A_VALID_ITEM -> {
				player.sendMessage(Component.text("您的物品不合法。"))
				return true
			}

			EliteWeaponData.WeaponReason.HAS_ALREADY_UPDATED -> {
				player.sendMessage(Component.text("您已经拥有了一个此类型的英雄武器。"))
				return true
			}

			EliteWeaponData.WeaponReason.OK -> {
				player.sendMessage(Component.text("升级成功！").color(NamedTextColor.GOLD))
			}
		}
		player.inventory.itemInMainHand.itemMeta = result.first.itemMeta
		return true
	}
}