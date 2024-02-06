package vip.qoriginal.quantumplugin.metro;

import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;

public class Speed implements Listener {
    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event){
        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();
            boost(minecart);
        }
    }
    private void boost(Minecart minecart) {
        if (minecart.getLocation().getBlock().isBlockPowered()) {
            double targetSpeedKMH = 120.0;
            double targetSpeedMS = targetSpeedKMH * 1000 / 3600;

            double currentSpeed = Math.sqrt(minecart.getVelocity().getX() * minecart.getVelocity().getX()
                    + minecart.getVelocity().getZ() * minecart.getVelocity().getZ());
            double factor = targetSpeedMS / currentSpeed;

            minecart.setVelocity(minecart.getVelocity().multiply(factor));
        }
    }
}