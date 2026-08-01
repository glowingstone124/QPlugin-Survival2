package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public class StatusUpload implements Runnable {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LoggerProvider.INSTANCE.getLogger("StatusUpload");
    private static final List<Long> timings = new CopyOnWriteArrayList<>();
    private static volatile Predicate<Player> playerFilter = player -> true;

    public static void setPlayerFilter(Predicate<Player> filter) {
        playerFilter = filter == null ? player -> true : filter;
    }

    @Override
    public void run() {
        long started = System.currentTimeMillis();
        try {
            upload();
        } finally {
            timings.add(System.currentTimeMillis() - started);
            if (timings.size() >= 50) {
                LOGGER.debug(timings.toString());
                timings.clear();
            }
        }
    }

    private void upload() {
        StatusSample status = new StatusSample();
        status.timestamp = System.currentTimeMillis();
        status.totalcount = Bukkit.getOfflinePlayers().length;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!playerFilter.test(player)) continue;
            status.onlinecount++;
            status.players.add(new BriefPlayerInfo(player));
        }
        status.mspt = Float.isNaN(MSPTCalculator.mspt) ? 0 : MSPTCalculator.mspt;
        status.recent60 = MSPTCalculator.getRecent60t();
        status.mspt_3s = MSPTCalculator.getR3s();
        status.tick_time = Bukkit.getServer().getTickTimes();
        World world = Bukkit.getWorld("world");
        status.game_time = world == null ? 0 : world.getGameTime();
        try {
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/upload/status", GSON.toJson(status),
                    Optional.of(Map.of("Authorization", Config.INSTANCE.getAPI_SECRET())));
        } catch (Exception error) {
            Bukkit.getLogger().warning("Failed to upload server status: " + error.getMessage());
        }
    }

    public static final class StatusSample {
        int onlinecount;
        int totalcount;
        ArrayList<BriefPlayerInfo> players = new ArrayList<>();
        long timestamp;
        ArrayList<Float> recent60 = new ArrayList<>();
        float mspt;
        float mspt_3s;
        long[] tick_time;
        long game_time;
    }

    public static final class BriefPlayerInfo {
        String name;
        int ping;
        double health;
        String world;
        int x;
        int y;
        int z;

        BriefPlayerInfo(Player player) {
            name = player.getName();
            ping = player.getPing();
            health = player.getHealth();
            world = player.getWorld().getName();
            x = player.getLocation().getBlockX();
            y = player.getLocation().getBlockY();
            z = player.getLocation().getBlockZ();
        }
    }
}
