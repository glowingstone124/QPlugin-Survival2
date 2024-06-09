package vip.qoriginal.quantumplugin.metro;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;

public class SegmentMap {
    public static HashMap<String, Segment> segMap = new HashMap<>();
    public static HashMap<Integer, Line> lineMap = new HashMap<>();
    public static World ov = Bukkit.getWorld("world");
    public static void init() {
        lineMap.put(0, new Line("C1 Monorail","#c73532"));

        segMap.put("0001",
                new Segment(0, false, "C1上/下行区段0001", new Location[]{
                        new Location(ov, -8, 69, 304),
                        new Location(ov, 343, 63, 187)
                }));
    }

    public static boolean enter(String id, Minecart minecart) {
        return segMap.get(id).enter(minecart);
    }
    public static void leave(String id, Minecart minecart) {
        segMap.get(id).leave(minecart);
    }

    public static void refresh() {
        for(Minecart minecart: ov.getEntitiesByClass(Minecart.class)) {
            for(String tag: minecart.getScoreboardTags()) {
                if(tag.indexOf("queueing-")==0 || tag.indexOf("occupied-")==0) minecart.removeScoreboardTag(tag);
            }
        }

        segMap.forEach((key, value) -> {
            if(value.occupied != null) value.occupied.addScoreboardTag("occupied-"+key);
        });
        segMap.forEach((key, value) -> {
            if(value.occupied == null) {
                for(Location location : value.signal) {
                    if(location.getBlock().getType() == Material.GREEN_CONCRETE || location.getBlock().getType() == Material.RED_CONCRETE) {
                        location.getBlock().setType(Material.GREEN_CONCRETE);
                    }
                }
            } else {
                for(Location location : value.signal) {
                    if(location.getBlock().getType() == Material.GREEN_CONCRETE || location.getBlock().getType() == Material.RED_CONCRETE) {
                        location.getBlock().setType(Material.RED_CONCRETE);
                    }
                }
            }

            for(Minecart minecart: value.queueing) {
                minecart.setVelocity(minecart.getVelocity().multiply(0.94));
                minecart.addScoreboardTag("queueing-"+key);
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
        public ArrayList<Minecart> queueing = new ArrayList<>();
        public Location[] signal;

        Segment(int lid, boolean station, String dummy, Location[] signal) {
            this.lid = lid;
            this.station = station;
            this.dummy = dummy;
            this.signal = signal;
        }

        public boolean enter(Minecart minecart) {
            if(occupied == null || occupied.isDead()) {
                occupied = minecart;
                return true;
            } else {
                queueing.addLast(occupied);
                return false;
            }
        }

        public void leave(Minecart minecart) {
            if(minecart == occupied) {
                if(!queueing.isEmpty()) {
                    Minecart cart;
                    do {
                        cart = queueing.removeFirst();
                    } while (cart.isDead());
                    occupied = cart;
                } else occupied = null;
            }
        }
    }
}
