package vip.qoriginal.quantumplugin.fallen

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random
import java.util.Locale
import java.util.UUID

enum class FallenTeam(val displayName: String, val color: NamedTextColor) {
	A("A 阵营", NamedTextColor.DARK_RED),
	B("B 阵营", NamedTextColor.BLUE),
	C("C 阵营", NamedTextColor.GREEN);

	companion object {
		@JvmStatic
		fun parse(value: String?): FallenTeam {
			require(!value.isNullOrBlank()) { "缺少阵营。" }
			return entries.firstOrNull { it.name == value.trim().uppercase(Locale.ROOT) }
				?: throw IllegalArgumentException("未知阵营: $value，可用: A, B, C")
		}
	}
}

enum class FallenPhase {
	IDLE,
	DEPLOYMENT,
	ACTIVE,
	OVERTIME,
	ENDED;

	fun allowsKeyPlacement(): Boolean = this == DEPLOYMENT || this == ACTIVE || this == OVERTIME
	fun allowsKeyCapture(): Boolean = this == ACTIVE || this == OVERTIME

	companion object {
		@JvmStatic
		fun parse(value: String?): FallenPhase {
			require(!value.isNullOrBlank()) { "缺少活动阶段。" }
			return entries.firstOrNull { it.name == value.trim().uppercase(Locale.ROOT) }
				?: throw IllegalArgumentException("未知阶段: $value，可用: idle, deployment, active, overtime, ended")
		}
	}
}

enum class FallenKeyState {
	PLACED,
	ITEM,
	SELF_DESTRUCTING,
	DESTROYED
}

enum class FallenKeyType {
	INITIAL,
	REFRESH,
	STOLEN
}

data class FallenRegion(
	val worldName: String,
	val minX: Int,
	val minY: Int,
	val minZ: Int,
	val maxX: Int,
	val maxY: Int,
	val maxZ: Int
) {
	fun contains(location: Location): Boolean {
		val world = location.world ?: return false
		return world.name == worldName
			&& location.blockX in minX..maxX
			&& location.blockY in minY..maxY
			&& location.blockZ in minZ..maxZ
	}

	fun randomSpawn(): Location? {
		val world = Bukkit.getWorld(worldName) ?: return null
		repeat(80) {
			val x = Random.nextInt(minX, maxX + 1)
			val z = Random.nextInt(minZ, maxZ + 1)
			val highest = world.getHighestBlockYAt(x, z).coerceIn(minY, maxY)
			val location = Location(world, x + 0.5, highest + 1.0, z + 0.5)
			if (contains(location) && location.y >= 0 && location.block.isEmpty && location.clone().add(0.0, 1.0, 0.0).block.isEmpty) {
				return location
			}
		}
		return Location(world, (minX + maxX) / 2.0 + 0.5, minY.coerceAtLeast(1).toDouble(), (minZ + maxZ) / 2.0 + 0.5)
	}

	fun center(): Location? {
		val world = Bukkit.getWorld(worldName) ?: return null
		return Location(world, (minX + maxX) / 2.0 + 0.5, (minY + maxY) / 2.0 + 0.5, (minZ + maxZ) / 2.0 + 0.5)
	}

	fun save(section: ConfigurationSection) {
		section["world"] = worldName
		section["min-x"] = minX
		section["min-y"] = minY
		section["min-z"] = minZ
		section["max-x"] = maxX
		section["max-y"] = maxY
		section["max-z"] = maxZ
	}

	override fun toString(): String = "$worldName:$minX,$minY,$minZ -> $maxX,$maxY,$maxZ"

	companion object {
		fun of(worldName: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): FallenRegion {
			return FallenRegion(
				worldName = worldName,
				minX = minOf(x1, x2),
				minY = minOf(y1, y2),
				minZ = minOf(z1, z2),
				maxX = maxOf(x1, x2),
				maxY = maxOf(y1, y2),
				maxZ = maxOf(z1, z2)
			)
		}

		fun load(section: ConfigurationSection): FallenRegion {
			return of(
				section.getString("world") ?: "world",
				section.getInt("min-x"),
				section.getInt("min-y"),
				section.getInt("min-z"),
				section.getInt("max-x"),
				section.getInt("max-y"),
				section.getInt("max-z")
			)
		}
	}
}

data class FallenStation(
	val id: String,
	val team: FallenTeam,
	val worldName: String?,
	val x: Int,
	val y: Int,
	val z: Int,
	val links: Set<String>
) {
	fun center(): Location? {
		val world = Bukkit.getWorld(worldName ?: return null) ?: return null
		return Location(world, x + 0.5, y + 0.5, z + 0.5)
	}

	fun contains(location: Location): Boolean {
		val world = location.world ?: return false
		return worldName == world.name
			&& location.blockX in (x - 2)..(x + 2)
			&& location.blockY in y..(y + 2)
			&& location.blockZ in (z - 2)..(z + 2)
	}

	fun containsCore(location: Location): Boolean {
		val world = location.world ?: return false
		return worldName == world.name
			&& location.blockX in (x - 1)..(x + 1)
			&& location.blockY in y..(y + 2)
			&& location.blockZ in (z - 1)..(z + 1)
	}
}

data class FallenKey(
	val id: UUID,
	var ownerTeam: FallenTeam,
	val originalTeam: FallenTeam,
	var state: FallenKeyState,
	var type: FallenKeyType,
	var worldName: String? = null,
	var x: Int = 0,
	var y: Int = 0,
	var z: Int = 0,
	var holder: UUID? = null,
	var selfDestructAtMillis: Long = 0L,
	var expiresAtMillis: Long = 0L
) {
	fun placeAt(location: Location) {
		val world = location.world ?: throw IllegalArgumentException("密钥位置没有世界。")
		worldName = world.name
		x = location.blockX
		y = location.blockY
		z = location.blockZ
		holder = null
		selfDestructAtMillis = 0L
		expiresAtMillis = 0L
		state = FallenKeyState.PLACED
	}

	fun minLocation(): Location? {
		val world = Bukkit.getWorld(worldName ?: return null) ?: return null
		return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
	}

	fun center(): Location? {
		val world = Bukkit.getWorld(worldName ?: return null) ?: return null
		return Location(world, x + 1.0, y + 1.5, z + 1.0)
	}

	fun contains(location: Location): Boolean {
		val world = location.world ?: return false
		if (worldName != world.name) return false
		return location.blockX in x..(x + 1)
			&& location.blockY in y..(y + 2)
			&& location.blockZ in z..(z + 1)
	}

	fun shortId(): String = id.toString().substring(0, 8)

	fun save(section: ConfigurationSection) {
		section["owner"] = ownerTeam.name
		section["original"] = originalTeam.name
		section["state"] = state.name
		section["type"] = type.name
		section["world"] = worldName
		section["x"] = x
		section["y"] = y
		section["z"] = z
		section["holder"] = holder?.toString()
		section["self-destruct-at"] = selfDestructAtMillis
		section["expires-at"] = expiresAtMillis
	}

	companion object {
		fun load(id: UUID, section: ConfigurationSection): FallenKey {
			val owner = FallenTeam.parse(section.getString("owner"))
			return FallenKey(
				id = id,
				ownerTeam = owner,
				originalTeam = FallenTeam.parse(section.getString("original", owner.name)),
				state = FallenKeyState.valueOf(section.getString("state", FallenKeyState.ITEM.name)!!),
				type = FallenKeyType.valueOf(section.getString("type", FallenKeyType.INITIAL.name)!!),
				worldName = section.getString("world"),
				x = section.getInt("x"),
				y = section.getInt("y"),
				z = section.getInt("z"),
				holder = section.getString("holder")?.let(UUID::fromString),
				selfDestructAtMillis = section.getLong("self-destruct-at"),
				expiresAtMillis = section.getLong("expires-at")
			)
		}
	}
}
