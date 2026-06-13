package vip.qoriginal.quantumplugin.eliteWeapons

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import vip.qoriginal.quantumplugin.Config
import vip.qoriginal.quantumplugin.Logger
import vip.qoriginal.quantumplugin.LoggerProvider
import vip.qoriginal.quantumplugin.Request
import vip.qoriginal.quantumplugin.asJsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EliteWeaponData {

	val logger = LoggerProvider.getLogger("EliteWeaponData")
	val gson: Gson = GsonBuilder().setPrettyPrinting().create()
	val listType = object : TypeToken<MutableList<EliteWeapon>>(){}.type
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

		val EliteWeaponCache = mutableMapOf<String, MutableList<EliteWeapon>>()
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

	fun getWeaponUuid(item: ItemStack): String? {
		val meta = item.itemMeta ?: return null
		meta?.persistentDataContainer?.get(
			NamespacedKey("qplugin", "uuid"),
			PersistentDataType.STRING
		)?.let {
			return meta.persistentDataContainer.get(NamespacedKey("qplugin", "uuid"), PersistentDataType.STRING)
		}
		return null
	}

	fun cacheWeaponsForSpecUser(username: String) {
		val list = gson.fromJson<MutableList<EliteWeapon>>(Request.sendGetRequest(Config.API_ENDPOINT + "/qo/elite/download?username=$username").get(), listType)
		EliteWeaponCache[username] = list
	}

	fun applyWeaponData(item: ItemStack, player: Player, desc: String, name: String): Pair<ItemStack, WeaponReason> {
		val meta = item.itemMeta ?: return Pair(item, WeaponReason.NOT_A_VALID_ITEM)

		if (meta.persistentDataContainer.get(NamespacedKey("qplugin", "uuid"), PersistentDataType.STRING) != null) {
			return Pair(item, WeaponReason.HAS_ALREADY_UPDATED)
		}

		val type = item.type
		if (type !in ELITE_ITEMS_CLOSECOMBAT && type !in ELITE_ITEMS_RANGED) {
			return Pair(item, WeaponReason.NOT_A_VALID_ITEM)
		}

		val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8)
		val encodedDesc = URLEncoder.encode(desc, StandardCharsets.UTF_8)


		val result = Request
			.sendGetRequest(logger.strWithDebugPrint("${Config.API_ENDPOINT}/qo/elite/create?owner=${player.name}&type=${item.type.name}&description=$encodedDesc&name=$encodedName"))
			.get()
			.asJsonObject()

		if (result.get("result").asBoolean) {
			val uuid = result.get("uuid").asString

			meta.persistentDataContainer.set(
				NamespacedKey("qplugin", "uuid"),
				PersistentDataType.STRING,
				uuid
			)

			meta.displayName(Component.text(name).color(TextColor.fromHexString("#FFD700")))
			meta.lore(listOf(Component.text(desc).color(TextColor.fromHexString("#414DA7"))))

			item.itemMeta = meta

			addWeaponInfoToCache(
				player.name,
				EliteWeapon(
					uuid = uuid,
					owner = player.name,
					type = type.name,
					damage = 0,
					kills = 0,
					description = desc
				)
			)

			return Pair(item, WeaponReason.OK)
		}

		return Pair(item, WeaponReason.NOT_A_VALID_ITEM)
	}


	fun addWeaponInfoToCache(username: String, eliteWeapon: EliteWeapon) {
		EliteWeaponCache[username]?.add(eliteWeapon)
	}
}