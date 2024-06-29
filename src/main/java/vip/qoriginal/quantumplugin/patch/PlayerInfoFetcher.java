package vip.qoriginal.quantumplugin.patch;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public class PlayerInfoFetcher {
    public Player getPlayer(String name){
        OfflinePlayer player = Bukkit.getOfflinePlayer(name);
        if(player != null){
            return new Player(player.getName(),player.getUniqueId().toString(),player.hasPlayedBefore(),player.isBanned(),player.isOnline());
        }
        return null;
    }
    public static class Player{
        String name;
        String UUID;
        boolean hasPlayedBefore;
        boolean isBanned;
        boolean isOnline;
        public Player(String name, String UUID, boolean hasPlayedBefore, boolean isBanned, boolean isOnline){
            this.name = name;
            this.UUID = UUID;
            this.hasPlayedBefore = hasPlayedBefore;
            this.isBanned = isBanned;
            this.isOnline = isOnline;
        }
    }
}
