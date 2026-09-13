package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import vip.qoriginal.quantumplugin.fakeplayer.FakePlayerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uploads absolute Minecraft statistics, so retried snapshots never double-count totals. */
public final class PlayerStatisticsReporter {
    private static final long TICKS_PER_SECOND = 20L;
    private static final long UPLOAD_PERIOD_TICKS = 1200L;

    private final JavaPlugin plugin;
    private final Gson gson = new Gson();
    private final NamespacedKey elytraFlightTicksKey;
    private final AtomicBoolean uploadInFlight = new AtomicBoolean(false);
    private BukkitTask task;

    public PlayerStatisticsReporter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.elytraFlightTicksKey = new NamespacedKey(plugin, "player_statistics_elytra_flight_ticks");
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::captureAndUpload, TICKS_PER_SECOND, UPLOAD_PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        captureAndUpload();
    }

    private void captureAndUpload() {
        tickElytraTimers();
        if (!uploadInFlight.compareAndSet(false, true)) return;

        List<PlayerStatisticsSnapshot> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (FakePlayerManager.isFakePlayer(player) || player.getScoreboardTags().contains("visitor_login")) continue;
            players.add(snapshot(player));
        }
        if (players.isEmpty()) {
            uploadInFlight.set(false);
            return;
        }

        String payload = gson.toJson(new UploadRequest(players));
        Request.sendPostRequestWithStatus(
                Config.INSTANCE.getAPI_ENDPOINT() + "/qo/player-statistics/upload",
                payload,
                Optional.of(Map.of("Token", Config.INSTANCE.getAPI_SECRET()))
        ).whenComplete((response, error) -> {
            if (error != null || response == null || response.status < 200 || response.status >= 300) {
                String detail = error == null ? "HTTP " + (response == null ? "unknown" : response.status) : error.getMessage();
                plugin.getLogger().warning("Failed to upload player statistics: " + detail);
            }
            uploadInFlight.set(false);
        });
    }

    private void tickElytraTimers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isGliding()) continue;
            PersistentDataContainer data = player.getPersistentDataContainer();
            long current = data.getOrDefault(elytraFlightTicksKey, PersistentDataType.LONG, 0L);
            data.set(elytraFlightTicksKey, PersistentDataType.LONG, current + TICKS_PER_SECOND);
        }
    }

    private PlayerStatisticsSnapshot snapshot(Player player) {
        return new PlayerStatisticsSnapshot(
                player.getName(),
                totalMovementCm(player),
                statistic(player, Statistic.DAMAGE_DEALT),
                statistic(player, Statistic.MOB_KILLS),
                player.getPersistentDataContainer().getOrDefault(elytraFlightTicksKey, PersistentDataType.LONG, 0L)
        );
    }

    private long totalMovementCm(Player player) {
        long total = 0L;
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED && statistic.name().endsWith("_ONE_CM")) {
                total += statistic(player, statistic);
            }
        }
        return total;
    }

    private long statistic(Player player, Statistic statistic) {
        return Math.max(0, player.getStatistic(statistic));
    }

    private record UploadRequest(List<PlayerStatisticsSnapshot> players) {}

    private record PlayerStatisticsSnapshot(
            String name,
            long distanceCm,
            long damageDealt,
            long mobKills,
            long elytraFlightTicks
    ) {}
}
