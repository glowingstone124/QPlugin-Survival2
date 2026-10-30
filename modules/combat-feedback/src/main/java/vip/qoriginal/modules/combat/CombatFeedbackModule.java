package vip.qoriginal.modules.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import vip.qoriginal.quantumplugin.hot.api.HotModule;
import vip.qoriginal.quantumplugin.hot.api.ModuleContext;
import java.util.List;
import java.util.Locale;

/** Player preference remains in the existing scoreboard tag, outside the module generation. */
public final class CombatFeedbackModule implements HotModule {
    private boolean survival;
    @Override public void prepare(ModuleContext context) {
        if (!List.of("QuantumPlugin", "QuantumPlugin-Creative").contains(context.hostName())) return;
        survival = context.hostName().equals("QuantumPlugin");
        context.listen(ProjectileHitEvent.class, EventPriority.NORMAL, false, this::onProjectileHit);
        context.listen(EntityDamageByEntityEvent.class, EventPriority.NORMAL, false, this::onDamage);
        context.command("damageindicator", (sender, command, label, args) -> toggle(sender, args),
            (sender, command, alias, args) -> args.length == 1
                ? List.of("query", "enable", "disable").stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of());
    }
    private boolean enabled(Player player) { return player.getScoreboardTags().contains("di"); }
    private void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow) || !(arrow.getShooter() instanceof Player player) || !enabled(player)) return;
        boolean hit = event.getHitEntity() != null;
        player.sendMessage(hit ? "Hit!" : "Miss!");
        player.sendActionBar(Component.text(hit ? "Hit!" : "Miss!").color(TextColor.color(hit ? 34 : 139, hit ? 139 : 133, hit ? 34 : 42)));
    }
    private void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow) || !(arrow.getShooter() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity target) || !enabled(player)) return;
        double damage = event.getDamage();
        player.sendMessage(Component.text(" -> " + target.getName() + " " + damage + " damage").color(TextColor.color(34, 139, 34)));
        player.sendActionBar(Component.text(player.getName() + " -> " + target.getName() + " 造成 " + damage + " 点伤害").color(TextColor.color(34, 139, 34)));
    }
    private boolean toggle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("只有玩家可以执行这个命令。"); return true; }
        if (args.length != 1) { usage(sender); return true; }
        switch (args[0]) {
            case "query" -> {
                if (survival) message(sender, "伤害提示当前状态: " + (enabled(player) ? "开启" : "关闭"), NamedTextColor.AQUA);
                else sender.sendMessage("当前状态：" + (enabled(player) ? "开启" : "关闭"));
            }
            case "enable", "disable" -> {
                boolean enable = args[0].equals("enable");
                if (enable) player.addScoreboardTag("di"); else player.removeScoreboardTag("di");
                if (survival) message(sender, enable ? "伤害提示已开启。" : "伤害提示已关闭。", NamedTextColor.GREEN);
                else sender.sendMessage("成功");
            }
            default -> usage(sender);
        }
        return true;
    }
    private void usage(CommandSender sender) {
        if (survival) message(sender, "用法: /damageindicator <query|enable|disable>", NamedTextColor.YELLOW);
        else sender.sendMessage("[query] 查询开启状态 [enable]开启 [disable]关闭");
    }
    private void message(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text("[QO] ", NamedTextColor.DARK_AQUA, TextDecoration.BOLD)
            .append(Component.text(text, color).decoration(TextDecoration.BOLD, false)));
    }
}
