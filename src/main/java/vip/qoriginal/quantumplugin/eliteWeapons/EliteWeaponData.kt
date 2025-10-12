package vip.qoriginal.quantumplugin.eliteWeapons

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.papermc.paper.command.brigadier.argument.ArgumentTypes.player
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.Request
import vip.qoriginal.quantumplugin.asJsonObject

class EliteWeaponData {

	val gson: Gson = GsonBuilder().setPrettyPrinting().create()
	val listType = object : TypeToken<List<EliteWeapon>>(){}.type
	enum class WeaponReason() {
		HAS_ALREADY_UPDATED,
		NOT_A_VALID_ITEM,
		OK
	}

	data class EliteWeapon(
		val uuid:String,
		val owner:String,
		val type:String,
		val damage: Long,
		val kills: Long,
		val description: String,
	)


	companion object {
		val ELITE_ITEMS_CLOSECOMBAT = listOf(
			Material.DIAMOND_SWORD,
			Material.DIAMOND_AXE,
			Material.NETHERITE_SWORD,
			Material.NETHERITE_AXE,
			Material.TRIDENT,
			Material.MACE,
		)
		val ELITE_ITEMS_RANGED = listOf(
			Material.BOW,
			Material.CROSSBOW,
			Material.TRIDENT
		)

		val EliteWeaponCache = mutableMapOf<String, List<EliteWeapon>>()
	}

	fun checkIfWeaponHasEliteData(item: ItemStack): Boolean {
		val meta = item.itemMeta
		meta?.persistentDataContainer?.get(
			NamespacedKey("qplugin", "uuid"),
			PersistentDataType.STRING
		)?.let {
			return meta.persistentDataContainer.get(NamespacedKey("qplugin", "uuid"), PersistentDataType.STRING) != null
		}
		return false
	}

	fun cacheWeaponsForSpecUser(username: String) {
		val list = gson.fromJson<List<EliteWeapon>>(Request.sendGetRequest(Config.API_ENDPOINT + "/qo/elite/download?username=$username").get(), listType)
		EliteWeaponCache[username] = list
	}

	fun applyWeaponData(item: ItemStack, player: Player,desc:String):Pair<ItemStack, WeaponReason> {
		val meta = item.itemMeta
		if (meta.persistentDataContainer.get(NamespacedKey("qplugin", "uuid"), PersistentDataType.STRING) != null) return Pair(item, WeaponReason.HAS_ALREADY_UPDATED)

		val type = item.type
		if (type !in ELITE_ITEMS_CLOSECOMBAT && type !in ELITE_ITEMS_RANGED) {
			return Pair(item, WeaponReason.NOT_A_VALID_ITEM)
		}

		val result = Request.sendGetRequest(Config.API_ENDPOINT + "/qo/elite/create?owner=${player.name}&type=${item.type.name}&description=$desc").get().asJsonObject()

		if (result.get("result").asBoolean) {
			meta.persistentDataContainer.set(NamespacedKey("qplugin", "uuid"), PersistentDataType.STRING, result.get("uuid").asString)
			item.itemMeta = meta
		}
		return Pair(item, WeaponReason.OK)
	}
}