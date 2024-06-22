package vip.qoriginal.quantumplugin.metro;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.Material;


public class Speed implements Listener{

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMinecartMove(VehicleMoveEvent event) {
        Location f = event.getFrom();
        Location t = event.getTo();
        Vector calc;
        int tryTimes = 0;
        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();
            Material blockTypeBelow = minecart.getLocation().subtract(0, 1, 0).getBlock().getType();
            if (minecart.getScoreboardTags().contains("accel")) {
                if (blockTypeBelow == Material.SMOOTH_STONE) { //普刹车
                    calc = boost(minecart, .4d, .005, f, t);
                } else if (blockTypeBelow == Material.SMOOTH_STONE_SLAB) { //急刹车
                    calc = boost(minecart, .1d, .02, f, t);
                } else if (minecart.getScoreboardTags().contains("curve")){ //弯道减速
                    boolean flag = false;
                    if (minecart.getLocation().getBlock().getBlockData() instanceof Rail) {
                        Rail rail = (Rail) minecart.getLocation().getBlock().getBlockData();
                        Rail.Shape shape = rail.getShape();
                        if(shape == Rail.Shape.NORTH_EAST || shape == Rail.Shape.NORTH_WEST ||
                                shape == Rail.Shape.SOUTH_EAST || shape == Rail.Shape.SOUTH_WEST) {
                            flag = true;
                        }
                    }
                    calc = boost(minecart, flag?0.5:0.72, flag?.015:.0008, f, t);
                    minecart.setMaxSpeed(flag?0.6:0.72);
                } else {
                    calc = boost(minecart, minecart.getScoreboardTags().contains("cr200j") ? 1.6d : 0.9d, .005, f, t);
                }
                if(calc!=null) {
                    minecart.teleport(event.getFrom().add(calc));
                    minecart.setVelocity(calc);
                }
            }
        }
    }
    private Vector boost(Minecart minecart, double ts, double a, Location from, Location to) {
        Vector currentVelocity = to.toVector().subtract(from.toVector());
        double cs = currentVelocity.length();
        for (String str: minecart.getScoreboardTags()) {
            if (str.indexOf("queueing-") == 0) {
                ts = 0;
                a = -0.1;
                break;
            }
        }
        if (cs > 0 && minecart.getLocation().getBlock().isBlockPowered() && !minecart.getScoreboardTags().contains("queueing")) {
            // ns = Computed Next Speed
            double ns = ts>cs?Math.min(cs+a,cs*.7+ts*.3):Math.max(cs-a,cs*.7+ts*.3);
            double factor = ns / cs;
            Vector newVelocity = currentVelocity.multiply(factor);
            return newVelocity;
        }
        return null;
    }

    private String vecToStr(Location l) {
        return (float)Math.round(l.getX()*100)/100f+","+(float)Math.round(l.getY()*100)/100f+","+(float)Math.round(l.getZ()*100)/100f+",";
    }
}