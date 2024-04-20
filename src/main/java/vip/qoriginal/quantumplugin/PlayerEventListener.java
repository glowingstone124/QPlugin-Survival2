package vip.qoriginal.quantumplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerEventListener implements Listener {
    ChatSync cs = new ChatSync();
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        cs.sendChatMsg("玩家" + player.getName() + "死了，" + event.getDeathMessage());
    }
}
