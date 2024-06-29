package vip.qoriginal.quantumplugin.patch

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import vip.qoriginal.quantumplugin.QuantumPlugin
import java.util.*
import kotlin.math.min

class CustomItemStack : Listener {
    private val stackableMap: MutableMap<Material, Int> = EnumMap(Material::class.java)

    init {
        stackableMap[Material.TOTEM_OF_UNDYING] = 4
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val itemStack = event.item.itemStack

        if (stackableMap.containsKey(itemStack.type)) {
            val player = event.entity as Player

            val itemInInventory = findItemInInventory(player, itemStack.type)
            if (itemInInventory != null) {
                val maxStackSize = stackableMap[itemStack.type]!!
                val newAmount = itemInInventory.amount + itemStack.amount
                if (newAmount <= maxStackSize) {
                    itemInInventory.amount = newAmount
                    event.item.remove()
                    event.isCancelled = true
                } else {
                    itemInInventory.amount = maxStackSize
                    itemStack.amount = newAmount - maxStackSize
                }
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        handleItemStacking(event.currentItem, event.whoClicked as Player)
    }

    @EventHandler
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        val sourceInventory = event.source
        val destinationInventory = event.destination

        handleItemStacking(event.item, sourceInventory, destinationInventory)
    }

    private fun handleItemStacking(itemStack: ItemStack?, player: Player) {
        if (itemStack != null && stackableMap.containsKey(itemStack.type)) {
            val maxStackSize = stackableMap[itemStack.type]!!
            if (itemStack.amount > maxStackSize) {
                itemStack.amount = maxStackSize
                player.updateInventory()
            }
        }
    }

    private fun handleItemStacking(itemStack: ItemStack?, sourceInventory: Inventory, destinationInventory: Inventory) {
        if (itemStack != null && stackableMap.containsKey(itemStack.type)) {
            val maxStackSize = stackableMap[itemStack.type]!!
            var remainingAmount = itemStack.amount
            for (i in destinationInventory.contents.indices) {
                val destItem = destinationInventory.getItem(i)

                if (remainingAmount <= 0) break

                if (destItem == null || destItem.type == Material.AIR) {
                    val transferAmount = min(remainingAmount, maxStackSize - (destItem?.amount ?: 0))
                    val newItem = ItemStack(itemStack.type, transferAmount)
                    destinationInventory.setItem(i, newItem)
                    remainingAmount -= transferAmount
                } else if (destItem.type == itemStack.type && destItem.amount < maxStackSize) {
                    val spaceLeft = maxStackSize - destItem.amount
                    val transferAmount = min(remainingAmount, spaceLeft)
                    destItem.amount += transferAmount
                    remainingAmount -= transferAmount
                    destinationInventory.setItem(i, destItem)
                }
            }

            if (remainingAmount <= 0) {
                itemStack.amount = 0
                sourceInventory.removeItem(itemStack)
            } else {
                itemStack.amount = remainingAmount
            }

            Bukkit.getScheduler().runTask(QuantumPlugin.getInstance(), Runnable {
                sourceInventory.contents = sourceInventory.contents
                destinationInventory.contents = destinationInventory.contents
            })
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