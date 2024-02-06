package vip.qoriginal.quantumplugin.metro;

import org.bukkit.Chunk;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;

public class LoadChunk implements Listener{
    public static int radius = 3;
    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();
            int chunkX = minecart.getLocation().getBlockX() >> 4;
            int chunkZ = minecart.getLocation().getBlockZ() >> 4;

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