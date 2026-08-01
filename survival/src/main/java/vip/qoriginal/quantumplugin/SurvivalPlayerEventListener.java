package vip.qoriginal.quantumplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;

import static vip.qoriginal.quantumplugin.Ranking.destroyMap;
import static vip.qoriginal.quantumplugin.Ranking.placeMap;

public final class SurvivalPlayerEventListener implements Listener {
    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        int total = totalExperience(event.getPlayer().getLevel(), event.getPlayer().getExp()) + event.getAmount();
        int level = 0;
        while (total >= expToLevel(level)) total -= expToLevel(level++);
        int uploadedLevel = level;
        Thread.startVirtualThread(() -> {
            try {
                Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/upload/explevel?token=" + Config.INSTANCE.getAPI_SECRET()
                        + "&username=" + event.getPlayer().getName() + "&lvl=" + uploadedLevel, "");
            } catch (Exception error) {
                PluginContext.getPlugin().getLogger().warning("Failed to upload experience level: " + error.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) { destroyMap.merge(event.getPlayer().getName(), 1L, Long::sum); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) { placeMap.merge(event.getPlayer().getName(), 1L, Long::sum); }

    private static int expToLevel(int level) {
        if (level >= 31) return 62 + (level - 31) * 7;
        if (level >= 16) return 17 + (level - 16) * 3;
        return 7 + level * 2;
    }

    private static int totalExperience(int level, float progress) {
        int total = 0;
        for (int i = 0; i < level; i++) total += expToLevel(i);
        return total + (int) (progress * expToLevel(level));
    }
}
