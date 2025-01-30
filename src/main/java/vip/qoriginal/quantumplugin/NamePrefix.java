package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class NamePrefix implements Listener {
    private List<String> sponsorsList = new ArrayList<>();
    Timer timer = new Timer();
    public NamePrefix() {
        QuantumPlugin.getInstance().getLogger().info("NamePrefix now up and running.");
        timer.schedule(update, 0L, 1000 * 60L);
    }
    TimerTask update = new TimerTask() {
        @Override
        public void run() {
            try {
                String result = Files.readString(Path.of("sponsors.txt"));
                sponsorsList = List.of(result.split("\n"));
            } catch (IOException e) {
                return;
            }
        }
    };
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String newName;

        if (sponsorsList.contains(player.getName())) {
            newName = "§b[SPONSOR]§f" + player.getName();
        } else {
            switch (player.getName()) {
                case "MineCreeper2086":
                    newName = "§a[Master]§f" + player.getName();
                    break;
                case "glowingstone124":
                    newName = "§a[ADMIN]§f" + player.getName();
                    break;
                default:
                    newName = player.getName();
                    break;
            }
        }

        player.displayName(Component.text(newName));
    }

}
