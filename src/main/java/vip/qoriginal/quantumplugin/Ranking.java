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
            String serializedPlace = gson.toJson(placeMap);
            String serializedDestroy = gson.toJson(destroyMap);
            try {
                Request.sendPostRequest(
                        "http://172.19.0.6:8080/qo/destroy/upload/",
                        serializedDestroy
                );
                Request.sendPostRequest(
                        "http://172.19.0.6:8080/qo/place/upload/",
                        serializedPlace
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            destroyMap.clear();
            placeMap.clear();
        }
    };
    public void initRankingMap() {
        String placeUrl = "http://172.19.0.6:8080/qo/place/download";
        String destroyUrl = "http://172.19.0.6:8080/qo/destroy/download/";

        try {
            String placeResponse = Request.sendGetRequest(placeUrl).get();
            String destroyResponse = Request.sendGetRequest(destroyUrl).get();

            Type placeType = new TypeToken<Map<String, Long>>(){}.getType();
            Type destroyType = new TypeToken<Map<String, Long>>(){}.getType();
            Map<String, Long> placeData = gson.fromJson(placeResponse, placeType);
            Map<String, Long> destroyData = gson.fromJson(destroyResponse, destroyType);

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
}
