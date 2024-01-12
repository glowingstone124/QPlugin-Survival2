package vip.qoriginal.quantumplugin.patch;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

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
                        Vehicle vehicle = (Vehicle) player.getVehicle();
                        double speed = calculateSpeed(vehicle);
                        player.sendTitle("", "Speed: " + speed + "KM/H", 0, 20, 0);
                    } else {
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0, 20);
        }
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
        double speed = vehicle.getVelocity().length();
        return speed * 3.6 * 20;//TPS
    }
}
