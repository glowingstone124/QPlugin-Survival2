package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.Location;
import org.bukkit.World;

public record ChamberRegion(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {
    public boolean contains(Location location, World instanceWorld, Location origin) {
        int offsetX = origin.getBlockX();
        int offsetY = origin.getBlockY();
        int offsetZ = origin.getBlockZ();
        return location.getWorld() != null
                && location.getWorld().getUID().equals(instanceWorld.getUID())
                && location.getBlockX() >= offsetX + minX
                && location.getBlockX() <= offsetX + maxX
                && location.getBlockY() >= offsetY + minY
                && location.getBlockY() <= offsetY + maxY
                && location.getBlockZ() >= offsetZ + minZ
                && location.getBlockZ() <= offsetZ + maxZ;
    }
}
