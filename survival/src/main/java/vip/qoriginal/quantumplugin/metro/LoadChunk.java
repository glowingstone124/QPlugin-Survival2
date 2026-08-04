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
    private final ExperimentalAcceleration acceleration = new ExperimentalAcceleration();
    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            Block blockBelow = minecart.getLocation().subtract(0, 1, 0).getBlock();

            if (blockBelow.getType() == Material.DIAMOND_BLOCK) {
                endExperimentalAcceleration(minecart);
                minecart.addScoreboardTag("accel");
                minecart.addScoreboardTag("cr200j");
                minecart.removeScoreboardTag("curve");
                minecart.setMaxSpeed(1.6D);
            } else if (blockBelow.getType() == Material.EMERALD_BLOCK) {
                endExperimentalAcceleration(minecart);
                minecart.addScoreboardTag("accel");
                minecart.removeScoreboardTag("cr200j");
                minecart.removeScoreboardTag("curve");
                minecart.setMaxSpeed(1.2D);
            } else if (acceleration.ensuresCondition(minecart)) {
                minecart.removeScoreboardTag("accel");
                minecart.removeScoreboardTag("cr200j");
                minecart.removeScoreboardTag("curve");
                minecart.addScoreboardTag("accelplus");
                acceleration.startExperimentalAcceleration(minecart);
            } else if (blockBelow.getType() == Material.IRON_BLOCK) {
                minecart.removeScoreboardTag("accel");
                minecart.removeScoreboardTag("curve");
                endExperimentalAcceleration(minecart);
                minecart.setMaxSpeed(0.4D);
            } else if (blockBelow.getType() == Material.GOLD_BLOCK) {
                endExperimentalAcceleration(minecart);
                minecart.addScoreboardTag("accel");
                minecart.addScoreboardTag("curve");
                minecart.setMaxSpeed(0.89D);
            } else if (minecart.getScoreboardTags().contains("accelplus")) {
                // Restore the in-memory state after the entity has been unloaded and loaded again.
                acceleration.startExperimentalAcceleration(minecart);
            }

            if (blockBelow.getType() == Material.WHITE_TERRACOTTA || blockBelow.getType() == Material.BLACK_TERRACOTTA) {
                StringBuilder p = new StringBuilder();
                for(int i=0;i<4;i++) {
                    switch (minecart.getLocation().subtract(0, 2+i, 0).getBlock().getType()) {
                        case LIGHT_GRAY_WOOL -> p.append(1);
                        case GRAY_WOOL -> p.append(2);
                        case BLACK_WOOL -> p.append(3);
                        case BROWN_WOOL -> p.append(4);
                        case RED_WOOL -> p.append(5);
                        case ORANGE_WOOL -> p.append(6);
                        case YELLOW_WOOL -> p.append(7);
                        case LIME_WOOL -> p.append(8);
                        case GREEN_WOOL -> p.append(9);
                        case CYAN_WOOL -> p.append("a");
                        case LIGHT_BLUE_WOOL -> p.append("b");
                        case BLUE_WOOL -> p.append("c");
                        case PURPLE_WOOL -> p.append("d");
                        case MAGENTA_WOOL -> p.append("e");
                        case PINK_WOOL -> p.append("f");
                        default -> p.append(0);
                    }
                }
                if(blockBelow.getType() == Material.WHITE_TERRACOTTA) SegmentMap.enter(p.toString(),minecart);
                if(blockBelow.getType() == Material.BLACK_TERRACOTTA) SegmentMap.leave(p.toString(),minecart);
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

    private void endExperimentalAcceleration(Minecart minecart) {
        minecart.removeScoreboardTag("accelplus");
        acceleration.endExperimentalAcceleration(minecart);
    }
}
