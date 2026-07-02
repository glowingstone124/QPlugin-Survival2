package vip.qoriginal.quantumplugin.metro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import vip.qoriginal.quantumplugin.Config;
import vip.qoriginal.quantumplugin.Logger;
import vip.qoriginal.quantumplugin.LoggerProvider;
import vip.qoriginal.quantumplugin.Request;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class SegmentMap {
    public static final Logger logger = LoggerProvider.INSTANCE.getLogger("SegmentMap");
    public static final int cache_expiration = 60000;
    public static ConcurrentHashMap<String, String> minecart_status = new ConcurrentHashMap<>();

    public static void enter(String id, Minecart minecart) {
        String code = id + "E";
        String uuid = minecart.getUniqueId().toString();
        if(minecart_status.containsKey(uuid) && minecart_status.get(uuid).contentEquals(code)) return;
        JsonObject line = getLineInfo(id);
        if (line != null && line.has("stations")) {
            try {
                JsonObject station = line.getAsJsonArray("stations").get(Integer.parseInt(id, 16) & 255).getAsJsonObject();
                if(!minecart.getPassengers().isEmpty() && minecart.getPassengers().getFirst() instanceof Player) {
                    minecart.getPassengers().getFirst().sendMessage(
                            Component.text(station.get("name").getAsString())
                                    .append(Component.text("到了，请在停稳后从侧方下车。"))
                                    .append(getInterchangeMessage(station, line))
                    );
                }
                minecart_status.put(uuid, code);
            } catch (Exception e) {
                logger.log("Error processing minecart enter: " + id);
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                e.printStackTrace(pw);
                String exceptionAsString = sw.toString();
                logger.debug(exceptionAsString);
            }
        }
    }

    public static TextComponent getInterchangeMessage(JsonObject station, JsonObject line) {
        if (station.getAsJsonArray("transfer_lines").isEmpty()) return Component.empty();
        String type = line.get("type").getAsString();
        String dim = line.get("dimension").getAsString();
        //TODO finish it
        return Component.empty();
    }

    public static void leave(String id, Minecart minecart) {
        String code = id + "L";
        String uuid = minecart.getUniqueId().toString();
        if(minecart_status.containsKey(uuid) && minecart_status.get(uuid).contentEquals(code)) return;
        JsonObject line = getLineInfo(id);
        if (line != null && line.has("stations")) {
            try {
                JsonObject station = line.getAsJsonArray("stations").get((Integer.parseInt(id, 16) & 255) + 1).getAsJsonObject();
                if(!minecart.getPassengers().isEmpty() && minecart.getPassengers().getFirst() instanceof Player) {
                    minecart.getPassengers().getFirst().sendMessage(Component.text("欢迎您乘坐轨道交通")
                            .append(Component.text(line.get("name").getAsString().split("-")[0]+"。").color(TextColor.color(Integer.parseInt(line.get("color").getAsString().substring(1),16))))
                            .appendNewline().append(Component.text("本线终点站："))
                            .append(Component.text(line.getAsJsonArray("stations").get(line.getAsJsonArray("stations").size() - 1).getAsString()))
                            .append(Component.text("，下一站："+station.getAsJsonArray("name").getAsString()))
                    );
                }
                minecart_status.put(uuid, code);
            } catch (Exception e) {
                logger.log("Error processing minecart leave: " + id);
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                e.printStackTrace(pw);
                String exceptionAsString = sw.toString();
                logger.debug(exceptionAsString);
            }
        }
    }

    public static JsonObject getLineInfo(String id) {
        int int_id = Integer.parseInt(id,16);
        LineCache lineCache = LineCache.getLine(int_id >> 8);
        if(lineCache == null) return null;
        return lineCache.line_info;
    }

    private static class LineCache {
        JsonObject line_info;
        long update_time = 0;
        static ConcurrentHashMap<Integer, LineCache> line_cache = new ConcurrentHashMap<>();
        LineCache(JsonObject object) {
            this.line_info = object;
            this.update_time = System.currentTimeMillis();
        }
        static LineCache getLine(int id) {
            if (line_cache.get(id) != null && line_cache.get(id).update_time + cache_expiration > System.currentTimeMillis()) {
                return line_cache.get(id);
            }
            synchronized (LineCache.class) {
                if (line_cache.get(id) != null && line_cache.get(id).update_time + cache_expiration > System.currentTimeMillis()) {
                    return line_cache.get(id);
                }
                JsonObject relationship = null;
                try {
                    relationship = JsonParser.parseString(Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/transportation/line/detail?id=" + id).get()).getAsJsonObject();
                    JsonArray stations = relationship.getAsJsonArray("stations");
                    if (stations == null || stations.isEmpty()) return null;
                    for (JsonElement station_element : stations) {
                        JsonObject station = station_element.getAsJsonObject();
                        JsonArray transfers = station.getAsJsonArray("transfer_lines");
                        JsonArray transfers_new = new JsonArray();
                        HashSet<String> line_names = new HashSet<>();
                        line_names.add(relationship.getAsJsonObject("line").get("name").getAsString().split("-")[0]);
                        for (JsonElement content : transfers) {
                            String line_name = content.getAsJsonObject().get("name").getAsString().split("-")[0];
                            if (line_names.add(line_name)) {
                                JsonObject object = new JsonObject();
                                object.addProperty("name", line_name);
                                object.add("color", content.getAsJsonObject().get("color"));
                                transfers_new.add(object);
                            }
                        }
                        station.add("transfer_lines", transfers_new);
                    }
                    LineCache new_cache = new LineCache(relationship);
                    line_cache.put(id, new_cache);
                    return new_cache;
                } catch (Exception e) {
                    logger.log("failed to fetch station info:" + id);
                    return line_cache.get(id);
                }
            }
        }
    }
}
