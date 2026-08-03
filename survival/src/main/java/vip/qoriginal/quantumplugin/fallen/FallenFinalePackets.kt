package vip.qoriginal.quantumplugin.fallen

import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.world.level.ChunkPos
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

data class FallenChunkOffset(val x: Int, val z: Int)

object FallenFinaleRules {
	fun chunkRing(radius: Int): List<FallenChunkOffset> {
		require(radius >= 0) { "radius must not be negative" }
		if (radius == 0) return listOf(FallenChunkOffset(0, 0))
		val result = ArrayList<FallenChunkOffset>(radius * 8)
		for (x in -radius..radius) {
			result += FallenChunkOffset(x, -radius)
			result += FallenChunkOffset(x, radius)
		}
		for (z in (-radius + 1) until radius) {
			result += FallenChunkOffset(-radius, z)
			result += FallenChunkOffset(radius, z)
		}
		return result
	}
}

object FallenFinalePackets {
	fun forgetChunk(player: Player, chunkX: Int, chunkZ: Int) {
		val craftPlayer = player as? CraftPlayer ?: return
		craftPlayer.handle.connection.send(ClientboundForgetLevelChunkPacket(ChunkPos(chunkX, chunkZ)))
	}
}
