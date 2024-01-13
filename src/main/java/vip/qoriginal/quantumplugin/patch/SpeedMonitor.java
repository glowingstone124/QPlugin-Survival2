package vip.qoriginal.quantumplugin.patch;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

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

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isInsideVehicle()) {
                        double speed = calculatePlayerSpeed(player);
                        DecimalFormat decimalFormat = new DecimalFormat("#.#");
                        String formattedSpeed = decimalFormat.format(speed);
                        player.sendTitle("", "Speed: " + formattedSpeed + "KM/H", 0, 20, 0);
                    } else {
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0, 20); // 每20 tick执行一次
        }
    }

    private double calculatePlayerSpeed(Player player) {
        Location currentLocation = player.getLocation();
        Location previousLocation = previousLocations.getOrDefault(player, currentLocation);
        double horizontalDistance = Math.sqrt(Math.pow(currentLocation.getX() - previousLocation.getX(), 2) +
                Math.pow(currentLocation.getZ() - previousLocation.getZ(), 2));
        double timeDelta = 1.0;

        // 计算速度
        double speed = horizontalDistance / timeDelta;

        // 更新上一个位置信息
        previousLocations.put(player, currentLocation);

        return speed * 3.6; // 转换为公里每小时
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
