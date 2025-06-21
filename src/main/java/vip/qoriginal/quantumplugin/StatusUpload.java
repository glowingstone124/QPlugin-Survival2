package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import kotlinx.coroutines.Dispatchers;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.util.*;

public class StatusUpload {
    public static final Map<String, String> header = new HashMap<>();
    public static final List<Long> time = new ArrayList<>();
    private static final CoroutineJava cj = new CoroutineJava();
    private static final Logger logger = new Logger();
    private static final Gson gson = new Gson();
    private static final boolean DEBUG = true;

    static {
        header.put("Authorization", AuthUtils.INSTANCE.getToken());
    }

    public static int totalUser = 0;

    public void run() {
        long starttime = System.currentTimeMillis();
        if (DEBUG) {
            cj.run(() -> {
                if (time.size() >= 50) {
                    logger.log(time.toString(), "LoggerHealth");
                    time.clear();
                }
                return null;
            }, Dispatchers.getIO());
        }
        actualLogic();
        if (DEBUG) {
            long cost = System.currentTimeMillis() - starttime;
            time.add(cost);
        }
    }

    private void actualLogic() {
        StatusSample status = new StatusSample();
        status.timestamp = System.currentTimeMillis();
        status.onlinecount = Bukkit.getOnlinePlayers().size();
        for (Player p : Bukkit.getOnlinePlayers()) {
            BriefPlayerInfo info = new BriefPlayerInfo();
            info.ping = p.getPing();
            info.world = p.getWorld().getName();
            info.x = p.getLocation().getBlockX();
            info.y = p.getLocation().getBlockY();
            info.z = p.getLocation().getBlockZ();
            info.health = p.getHealth();
            info.name = p.getName();
            status.players.add(info);
        }
        status.totalcount = totalUser;
        status.mspt = Float.isNaN(MSPTCalculator.mspt) ? 0 : MSPTCalculator.mspt;
        status.recent60 = MSPTCalculator.getRecent60t();
        //getR3S will clear msptList so invoke recent60 before R3S.
        float mspt_3s = MSPTCalculator.getR3s();
        status.mspt_3s = Float.isNaN(mspt_3s) ? 0 : mspt_3s;
        String data = gson.toJson(status);
        status.tick_time = Bukkit.getServer().getTickTimes();
        status.game_time = Objects.requireNonNull(Bukkit.getServer().getWorld("world")).getGameTime();
        try {
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/upload/status", data, Optional.of(header));
        } catch (Exception e) {
            Bukkit.getLogger().warning("Experienced an exception" + e + " (on network?) while uploading status.\nIf the problem persists, please tell MineCreeper2086 to check if the target host is down.");
        }
    }
    public static class DetailedStatus {
        int onlinecount = 0;
        ArrayList<BriefPlayerInfo> players = new ArrayList<>();
        float tps = 0;
        float mspt = 0;
        int cload = 0;
        String format = "text";
        long[] tick_time;
        long game_time = 0;
    }

    public static class StatusSample {
        int onlinecount = 0;
        int totalcount = 0;
        ArrayList<BriefPlayerInfo> players = new ArrayList<>();
        long timestamp = 0;
        ArrayList<Float> recent60 = new ArrayList<>();
        Statistics total = null;
        float mspt = 0;
        float mspt_3s = 0;
        long[] tick_time;
        long game_time = 0;
    }

    public static class Statistics {
        long distance = 0;
        int place_torch = 0;
        int place_lantern = 0;
        long game_time = 0;
        int coal_mined = 0;
        int iron_mined = 0;
        int copper_mined = 0;
        int gold_mined = 0;
        int lapis_mined = 0;
        int emerald_mined = 0;
        int redstone_mined = 0;
        int diamond_mined = 0;
        int quartz_mined = 0;
        int netherite_mined = 0;
        int damage = 0;
        int deaths = 0;
    }

    public static class PlayerStat {
        String name = "";
        Statistics stats = new Statistics();
    }

    public static class BriefPlayerInfo {
        String name = "";
        int ping = 0;
        double health = 0;
        String world = "";
        int x = 0;
        int y = 0;
        int z = 0;
    }
}
