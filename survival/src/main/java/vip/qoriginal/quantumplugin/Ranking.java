package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Ranking
{
    public Ranking() {
        initRankingMap();
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
            String serializedPlace = gson.toJson(placeMap);
            String serializedDestroy = gson.toJson(destroyMap);
            try {
                Request.sendPostRequest(
                        Config.INSTANCE.getAPI_ENDPOINT() + "/qo/destroy/upload",
                        serializedDestroy
                ).get();
                Request.sendPostRequest(
                        Config.INSTANCE.getAPI_ENDPOINT() +"/qo/place/upload",
                        serializedPlace
                ).get();
                destroyMap.clear();
                placeMap.clear();
            } catch (Exception e) {
                QuantumPlugin.getInstance().getLogger().warning("Failed to sync block ranking data: " + e.getMessage());
            }
        }
    };
    public void initRankingMap() {
        String placeUrl = Config.INSTANCE.getAPI_ENDPOINT() +"/qo/place/download";
        String destroyUrl =Config.INSTANCE.getAPI_ENDPOINT() + "/qo/destroy/download";

        try {
            String placeResponse = Request.sendGetRequest(placeUrl).get();
            String destroyResponse = Request.sendGetRequest(destroyUrl).get();

            Type placeType = new TypeToken<Map<String, Long>>(){}.getType();
            Type destroyType = new TypeToken<Map<String, Long>>(){}.getType();
            Map<String, Long> placeData = parseRankingResponse(placeResponse, placeType);
            Map<String, Long> destroyData = parseRankingResponse(destroyResponse, destroyType);

            placeMap.putAll(placeData);
            destroyMap.putAll(destroyData);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void enableRankingSchedule()
    {
        syncTask.runTaskTimerAsynchronously(QuantumPlugin.getInstance(), 0, 1200);
    }

    private Map<String, Long> parseRankingResponse(String response, Type type) {
        if (response == null || response.isBlank()) {
            return Map.of();
        }
        Map<String, Long> data = gson.fromJson(response, type);
        return data == null ? Map.of() : data;
    }
}
