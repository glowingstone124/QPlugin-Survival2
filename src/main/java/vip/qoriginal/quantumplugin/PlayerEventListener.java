package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;

import static vip.qoriginal.quantumplugin.QuantumPlugin.isIndicatorEnabled;

public class PlayerEventListener implements Listener {
    ChatSync cs = new ChatSync();

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        cs.sendChatMsg("玩家" + player.getName() + "死了，" + event.getDeathMessage());
    }

    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        int oldLevel = player.getLevel();
        int totalExp = getTotalExperience(player) + event.getAmount();

        int newLevel = 0;
        while (totalExp >= getExpToLevel(newLevel)) {
            totalExp -= getExpToLevel(newLevel);
            newLevel++;
        }
        if (newLevel > oldLevel && newLevel >= 100) {
            cs.sendChatMsg("玩家 " + player.getName() + " 的等级已经超过了100级，现在等级为：" + newLevel);
        }

    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow)) {
            return;
        }
        Arrow arrow = (Arrow) event.getEntity();
        if (arrow.getShooter() instanceof Player player) {
            Entity hitEntity = event.getHitEntity();
            if (!isIndicatorEnabled(player)) return;
            if (hitEntity != null) {
                player.sendMessage("Hit!");
                player.sendActionBar(Component.text("Hit!").color(TextColor.color(34,139,34)));
            } else {
                player.sendMessage("Miss!");
                player.sendActionBar(Component.text("Miss!").color(TextColor.color(139, 133, 42)));
            }
        }
    }
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Arrow)
        {
            Arrow arrow = (Arrow) event.getDamager();
            if (arrow.getShooter() instanceof Player)
            {
                Player player = (Player) arrow.getShooter();
                if (!isIndicatorEnabled(player)) return;
                if (event.getEntity() instanceof LivingEntity)
                {
                    LivingEntity livingEntity = (LivingEntity) event.getEntity();
                    double damage = event.getDamage();
                    player.sendMessage(Component.text(" -> "+ livingEntity.getName() +" "+ damage + " damage").color(TextColor.color(34,139,34)));
                    player.sendActionBar(Component.text(player.getName()+" -> "+ livingEntity.getName() +" 造成 "+ damage + " 点伤害").color(TextColor.color(34,139,34)));
                }
            }
        }
    }

    private int getExpToLevel(int level) {
        if (level >= 31) {
            return 62 + (level - 31) * 7;
        } else if (level >= 16) {
            return 17 + (level - 16) * 3;
        } else {
            return 7 + level * 2;
        }
    }

    private int getTotalExperience(Player player) {
        int level = player.getLevel();
        int totalExp = 0;
        for (int i = 0; i < level; i++) {
            totalExp += getExpToLevel(i);
        }
        totalExp += player.getExp() * getExpToLevel(level);
        return totalExp;
    }
}
