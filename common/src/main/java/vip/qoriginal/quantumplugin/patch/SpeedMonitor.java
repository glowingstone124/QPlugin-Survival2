package vip.qoriginal.quantumplugin.patch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.*;
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
    private static final int MINECART_ANIMATION_TICKS = 40;
    private static final int MINECART_ANIMATION_FRAME_TICKS = 4;
    private static final String[] MINECART_ANIMATION_FRAMES = {
            "Q U H R",
            "QU H R",
            "QUH R",
            "QUHR",
            "QUHR >"
    };

    private final Plugin plugin;
    private final Predicate<Entity> ignoredVehicles;
    private final Predicate<Entity> experimentalAccelerationVehicles;
    private final Map<Player, Location> previousLocations = new HashMap<>();

    public SpeedMonitor(Plugin plugin) {
        this(plugin, vehicle -> vehicle instanceof HappyGhast, vehicle -> false);
    }

    public SpeedMonitor(Plugin plugin, Predicate<Entity> ignoredVehicles) {
        this(plugin, ignoredVehicles, vehicle -> false);
    }

    public SpeedMonitor(
            Plugin plugin,
            Predicate<Entity> ignoredVehicles,
            Predicate<Entity> experimentalAccelerationVehicles
    ) {
        this.plugin = plugin;
        this.ignoredVehicles = ignoredVehicles;
        this.experimentalAccelerationVehicles = experimentalAccelerationVehicles;
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (ignoredVehicles.test(event.getVehicle()) || !(event.getEntered() instanceof Player player)) return;
        Component actionBar = event.getVehicle() instanceof Boat
                ? Component.text("QO交通委提醒您，行船不规范，亲人两行泪。 欢迎您，高级驾驶员 " + player.getName(), NamedTextColor.GREEN)
                : event.getVehicle() instanceof Minecart
                ? genMinecartMsg(event)
                : Component.empty();
        new BukkitRunnable() {
            private int elapsedTicks;

            @Override public void run() {
                if (!player.isInsideVehicle() || player.getVehicle() != event.getVehicle()) {
                    cancel();
                    return;
                }

                if (event.getVehicle() instanceof Minecart) {
                    Component minecartActionBar = elapsedTicks < MINECART_ANIMATION_TICKS
                            ? genMinecartAnimation(elapsedTicks / MINECART_ANIMATION_FRAME_TICKS)
                            : genMinecartMsg(event);
                    player.sendActionBar(minecartActionBar);

                    if (elapsedTicks % 20 == 0) {
                        sendSpeedTitle(player);
                    }
                } else {
                    player.sendActionBar(actionBar);
                    sendSpeedTitle(player);
                }
                elapsedTicks += 2;
            }
        }.runTaskTimer(plugin, 0, 2);
    }

    private Component genMinecartAnimation(int frame) {
        return Component.text(
                MINECART_ANIMATION_FRAMES[
                        Math.min(frame, MINECART_ANIMATION_FRAMES.length - 1)
                ],
                NamedTextColor.AQUA
        );
    }

    private Component genMinecartMsg(VehicleEnterEvent event) {
        if (experimentalAccelerationVehicles.test(event.getVehicle())) {
            return Component.text("感谢您选择UHR轨道交通系统。", NamedTextColor.AQUA);
        }
        return Component.text("感谢您选择QO铁路，QO高速铁路现已全面普及108km/h高速 ", NamedTextColor.GREEN);
    }

    private void sendSpeedTitle(Player player) {
        String speed = new DecimalFormat("#.#").format(calculatePlayerSpeed(player));
        player.showTitle(Title.title(Component.empty(), Component.text("Speed: " + speed + "KM/H"),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO)));
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
