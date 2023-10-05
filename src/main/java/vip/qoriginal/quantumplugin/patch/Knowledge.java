package vip.qoriginal.quantumplugin.patch;

import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

import javax.swing.text.Position;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class Knowledge implements Listener{
    public static ArrayList<ScheduledStrike> strikes = new ArrayList<>();
    public static class ScheduledStrike {
        public long time;
        public Location l;

        public ScheduledStrike(long time, Location l) {
            this.time = time;
            this.l = l;
        }
    }
    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if(event.getItemDrop().getItemStack().getEnchantmentLevel(Enchantment.CHANNELING)==10) {
            Location l = event.getItemDrop().getLocation();
            event.getItemDrop().remove();
            strikes.add(new ScheduledStrike(System.currentTimeMillis()+3000,l));
        }
    }
}
