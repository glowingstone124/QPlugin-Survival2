package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import org.bukkit.scheduler.BukkitRunnable;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class Ranking
{
    public Ranking() {
        enableRankingSchedule();
        QuantumPlugin.getInstance().getLogger().info("Ranking Enabled.");
    }
    public static  ConcurrentHashMap<String, Long> destroyMap = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, Long> placeMap = new ConcurrentHashMap<>();
    Gson gson = new Gson();
    BukkitRunnable syncTask = new BukkitRunnable()
    {
        @Override
        public void run()
        {
            if (placeMap.isEmpty() && destroyMap.isEmpty()) {
                return;
            }
            try {
                syncRankingMap(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/destroy/upload", destroyMap);
                syncRankingMap(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/place/upload", placeMap);
            } catch (Exception e) {
                QuantumPlugin.getInstance().getLogger().warning("Failed to sync block ranking data: " + e.getMessage());
            }
        }
    };
    public void enableRankingSchedule()
    {
        syncTask.runTaskTimerAsynchronously(QuantumPlugin.getInstance(), 0, 1200);
    }

    private void syncRankingMap(String url, ConcurrentHashMap<String, Long> rankingMap) throws Exception {
        if (rankingMap.isEmpty()) {
            return;
        }
        HashMap<String, Long> snapshot = new HashMap<>(rankingMap);
        Request.Response response = Request.sendPostRequestWithStatus(
                url,
                gson.toJson(snapshot),
                Optional.of(Map.of("Token", Config.INSTANCE.getAPI_SECRET()))
        ).get();
        if (response.status < HttpURLConnection.HTTP_OK || response.status >= HttpURLConnection.HTTP_MULT_CHOICE) {
            throw new IllegalStateException("ranking API returned HTTP " + response.status);
        }
        snapshot.forEach((player, uploadedAmount) ->
                rankingMap.computeIfPresent(player, (ignored, currentAmount) -> {
                    long remaining = currentAmount - uploadedAmount;
                    return remaining > 0 ? remaining : null;
                })
        );
    }
}
