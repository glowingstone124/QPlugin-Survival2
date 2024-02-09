package vip.qoriginal.quantumplugin.metro;

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
            Material blockTypeBelow = minecart.getLocation().subtract(0, 1, 0). getBlock().getType();
            if (minecart.getScoreboardTags().contains("accel")) {
                if (blockTypeBelow == Material.SMOOTH_STONE) {
                    boost(minecart, .4d);
                } else {
                    boost(minecart, minecart.getScoreboardTags().contains("cr200j")?2.23d:1.5d);
                }
            }
        }
    }
    private void boost(Minecart minecart, double ts) {
        if (minecart.getLocation().getBlock().isBlockPowered()) {

            Vector currentVelocity = minecart.getVelocity();
            double cs = currentVelocity.length();

            if (cs > 0) {
                // ns = Computed Next Speed
                double ns = ts>cs?Math.min(cs+.01,cs*.7+ts*.3):Math.max(cs-.01,cs*.7+ts*.3);
                double factor = ns / cs;
                Vector newVelocity = currentVelocity.multiply(factor);
                minecart.setVelocity(newVelocity);
            }
        }
    }
}