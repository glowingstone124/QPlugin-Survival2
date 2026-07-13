package vip.qoriginal.quantumplugin.patch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public class SpeedMonitor implements Listener {
    private final Plugin plugin;
    private final Predicate<Entity> ignoredVehicles;
    private final Map<Player, Location> previousLocations = new HashMap<>();

    public SpeedMonitor(Plugin plugin) {
        this(plugin, vehicle -> vehicle instanceof HappyGhast);
    }

    public SpeedMonitor(Plugin plugin, Predicate<Entity> ignoredVehicles) {
        this.plugin = plugin;
        this.ignoredVehicles = ignoredVehicles;
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (ignoredVehicles.test(event.getVehicle()) || !(event.getEntered() instanceof Player player)) return;
        Component actionBar = event.getVehicle() instanceof Boat
                ? Component.text("QO交通委提醒您，行船不规范，亲人两行泪。 欢迎您，高级驾驶员 " + player.getName(), NamedTextColor.GREEN)
                : event.getVehicle() instanceof Minecart
                ? Component.text("感谢您选择QO铁路，QO高速铁路现已全面普及108km/h高速 ", NamedTextColor.GREEN)
                : Component.empty();
        new BukkitRunnable() {
            @Override public void run() {
                if (!player.isInsideVehicle()) { cancel(); return; }
                String speed = new DecimalFormat("#.#").format(calculatePlayerSpeed(player));
                player.sendActionBar(actionBar);
                player.showTitle(Title.title(Component.empty(), Component.text("Speed: " + speed + "KM/H"),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO)));
            }
        }.runTaskTimer(plugin, 0, 20);
    }

    public double calculatePlayerSpeed(Player player) {
        Location current = player.getLocation();
        Location previous = previousLocations.getOrDefault(player, current);
        previousLocations.put(player, current);
        return Math.hypot(current.getX() - previous.getX(), current.getZ() - previous.getZ()) * 3.6;
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            previousLocations.remove(player);
            player.clearTitle();
        }
    }
}
