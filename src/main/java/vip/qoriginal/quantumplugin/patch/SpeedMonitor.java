package vip.qoriginal.quantumplugin.patch;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

public class SpeedMonitor implements Listener {
    private final Plugin plugin;
    private final Map<Player, Location> previousLocations = new HashMap<>();

    public SpeedMonitor(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        Entity entity = event.getEntered();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            String coloredActionbar = ChatColor.GREEN + "QO交通委提醒您，行船不规范，亲人两行泪。 欢迎您，高级驾驶员 " +player.getDisplayName();
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isInsideVehicle()) {
                        double speed = calculatePlayerSpeed(player);
                        DecimalFormat decimalFormat = new DecimalFormat("#.#");
                        String formattedSpeed = decimalFormat.format(speed);
                        player.sendActionBar(coloredActionbar);
                        player.sendTitle("", "Speed: " + formattedSpeed + "KM/H", 0, 20, 0);
                    } else {
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0, 20);
        }
    }

    private double calculatePlayerSpeed(Player player) {
        Location currentLocation = player.getLocation();
        Location previousLocation = previousLocations.getOrDefault(player, currentLocation);
        double horizontalDistance = Math.sqrt(Math.pow(currentLocation.getX() - previousLocation.getX(), 2) +
                Math.pow(currentLocation.getZ() - previousLocation.getZ(), 2));
        double timeDelta = 1.0;
        double speed = horizontalDistance / timeDelta;
        previousLocations.put(player, currentLocation);
        return speed * 3.6;
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        Entity entity = event.getExited();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            previousLocations.remove(player);
            player.resetTitle();
        }
    }
}
