package vip.qoriginal.quantumplugin.patch

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.inventory.ItemStack

class CustomItemStack : Listener {
    private val stackableMap = mutableMapOf(
        Material.TOTEM_OF_UNDYING to 4
    )

    @EventHandler
    fun onPlayerPickupItem(event: PlayerPickupItemEvent) {
        val item = event.item.itemStack

        if (stackableMap.containsKey(item.type)) {
            val playerInventory = event.player.inventory
            val maxStackSize = stackableMap[item.type]!!

            val itemInInventory = findItemInInventory(playerInventory.contents, item.type)
            if (itemInInventory != null && itemInInventory.amount < maxStackSize) {
                val newAmount = itemInInventory.amount + item.amount
                if (newAmount <= maxStackSize) {
                    itemInInventory.amount = newAmount
                    event.item.remove()
                    event.isCancelled = true
                } else {
                    item.amount = newAmount - maxStackSize
                    itemInInventory.amount = maxStackSize
                }
            }
        }
    }

    private fun findItemInInventory(inventory: Array<ItemStack?>, material: Material): ItemStack? {
        for (item in inventory) {
            if (item != null && item.type == material && item.amount < stackableMap[material]!!) {
                return item
            }
        }
        return null
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.currentItem != null && stackableMap.containsKey(event.currentItem!!.type)) {
            val currentItem = event.currentItem
            val maxStackSize = stackableMap[event.currentItem!!.type]!!
            if (currentItem!!.amount > maxStackSize) {
                currentItem.amount = maxStackSize
            }
        }
    }
}
