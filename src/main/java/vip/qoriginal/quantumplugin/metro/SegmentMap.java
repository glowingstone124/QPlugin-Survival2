package vip.qoriginal.quantumplugin.metro;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class SegmentMap {
    public static HashMap<String, Segment> segMap = new HashMap<>();
    public static HashMap<Integer, Line> lineMap = new HashMap<>();
    public static World ov = Bukkit.getWorld("world");
    public static void init() {
        lineMap.put(0, new Line("C1 Monorail","#c73532"));
        lineMap.put(6, new Line("QO Line 3","#fcd600"));
        lineMap.put(7, new Line("QO Line 3","#fcd600"));

        segMap.put("0001",
                new Segment(0, false, "C1上/下行区段0001", new Location[]{
                        new Location(ov, -8, 69, 304),
                        new Location(ov, 343, 63, 187)
                }));
        segMap.put("0002", new Segment(6, true, "出生点", new Location[]{}));
        segMap.put("0003", new Segment(7, true, "出生点", new Location[]{}));
        segMap.put("0004", new Segment(6, true, "清水河", new Location[]{}));
        segMap.put("0005", new Segment(7, true, "清水河", new Location[]{}));
        segMap.put("0006", new Segment(6, true, "高铁出生点站", new Location[]{}));
        segMap.put("0007", new Segment(7, true, "高铁出生点站", new Location[]{}));
        segMap.put("0008",
                new Segment(6, false, "L3上行至清水河区段0008", new Location[]{
                        new Location(ov, -43, 84, 534),
                }));
        segMap.put("0009",
                new Segment(6, false, "L3上行至清水河区段0009", new Location[]{
                        new Location(ov, -8, 83, 430),
                }));
        segMap.put("000a",
                new Segment(6, false, "L3上行至出生点区段000a", new Location[]{
                        new Location(ov, -8, 84, 303),
                }));
        segMap.put("000b",
                new Segment(6, false, "L3上行至出生点区段000b", new Location[]{
                        new Location(ov, -8, 65, 146),
                }));
        segMap.put("000c",
                new Segment(7, false, "L3下行至清水河区段000c", new Location[]{
                        new Location(ov, -16, 65, 20),
                }));
        segMap.put("000d",
                new Segment(7, false, "L3下行至清水河区段000d", new Location[]{
                        new Location(ov, -18, 65, 146),
                }));
        segMap.put("000e",
                new Segment(7, false, "L3下行至清水河区段000e", new Location[]{
                        new Location(ov, -18, 84, 316),
                }));
        segMap.put("000f",
                new Segment(7, false, "L3下行至清水河区段000f", new Location[]{
                        new Location(ov, -18, 83, 427),
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
            Set<String> tags = minecart.getScoreboardTags();
            for(String tag: tags) {
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
                if(value.occupied.isDead()) {value.leave(value.occupied);}
            }

            for(Minecart minecart: value.queueing) {
                minecart.setVelocity(minecart.getVelocity().multiply(0.88));
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
            if(minecart == occupied || queueing.contains(minecart)) return false;
            if(this.station && !minecart.getPassengers().isEmpty()) minecart.getPassengers().getFirst().sendMessage(Component.text(this.dummy).color(
                            TextColor.fromCSSHexString(lineMap.get(this.lid).color))
                    .append(Component.text("到了。").color(TextColor.fromCSSHexString("#ffffff"))));
            if(occupied == null || occupied.isDead()) {
                if(!minecart.getPassengers().isEmpty() && !this.station) minecart.getPassengers().getFirst().sendMessage(
                        Component.text("您已").append(
                                Component.text("绿灯").color(TextColor.color(0,255,0)))
                                .append(Component.text("进入"))
                                .append(Component.text(this.dummy).color(
                                        TextColor.fromCSSHexString(lineMap.get(this.lid).color)))
                                .append(Component.text("，祝您一路顺风。")));
                occupied = minecart;
                return true;
            } else {
                if(!minecart.getPassengers().isEmpty()  && !this.station) minecart.getPassengers().getFirst().sendMessage(
                        Component.text("您已").append(
                                        Component.text("红灯").color(TextColor.color(255,0,0)).decorate(TextDecoration.BOLD))
                                .append(Component.text("进入"))
                                .append(Component.text(this.dummy).color(
                                        TextColor.fromCSSHexString(lineMap.get(this.lid).color)))
                                .append(Component.text("，限速运行。")).appendNewline()
                                .append(Component.text("前车通过后限速将解除。")));
                queueing.addLast(minecart);
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
                    if(!minecart.getPassengers().isEmpty()  && !this.station) cart.getPassengers().getFirst().sendMessage("限速已解除。");
                } else occupied = null;
            }
        }
    }
}
