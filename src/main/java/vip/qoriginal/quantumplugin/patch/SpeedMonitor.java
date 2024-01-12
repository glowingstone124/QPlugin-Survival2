package vip.qoriginal.quantumplugin.patch;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;


public class SpeedMonitor implements Listener {
    private final Plugin plugin;

    public SpeedMonitor(Plugin plugin) {
        this.plugin = plugin;
    }
    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        Entity entity = event.getEntered();
        if (entity instanceof Player) {
            Player player = (Player) entity;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isInsideVehicle()) {
                        double speed = calculateSpeed(player.getVelocity());
                        player.sendTitle("", "Speed: " + speed + "KM/H", 0, 20, 0);
                    } else {
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0, 20);
        }
    }

    private double calculateSpeed(Vector velocity) {
        double speed = velocity.length();
        return speed * 3.6 * Bukkit.getServer().getTPS()[0];
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        Entity entity = event.getExited();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            player.resetTitle();
        }
    }
    private double calculateSpeed(Vehicle vehicle) {
        double speed = 0.0;
        if (vehicle instanceof Boat) {
            Boat boat = (Boat) vehicle;
            Location currentLocation = boat.getLocation();
            Location previousLocation = boat.getMetadata("previousLocation").stream()
                    .findFirst()
                    .map(metadataValue -> (Location) metadataValue.value())
                    .orElse(currentLocation);
            double distance = currentLocation.distance(previousLocation);
            speed = distance / 20.0;
            boat.setMetadata("previousLocation", new FixedMetadataValue(plugin, currentLocation));
            return speed;
        } else {
            speed = vehicle.getVelocity().length();
        }
        return speed * 3.6 * 20;
    }
}
