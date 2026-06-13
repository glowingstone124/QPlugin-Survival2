package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
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
        if (event.getDamager() instanceof Arrow arrow)
        {
            if (arrow.getShooter() instanceof Player player)
            {
                if (!isIndicatorEnabled(player)) return;
                if (event.getEntity() instanceof LivingEntity livingEntity)
                {
                    double damage = event.getDamage();
                    player.sendMessage(Component.text(" -> "+ livingEntity.getName() +" "+ damage + " damage").color(TextColor.color(34,139,34)));
                    player.sendActionBar(Component.text(player.getName()+" -> "+ livingEntity.getName() +" 造成 "+ damage + " 点伤害").color(TextColor.color(34,139,34)));
                }
            }
        }
    }

}
