package vip.qoriginal.quantumplugin;

import io.papermc.paper.entity.LookAnchor;
import jdk.internal.org.jline.utils.Status;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import vip.qoriginal.quantumplugin.industry.BoneMealFlowery;
import vip.qoriginal.quantumplugin.industry.StoneFarm;
import vip.qoriginal.quantumplugin.patch.Knowledge;

import java.io.IOException;
import java.net.Socket;
import java.util.Timer;

public final class QuantumPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        System.out.println("1.14.5.15 Started.");
        getServer().getPluginManager().registerEvents(new JoinLeaveListener(),this);
        getServer().getPluginManager().registerEvents(new ChatCommandListener(),this);
        getServer().getPluginManager().registerEvents(new MSPTCalculator(),this);
        getServer().getPluginManager().registerEvents(new VillagerCureTimer(),this);
        getServer().getPluginManager().registerEvents(new Knowledge(),this);
        getServer().getPluginManager().registerEvents(new BoneMealFlowery(),this);
        getServer().getPluginManager().registerEvents(new StoneFarm(),this);
        getServer().getPluginManager().registerEvents(new NamePrefix(),this);

        Timer timer = new Timer();
        timer.schedule(new StatusUpload(), 1000, 3000);
        Block b = Bukkit.getWorld("world").getBlockAt(-1782,68,720);
        if(b.getChunk().load()) {
            if(b.getType() == Material.LEVER) {
                BlockData data = b.getBlockData();
                if(data.getAsString().contains("powered=true")) StoneFarm.console_state = 10;
            }
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        System.out.println("Ended.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("suicide")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player s = (Player) sender;
            sender.sendMessage(Component.text("晚安..."));
            Location batloc = s.getLocation();
            batloc.setX(-66);
            Entity e = s.getWorld().spawnEntity(batloc, EntityType.BAT);
            e.customName(Component.text("中子束").color(TextColor.color(72,72,72)));
            s.setHealth(0.1f);
            s.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE);
            s.damage(s.getHealth()+5, e);
            int i = 0;
            while(!s.isDead() && ++i != 10) s.damage(5, e);
            if(!s.isDead()) s.setHealth(0f);
            return true;
        } else if (command.getName().equalsIgnoreCase("myloc")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player s = (Player) sender;
            String world_name = s.getWorld().getName();
            Component common_component = Component.text("玩家").color(TextColor.color(255,212,40))
                    .append(Component.text("["+s.getName()+"]").color(TextColor.color(128, 212, 28)))
                    .append(Component.text("发布了自己的位置：")).appendNewline()
                    .append(Component.text("x: "+s.getLocation().getBlockX()+", y: "+s.getLocation().getBlockY()+", z: "+s.getLocation().getBlockZ()+" ("+world_name+")"));
            for(Player player:Bukkit.getOnlinePlayers()) {
                if(world_name.equals(player.getWorld().getName())) {
                    player.sendMessage(common_component.append(Component.text("["+Math.round(player.getLocation().distance(s.getLocation())*100)/100f+"方块外]").color(TextColor.color(192, 168, 216))));
                    player.sendMessage(Component.text("用粒子效果高亮显示方位").decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.suggestCommand("/highlight "+s.getLocation().getBlockX()+" "+s.getLocation().getBlockY()+" "+s.getLocation().getBlockZ())));
                } else {
                    player.sendMessage(common_component.append(Component.text("[不在同一个世界]").color(TextColor.color(206, 206, 216))));
                }
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("highlight") && args.length == 3) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player s = (Player) sender;
            Location l = new Location(s.getWorld(),Float.parseFloat(args[0]),Float.parseFloat(args[1]),Float.parseFloat(args[2]));
            double distance = s.getLocation().distance(l);
            if(distance!=0) {
                double factor = 15 / distance;
                Location particle = new Location(s.getWorld(),
                        s.getLocation().getBlockX()*(1-factor)+l.getBlockX()*factor,
                        s.getLocation().getBlockY()+1.5,
                        s.getLocation().getBlockZ()*(1-factor)+l.getBlockZ()*factor);
                s.getWorld().spawnParticle(Particle.FIREWORKS_SPARK,particle,70,3,1,3);
                s.getWorld().spawnParticle(Particle.VILLAGER_HAPPY,particle,70,1,1,1);
                s.sendMessage("如果没有展示粒子效果试试转个身重来？");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("shutup") && args.length == 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player s = (Player) sender;
            if(args[0].contentEquals("query")) s.sendMessage(Component.text("当前向QQ同步消息的状态为：").append(isShutup(s)?Component.text("关闭").color(TextColor.color(255,0,0)):Component.text("开启").color(TextColor.color(0,255,0))));
            else if(args[0].contentEquals("enable")) {
                s.removeScoreboardTag("muteqq");
                s.sendMessage("已经启用QQ同步");
            } else if(args[0].contentEquals("disable")) {
                s.addScoreboardTag("muteqq");
                s.sendMessage("已经禁用QQ同步");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("showitem")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player s = (Player) sender;
            Component common_component = Component.text("玩家").color(TextColor.color(255,212,40))
                    .append(Component.text("["+s.getName()+"]").color(TextColor.color(128, 212, 28)))
                    .append(Component.text("手上有"+s.getInventory().getItemInMainHand().getAmount()+"个"))
                    .append(s.getInventory().getItemInMainHand().displayName());
            for(Player player:Bukkit.getOnlinePlayers()) {
                player.sendMessage(common_component);
            }
            return true;
        } else {
            return false;
        }
    }

    public static boolean isShutup(Player player) {
        boolean istagged = false;
        for(String s:player.getScoreboardTags()) if(s.contentEquals("muteqq")) istagged = true;
        return istagged;
    }
}
