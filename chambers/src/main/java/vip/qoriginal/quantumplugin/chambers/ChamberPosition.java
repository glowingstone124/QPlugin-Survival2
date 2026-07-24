package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.Location;
import org.bukkit.World;

public record ChamberPosition(
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public Location in(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public Location relativeTo(World world, Location origin) {
        return new Location(
                world,
                origin.getX() + x,
                origin.getY() + y,
                origin.getZ() + z,
                yaw,
                pitch
        );
    }
}
