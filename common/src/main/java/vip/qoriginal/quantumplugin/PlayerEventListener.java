package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;

import java.util.function.Consumer;

public class PlayerEventListener implements Listener {
    private static volatile Consumer<String> messageSink = message -> {};

    public static void setMessageSink(Consumer<String> sink) {
        messageSink = sink == null ? message -> {} : sink;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Component death = event.deathMessage();
        String detail = death == null ? "" : PlainTextComponentSerializer.plainText().serialize(death);
        messageSink.accept("玩家" + event.getEntity().getName() + "死了，" + detail);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow) || !(arrow.getShooter() instanceof Player player)
                || !indicatorEnabled(player)) return;
        boolean hit = event.getHitEntity() != null;
        player.sendMessage(hit ? "Hit!" : "Miss!");
        player.sendActionBar(Component.text(hit ? "Hit!" : "Miss!").color(
                TextColor.color(hit ? 34 : 139, hit ? 139 : 133, hit ? 34 : 42)));
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow) || !(arrow.getShooter() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity target) || !indicatorEnabled(player)) return;
        double damage = event.getDamage();
        player.sendMessage(Component.text(" -> " + target.getName() + " " + damage + " damage").color(TextColor.color(34, 139, 34)));
        player.sendActionBar(Component.text(player.getName() + " -> " + target.getName() + " 造成 " + damage + " 点伤害").color(TextColor.color(34, 139, 34)));
    }

    private static boolean indicatorEnabled(Player player) {
        return player.getScoreboardTags().contains("di");
    }
}
