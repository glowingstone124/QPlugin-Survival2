package vip.qoriginal.quantumplugin.industry;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

public class BoneMealFlowery implements Listener {
    static long[] recycle = new long[]{0,0,0,0,0,0,0,0,0,0};
    public static float recycle_efficiency = 0;
    static long[] produce = new long[]{0,0,0,0,0,0,0,0,0,0};
    public static float produce_efficiency = 0;
    @EventHandler
    public void onMoveItem(InventoryMoveItemEvent event) {
        if(event.getSource().getLocation()!=null) {
            Location l = event.getSource().getLocation();
            if(l.getBlockZ()==688&&l.getBlockY()==67) {
                if(l.getBlockX()<=-1694&&l.getBlockX()>=-1698) {
                    for(int i=8;i>=0;i--) produce[i+1] = produce[i];
                    produce[0] = System.currentTimeMillis();
                    produce_efficiency = 10000f/(float) (produce[0]-produce[9]);
                }
                if(l.getBlockX()<=-1702&&l.getBlockX()>=-1702) {
                    for(int i=8;i>=0;i--) recycle[i+1] = recycle[i];
                    recycle[0] = System.currentTimeMillis();
                    recycle_efficiency = 10000f/(float) (recycle[0]-recycle[9]);
                }
            }
        }
    }

    public static void update() {
        produce_efficiency = 10000f/(float) (System.currentTimeMillis()-produce[9]);
        recycle_efficiency = 10000f/(float) (System.currentTimeMillis()-recycle[9]);
    }
}
