package vip.qoriginal.quantumplugin.metro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Minecart;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

public class SegmentMap {
    public static HashMap<String, Segment> segMap = new HashMap<>();
    public static HashMap<Integer, Line> lineMap = new HashMap<>();
    public static World ov = Bukkit.getWorld("world");
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Location.class, new LocationAdapter())
            .create();
    private static final String SEGMENTS_FILE = "segments.json";
    private static final String LINES_FILE = "lines.json";

    public static void init() {
        loadSegments();
        loadLines();
    }
    public static int getMinecartCountInSegment(String segmentId){
        Segment seg = segMap.get(segmentId);
        if(seg == null){
            return 0;
        }
        int count = 0;
        if (seg.occupied != null) {
            count++;
        }
        count += seg.queueing.size();
        return count;
    }
    public static HashMap<String, Integer> getMinecartCountInSegment() {
        HashMap<String, Integer> map = new HashMap<>();
        for (String segId : segMap.keySet()) {
            map.put(segId,getMinecartCountInSegment(segId));
        }
        return map;
    }
    private static void loadSegments() {
        try (FileReader reader = new FileReader(SEGMENTS_FILE)) {
            Type type = new TypeToken<HashMap<String, Segment>>() {}.getType();
            segMap = gson.fromJson(reader, type);

            for (Segment segment : segMap.values()) {
                if (segment.queueing == null) {
                    segment.queueing = new ArrayList<>();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadLines() {
        try (FileReader reader = new FileReader(LINES_FILE)) {
            Type type = new TypeToken<HashMap<Integer, Line>>() {}.getType();
            lineMap = gson.fromJson(reader, type);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean enter(String id, Minecart minecart) {
        return segMap.get(id).enter(minecart);
    }

    public static void leave(String id, Minecart minecart) {
        segMap.get(id).leave(minecart);
    }

    public static void refresh() {
        for (Minecart minecart : ov.getEntitiesByClass(Minecart.class)) {
            Set<String> tags = minecart.getScoreboardTags();
            for (String tag : tags) {
                if (tag.startsWith("queueing-") || tag.startsWith("occupied-")) {
                    minecart.removeScoreboardTag(tag);
                }
            }
        }

        segMap.forEach((key, value) -> {
            if (value.occupied != null) {
                value.occupied.addScoreboardTag("occupied-" + key);
            }
        });

        segMap.forEach((key, value) -> {
            if (value.occupied == null) {
                for (Location location : value.signal) {
                    if (location.getBlock().getType() == Material.GREEN_CONCRETE || location.getBlock().getType() == Material.RED_CONCRETE) {
                        location.getBlock().setType(Material.GREEN_CONCRETE);
                    }
                }
            } else {
                for (Location location : value.signal) {
                    if (location.getBlock().getType() == Material.GREEN_CONCRETE || location.getBlock().getType() == Material.RED_CONCRETE) {
                        location.getBlock().setType(Material.RED_CONCRETE);
                    }
                }
                if (value.occupied.isDead()) {
                    value.leave(value.occupied);
                }
            }

            for (Minecart minecart : value.queueing) {
                minecart.setVelocity(minecart.getVelocity().multiply(0.88));
                minecart.addScoreboardTag("queueing-" + key);
            }
        });
    }

    public static class Line {
        public String dummy;
        public String color;

        Line(String dummy, String color) {
            this.dummy = dummy;
            this.color = color;
        }
    }

    public static class Segment {
        public int lid;
        public boolean station;
        public String dummy;
        public Minecart occupied = null;
        public ArrayList<Minecart> queueing;
        public Location[] signal;

        Segment(int lid, boolean station, String dummy, Location[] signal) {
            this.lid = lid;
            this.station = station;
            this.dummy = dummy;
            this.signal = signal;
            this.queueing = new ArrayList<>();
        }

        public boolean enter(Minecart minecart) {
            if (minecart == occupied || queueing.contains(minecart)) {
                return false;
            }
            if (this.station && !minecart.getPassengers().isEmpty()) {
                minecart.getPassengers().get(0).sendMessage(Component.text(this.dummy)
                        .color(TextColor.fromCSSHexString(lineMap.get(this.lid).color))
                        .append(Component.text("到了。").color(TextColor.fromCSSHexString("#ffffff"))));
            }
            if (occupied == null || occupied.isDead()) {
                if (!minecart.getPassengers().isEmpty() && !this.station) {
                    minecart.getPassengers().get(0).sendMessage(
                            Component.text("您已")
                                    .append(Component.text("绿灯").color(TextColor.color(0, 255, 0)))
                                    .append(Component.text("进入"))
                                    .append(Component.text(this.dummy).color(TextColor.fromCSSHexString(lineMap.get(this.lid).color)))
                                    .append(Component.text("，祝您一路顺风。"))
                    );
                }
                occupied = minecart;
                return true;
            } else {
                if (!minecart.getPassengers().isEmpty() && !this.station) {
                    minecart.getPassengers().get(0).sendMessage(
                            Component.text("您已")
                                    .append(Component.text("红灯").color(TextColor.color(255, 0, 0)).decorate(TextDecoration.BOLD))
                                    .append(Component.text("进入"))
                                    .append(Component.text(this.dummy).color(TextColor.fromCSSHexString(lineMap.get(this.lid).color)))
                                    .append(Component.text("，限速运行。")).appendNewline()
                                    .append(Component.text("前车通过后限速将解除。"))
                    );
                }
                queueing.add(minecart);
                return false;
            }
        }

        public void leave(Minecart minecart) {
            if (minecart == occupied) {
                if (!queueing.isEmpty()) {
                    Minecart cart;
                    do {
                        cart = queueing.remove(0);
                    } while (cart.isDead());
                    occupied = cart;
                    if (!cart.getPassengers().isEmpty() && !this.station) {
                        cart.getPassengers().get(0).sendMessage("限速已解除。");
                    }
                } else {
                    occupied = null;
                }
            }
        }
    }
}
