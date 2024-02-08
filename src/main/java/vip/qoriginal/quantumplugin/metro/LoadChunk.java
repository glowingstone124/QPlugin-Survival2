package vip.qoriginal.quantumplugin.metro;

import org.bukkit.Chunk;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;


public class LoadChunk implements Listener {
    public static int radius = 3;
    private final Plugin plugin;
    public LoadChunk(Plugin plugin) {
        this.plugin=plugin;
    }
    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();
            Block blockBelow = minecart.getLocation().subtract(0, 1, 0).getBlock();

            if (blockBelow.getType() == Material.EMERALD_BLOCK) {
                minecart.getPersistentDataContainer().set(new NamespacedKey(plugin, "load"), PersistentDataType.BYTE, (byte) 1);
            } else if (blockBelow.getType() == Material.IRON_BLOCK) {
                minecart.getPersistentDataContainer().remove(new NamespacedKey(plugin, "load"));
            }

            int chunkX = minecart.getLocation().getBlockX() >> 4;
            int chunkZ = minecart.getLocation().getBlockZ() >> 4;

            if (minecart.getPersistentDataContainer().has(new NamespacedKey(plugin, "load"), PersistentDataType.BYTE)) {
                for (int x = chunkX - radius; x <= chunkX + radius; x++) {
                    for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                        Chunk chunk = minecart.getWorld().getChunkAt(x, z);
                        if (!chunk.isLoaded()) {
                            chunk.load();
                        }
                    }
                }
            }
        }
    }
}
