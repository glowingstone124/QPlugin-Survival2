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
import java.util.function.Predicate;

public class SpeedMonitor implements Listener {

    private static final double TICKS_PER_SECOND = 20.0;
    private static final double BLOCKS_PER_SECOND_TO_KMH = 3.6;
    private static final int SPEED_INSPECT_INTERVAL_TICKS = 20;

    private static final int ACTION_BAR_INTERVAL_TICKS = 2;

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

    public SpeedMonitor(Plugin plugin) {
        this(
                plugin,
                vehicle -> vehicle instanceof HappyGhast,
                vehicle -> false
        );
    }

    public SpeedMonitor(
            Plugin plugin,
            Predicate<Entity> ignoredVehicles
    ) {
        this(
                plugin,
                ignoredVehicles,
                vehicle -> false
        );
    }

    public SpeedMonitor(
            Plugin plugin,
            Predicate<Entity> ignoredVehicles,
            Predicate<Entity> experimentalAccelerationVehicles
    ) {
        this.plugin = plugin;
        this.ignoredVehicles = ignoredVehicles;
        this.experimentalAccelerationVehicles =
                experimentalAccelerationVehicles;
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        Entity vehicle = event.getVehicle();

        if (ignoredVehicles.test(vehicle)) {
            return;
        }

        if (!(event.getEntered() instanceof Player player)) {
            return;
        }

        Component actionBar = getVehicleActionBar(
                vehicle,
                player,
                event
        );

        new BukkitRunnable() {

            private int elapsedTicks = 0;

            private Location previousLocation =
                    vehicle.getLocation().clone();
            private double accumulatedDistance = 0.0;

            private int sampledTicks = 0;

            @Override
            public void run() {
                if (!player.isInsideVehicle()
                        || player.getVehicle() != vehicle
                        || !vehicle.isValid()) {
                    cancel();
                    return;
                }

                sampleVehicleMovement(vehicle);

                updateActionBar();
                if (elapsedTicks > 0
                        && elapsedTicks
                        % SPEED_INSPECT_INTERVAL_TICKS == 0) {
                    inspectSpeed();
                }

                elapsedTicks++;
            }
            private void sampleVehicleMovement(Entity vehicle) {
                Location currentLocation =
                        vehicle.getLocation();

                if (previousLocation.getWorld()
                        != currentLocation.getWorld()) {
                    previousLocation =
                            currentLocation.clone();

                    accumulatedDistance = 0.0;
                    sampledTicks = 0;

                    return;
                }

                double deltaX =
                        currentLocation.getX()
                                - previousLocation.getX();

                double deltaY =
                        currentLocation.getY()
                                - previousLocation.getY();

                double deltaZ =
                        currentLocation.getZ()
                                - previousLocation.getZ();

                double distance = Math.sqrt(
                        deltaX * deltaX
                                + deltaY * deltaY
                                + deltaZ * deltaZ
                );

                accumulatedDistance += distance;
                sampledTicks++;

                previousLocation =
                        currentLocation.clone();
            }

            private void updateActionBar() {
                if (elapsedTicks
                        % ACTION_BAR_INTERVAL_TICKS != 0) {
                    return;
                }

                if (vehicle instanceof Minecart
                        && elapsedTicks
                        < MINECART_ANIMATION_TICKS) {

                    player.sendActionBar(
                            genMinecartAnimation(
                                    elapsedTicks
                                            / MINECART_ANIMATION_FRAME_TICKS
                            )
                    );

                    return;
                }

                player.sendActionBar(actionBar);
            }

            private void inspectSpeed() {
                if (sampledTicks <= 0) {
                    sendSpeedTitle(player, 0.0);
                    resetSpeedSamples();
                    return;
                }

                double elapsedSeconds =
                        sampledTicks / TICKS_PER_SECOND;
                double blocksPerSecond =
                        accumulatedDistance
                                / elapsedSeconds;

                double kmh =
                        blocksPerSecond
                                * BLOCKS_PER_SECOND_TO_KMH;

                sendSpeedTitle(player, kmh);

                resetSpeedSamples();
            }

            private void resetSpeedSamples() {
                accumulatedDistance = 0.0;
                sampledTicks = 0;
            }

        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Component getVehicleActionBar(
            Entity vehicle,
            Player player,
            VehicleEnterEvent event
    ) {
        if (vehicle instanceof Boat) {
            return Component.text(
                    "QO交通委提醒您，行船不规范，亲人两行泪。 欢迎您，高级驾驶员 "
                            + player.getName(),
                    NamedTextColor.GREEN
            );
        }

        if (vehicle instanceof Minecart) {
            return genMinecartMsg(event);
        }

        return Component.empty();
    }

    private Component genMinecartAnimation(int frame) {
        return Component.text(
                MINECART_ANIMATION_FRAMES[
                        Math.min(
                                frame,
                                MINECART_ANIMATION_FRAMES.length - 1
                        )
                        ],
                NamedTextColor.AQUA
        );
    }

    private Component genMinecartMsg(
            VehicleEnterEvent event
    ) {
        if (experimentalAccelerationVehicles.test(
                event.getVehicle()
        )) {
            return Component.text(
                    "感谢您选择UHR轨道交通系统。",
                    NamedTextColor.AQUA
            );
        }

        return Component.text(
                "感谢您选择QO铁路，QO高速铁路现已全面普及108km/h高速 ",
                NamedTextColor.GREEN
        );
    }

    private void sendSpeedTitle(
            Player player,
            double playerSpeed
    ) {
        String speed =
                new DecimalFormat("#.#")
                        .format(playerSpeed);

        player.showTitle(
                Title.title(
                        Component.empty(),
                        Component.text(
                                "Speed: "
                                        + speed
                                        + " KM/H"
                        ),
                        Title.Times.times(
                                Duration.ZERO,
                                Duration.ofSeconds(1),
                                Duration.ZERO
                        )
                )
        );
    }

    @EventHandler
    public void onVehicleExit(
            VehicleExitEvent event
    ) {
        if (event.getExited() instanceof Player player) {
            player.clearTitle();
        }
    }
}