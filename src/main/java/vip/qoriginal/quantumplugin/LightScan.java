package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class LightScan {
    public static void scan(Player p, int[] st, int[] en){
        float status = 0;
        String finalstring = "";
        for(int x=st[0];x<=en[0];x++) {
            for(int z=st[1];z<=en[1];z++) {
                int light = 0;
                for(int y=0;y<255;y++) {
                    Block block = Bukkit.getWorld("world").getBlockAt(x,y,z);
                    if (block.getType() == Material.TORCH || block.getType() == Material.SEA_LANTERN ||
                            block.getType() == Material.LANTERN || block.getType() == Material.REDSTONE_TORCH) {
                        p.sendMessage(x+" "+y+" "+z);
                        light++;
                    }
                }
                light = Math.min(9,light);
            }
            status += 1f/4.8f;
            p.sendActionBar(Component.text("扫描中，进度"+(float)Math.round(status)/10f+"%"));
        }
    }
}
