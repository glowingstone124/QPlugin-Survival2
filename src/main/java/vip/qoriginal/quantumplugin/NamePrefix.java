package vip.qoriginal.quantumplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class NamePrefix implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String newName;
        switch (player.getName()){
            case "MineCreeper2086":
                newName = "§a[Master]§f" +  player.getName();
                break;
            case "glowingstone124":
                newName = "§a[ADMIN]§f" +  player.getName();
                break;
            default:
                newName = player.getName();
                break;
        }
        player.setDisplayName(newName);
    }
}
