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

            if (blockBelow.getType() == Material.DIAMOND_BLOCK) {
                minecart.addScoreboardTag("accel");
                minecart.addScoreboardTag("cr200j");
                minecart.removeScoreboardTag("curve");
                minecart.setMaxSpeed(1.6D);
            } else if (blockBelow.getType() == Material.EMERALD_BLOCK) {
                minecart.addScoreboardTag("accel");
                minecart.removeScoreboardTag("cr200j");
                minecart.removeScoreboardTag("curve");
                minecart.setMaxSpeed(1.2D);
            } else if (blockBelow.getType() == Material.IRON_BLOCK) {
                minecart.removeScoreboardTag("accel");
                minecart.removeScoreboardTag("curve");
                minecart.setMaxSpeed(0.4D);
            } else if (blockBelow.getType() == Material.GOLD_BLOCK) {
                minecart.addScoreboardTag("curve");
                minecart.setMaxSpeed(0.89D);
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

            if (blockBelow.getType() == Material.WHITE_TERRACOTTA || blockBelow.getType() == Material.BLACK_TERRACOTTA) {
                String p = "";
                for(int i=0;i<4;i++) {
                    switch (minecart.getLocation().subtract(0, 2+i, 0).getBlock().getType()) {
                        case WHITE_WOOL -> p = p + 0;
                        case LIGHT_GRAY_WOOL -> p = p + 1;
                        case GRAY_WOOL -> p = p + 2;
                        case BLACK_WOOL -> p = p + 3;
                        case BROWN_WOOL -> p = p + 4;
                        case RED_WOOL -> p = p + 5;
                        case ORANGE_WOOL -> p = p + 6;
                        case YELLOW_WOOL -> p = p + 7;
                        case LIME_WOOL -> p = p + 8;
                        case GREEN_WOOL -> p = p + 9;
                        case CYAN_WOOL -> p = p + "a";
                        case LIGHT_BLUE_WOOL -> p = p + "b";
                        case BLUE_WOOL -> p = p + "c";
                        case PURPLE_WOOL -> p = p + "d";
                        case MAGENTA_WOOL -> p = p + "e";
                        case PINK_WOOL -> p = p + "f";
                    }
                }
                if(blockBelow.getType() == Material.WHITE_TERRACOTTA) SegmentMap.enter(p,minecart);
                if(blockBelow.getType() == Material.BLACK_TERRACOTTA) SegmentMap.leave(p,minecart);
            }
        }
    }
}
