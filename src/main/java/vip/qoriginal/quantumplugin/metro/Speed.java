package vip.qoriginal.quantumplugin.metro;

import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.Material;


public class Speed implements Listener{
    double targetSpeedKMH = 120.0;
    double targetSpeedMS = targetSpeedKMH * 1000 / 3600;
    @EventHandler
    public void onMinecartCreate(VehicleCreateEvent event) {
        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();
            double maxSpeed = targetSpeedMS;
            minecart.setMaxSpeed(maxSpeed);
        }
    }
    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();
            Material blockTypeBelow = minecart.getLocation().subtract(0, 1, 0). getBlock().getType();

        if (blockTypeBelow == Material.SMOOTH_STONE) {
            minecart.setMaxSpeed(28.8 * 1000 / 3600);
        } else {
            minecart.setMaxSpeed(targetSpeedMS);
        }

        boost(minecart);
        }
    }
    private void boost(Minecart minecart) {
        if (minecart.getLocation().getBlock().isBlockPowered()) {

            Vector currentVelocity = minecart.getVelocity();
            double currentSpeed = currentVelocity.length();

            if (currentSpeed > 0) {
                double factor = targetSpeedMS / currentSpeed;
                Vector newVelocity = currentVelocity.multiply(factor);
                minecart.setVelocity(newVelocity);
            }
        }
    }
}