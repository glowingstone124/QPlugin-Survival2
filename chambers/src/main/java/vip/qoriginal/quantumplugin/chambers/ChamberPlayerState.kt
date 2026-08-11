package vip.qoriginal.quantumplugin.chambers

import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect

class ChamberPlayerState private constructor(
    private val gameMode: GameMode,
    private val allowFlight: Boolean,
    private val flying: Boolean,
    private val invulnerable: Boolean,
    private val storageContents: Array<ItemStack?>,
    private val armorContents: Array<ItemStack?>,
    private val extraContents: Array<ItemStack?>,
    private val heldItemSlot: Int,
    private val level: Int,
    private val experience: Float,
    private val totalExperience: Int,
    private val health: Double,
    private val foodLevel: Int,
    private val saturation: Float,
    private val exhaustion: Float,
    private val fireTicks: Int,
    private val remainingAir: Int,
    private val freezeTicks: Int,
    private val potionEffects: List<PotionEffect>,
) {
    fun prepareForChamber(player: Player) {
        player.closeInventory()
        player.inventory.clear()
        player.inventory.armorContents =
            arrayOfNulls(player.inventory.armorContents.size)
        player.inventory.extraContents =
            arrayOfNulls(player.inventory.extraContents.size)
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.gameMode = GameMode.SURVIVAL
        player.isFlying = false
        player.allowFlight = false
        player.isInvulnerable = false
        player.level = 0
        player.exp = 0.0f
        player.totalExperience = 0
        player.foodLevel = 20
        player.saturation = 5.0f
        player.exhaustion = 0.0f
        player.fireTicks = 0
        player.fallDistance = 0.0f
        player.remainingAir = player.maximumAir
        player.freezeTicks = 0
        player.getAttribute(Attribute.MAX_HEALTH)?.value?.let { maximumHealth ->
            player.health = maximumHealth
        }
        player.updateInventory()
    }

    fun restore(player: Player) {
        player.closeInventory()
        player.inventory.storageContents = cloneContents(storageContents)
        player.inventory.armorContents = cloneContents(armorContents)
        player.inventory.extraContents = cloneContents(extraContents)
        player.inventory.heldItemSlot = heldItemSlot
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        potionEffects.forEach(player::addPotionEffect)
        player.totalExperience = totalExperience
        player.level = level
        player.exp = experience
        player.foodLevel = foodLevel
        player.saturation = saturation
        player.exhaustion = exhaustion
        player.fireTicks = fireTicks
        player.remainingAir = remainingAir
        player.freezeTicks = freezeTicks
        player.fallDistance = 0.0f
        player.getAttribute(Attribute.MAX_HEALTH)?.value?.let { maximumHealth ->
            player.health = health.coerceIn(MINIMUM_HEALTH, maximumHealth)
        }
        player.gameMode = gameMode
        player.allowFlight = allowFlight
        player.isFlying = flying && allowFlight
        player.isInvulnerable = invulnerable
        player.updateInventory()
    }

    companion object {
        private const val MINIMUM_HEALTH = 0.1

        fun capture(player: Player): ChamberPlayerState = ChamberPlayerState(
            gameMode = player.gameMode,
            allowFlight = player.allowFlight,
            flying = player.isFlying,
            invulnerable = player.isInvulnerable,
            storageContents = cloneContents(player.inventory.storageContents),
            armorContents = cloneContents(player.inventory.armorContents),
            extraContents = cloneContents(player.inventory.extraContents),
            heldItemSlot = player.inventory.heldItemSlot,
            level = player.level,
            experience = player.exp,
            totalExperience = player.totalExperience,
            health = player.health,
            foodLevel = player.foodLevel,
            saturation = player.saturation,
            exhaustion = player.exhaustion,
            fireTicks = player.fireTicks,
            remainingAir = player.remainingAir,
            freezeTicks = player.freezeTicks,
            potionEffects = player.activePotionEffects.toList(),
        )

        private fun cloneContents(contents: Array<ItemStack?>): Array<ItemStack?> =
            Array(contents.size) { index -> contents[index]?.clone() }
    }
}
