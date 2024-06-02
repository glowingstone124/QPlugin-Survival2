package vip.qoriginal.quantumplugin.metro;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.Material;


public class Speed implements Listener{


    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();
            Material blockTypeBelow = minecart.getLocation().subtract(0, 1, 0).getBlock().getType();
            if (minecart.getScoreboardTags().contains("accel")) {
                if (blockTypeBelow == Material.SMOOTH_STONE) { //普刹车
                    boost(minecart, .4d, .005);
                } else if (blockTypeBelow == Material.SMOOTH_STONE_SLAB) { //急刹车
                    boost(minecart, .1d, .02);
                } else if (minecart.getScoreboardTags().contains("curve")){ //弯道减速
                    Vector res = boost(minecart, 1d, .015);
                    if(res!=null) {
                        int tryTimes = 1;
                        Location nextTick = minecart.getLocation().add(res);
                        Material blockNextTick = nextTick.getBlock().getType();
                        while (blockNextTick != Material.RAIL && blockNextTick != Material.POWERED_RAIL && tryTimes < 629) {
                            res.rotateAroundY(Math.pow(-1,tryTimes) * tryTimes);
                            res.multiply(0.99);
                            nextTick = minecart.getLocation().add(res);
                            blockNextTick = nextTick.getBlock().getType();
                            tryTimes ++;
                        }
                    }
                } else {
                    boost(minecart, minecart.getScoreboardTags().contains("cr200j") ? 1.5d : 0.9d, .005);
                }
            }
        }
    }
    private Vector boost(Minecart minecart, double ts, double a) {
        if (minecart.getLocation().getBlock().isBlockPowered()) {

            Vector currentVelocity = minecart.getVelocity();
            double cs = currentVelocity.length();

            if (cs > 0) {
                // ns = Computed Next Speed
                double ns = ts>cs?Math.min(cs+a,cs*.7+ts*.3):Math.max(cs-a,cs*.7+ts*.3);
                double factor = ns / cs;
                Vector newVelocity = currentVelocity.multiply(factor);
                minecart.setVelocity(newVelocity);
                return newVelocity;
            }
        }
        return null;
    }
}