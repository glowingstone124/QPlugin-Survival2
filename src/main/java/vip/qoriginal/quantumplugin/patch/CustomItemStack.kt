package vip.qoriginal.quantumplugin.patch

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.inventory.ItemStack
import java.util.*
import kotlin.collections.HashMap

class CustomItemStack : Listener {
    private val stackableMap: MutableMap<Material, Int> = EnumMap(org.bukkit.Material::class.java)

    init {
        stackableMap[Material.TOTEM_OF_UNDYING] = 4
    }

    @EventHandler
    fun onPlayerPickupItem(event: PlayerPickupItemEvent) {
        val item = event.item
        val itemStack = item.itemStack

        if (stackableMap.containsKey(itemStack.type)) {
            val player = event.player
            val maxStackSize = stackableMap[itemStack.type]!!

            val itemInInventory = findItemInInventory(player, itemStack.type)
            if (itemInInventory != null && itemInInventory.amount < maxStackSize) {
                val newAmount = itemInInventory.amount + itemStack.amount
                if (newAmount <= maxStackSize) {
                    itemInInventory.amount = newAmount
                    item.remove()
                    event.isCancelled = true
                } else {
                    itemStack.amount = newAmount - maxStackSize
                    itemInInventory.amount = maxStackSize
                }
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.clickedInventory != null && event.clickedInventory!!.type == InventoryType.PLAYER) {
            val currentItem = event.currentItem
            if (currentItem != null && stackableMap.containsKey(currentItem.type)) {
                val player = event.whoClicked as Player
                val maxStackSize = stackableMap[currentItem.type]!!
                if (currentItem.amount > maxStackSize) {
                    currentItem.amount = maxStackSize
                    player.updateInventory()
                }
            }
        }
    }

    private fun findItemInInventory(player: Player, material: Material): ItemStack? {
        val contents = player.inventory.contents
        for (item in contents) {
            if (item != null && item.type == material && item.amount < stackableMap[material]!!) {
                return item
            }
        }
        return null
    }
}