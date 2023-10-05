package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.*;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Player;
import org.checkerframework.checker.units.qual.A;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TimerTask;

public class StatusUpload extends TimerTask {
    public static String command = "";
    public static int totalUser = 0;
    @Override
    public void run() {
        Gson gson = new Gson();
        StatusSample status = new StatusSample();
        status.timestamp = System.currentTimeMillis();
        Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        status.onlinecount = players.length;
        for(Player p:players) {
            BriefPlayerInfo info = new BriefPlayerInfo();
            info.ping = p.getPing();
            info.world = p.getWorld().getName();
            info.x = p.getLocation().getBlockX();
            info.y = p.getLocation().getBlockY();
            info.z = p.getLocation().getBlockZ();
            info.name = p.getName();
            status.players.add(info);
        }
        status.total = uploadStats();
        status.totalcount = totalUser;
        status.mspt = Float.isNaN(MSPTCalculator.mspt)?0:MSPTCalculator.mspt;
        float mspt_3s = MSPTCalculator.getR3s();
        status.mspt_3s = Float.isNaN(mspt_3s)?0:mspt_3s;
        String data = gson.toJson(status);
        status.tick_time = Bukkit.getServer().getTickTimes();
        status.game_time = Bukkit.getServer().getWorld("world").getGameTime();
        for (@NotNull Iterator<KeyedBossBar> it = Bukkit.getBossBars(); it.hasNext(); ) {
            BossBar bar = it.next();
            if(bar.getTitle().contentEquals("治疗进度")) {
                bar.removeAll();
            }
        }
        try {
            String msg = Request.sendPostRequest("http://127.0.0.1:8080/qo/upload/status",data);
            Type listType = new TypeToken<ArrayList<MessageQ>>(){}.getType();
            ArrayList<MessageQ> msgs = gson.fromJson(msg, listType);
            if(msgs!=null) for(MessageQ msgq:msgs){
                if(msgq.sender.contentEquals("qqstatus")){
                    Bukkit.getLogger().info("Remotely queried server status.");
                    DetailedStatus ds = new DetailedStatus();
                    ds.onlinecount = status.onlinecount;
                    ds.players = status.players;
                    ds.tps = (float) Bukkit.getServer().getTPS()[0];
                    ds.mspt = MSPTCalculator.mspt;
                    ds.format = msgq.content;
                    ds.tick_time = Bukkit.getServer().getTickTimes();
                    ds.game_time = Bukkit.getServer().getWorld("world").getGameTime();
                    for(World w:Bukkit.getWorlds()) {
                        ds.cload += w.getLoadedChunks().length;
                    }
                    Bukkit.getLogger().info(gson.toJson(ds));
                    //ChatCommandListener.sendMsg("serverstatus",gson.toJson(ds));
                } else if(msgq.sender.contentEquals("qqdisk")){
                    Bukkit.getLogger().info("Remotely queried server disk.");
                    DiskUsage du = new DiskUsage();
                    File disk = new File("E:");
                    du.total = disk.getTotalSpace();
                    du.free = disk.getFreeSpace();
                    //ChatCommandListener.sendMsg("serverdisk",gson.toJson(du));
                } else if(msgq.sender.contentEquals("qqchunk")){
                    Bukkit.getLogger().info("Remotely queried server chunk.");
                    ArrayList<Chunk[]> chunks = new ArrayList<>();
                    for(World w:Bukkit.getWorlds()) {
                        chunks.add(w.getLoadedChunks());
                    }
                    Bukkit.getLogger().info(gson.toJson(chunks));
                } else if(msgq.sender.contentEquals("qqcommand")){
                    if(command.contentEquals("")) {
                        command = msgq.content;
                        Bukkit.getLogger().info("Remotely issued a server command: "+command+".");
                    }
                } else if(msgq.sender.contentEquals("qqbroadcast")){
                    Bukkit.broadcast(Component.text("[QQ] ").color(TextColor.color(150, 160, 255)).append(Component.text(msgq.content).color(TextColor.color(255, 255, 255))));
                } else {
                    String[] sender_info_group = new String[]{msgq.sender.substring(0,msgq.sender.indexOf("|")), msgq.sender.substring(msgq.sender.indexOf("|")+1)};
                    if (msgq.content.contains("@318278289"))
                        Bukkit.broadcast(Component.text("<" + sender_info_group[1] + "> ").color(TextColor.color(220, 160, 32)).hoverEvent(HoverEvent.showText(Component.text("QQ: "+sender_info_group[0]))).append(Component.text(msgq.content.replace("@385773631", "【提及服务器】")).color(TextColor.color(255, 255, 150))));
                    else {
                        if (msgq.content.indexOf("imgsrc=") == 0)
                            Bukkit.broadcast(Component.text("<" + sender_info_group[1] + "> ").color(TextColor.color(150, 160, 255)).hoverEvent(HoverEvent.showText(Component.text("QQ: "+sender_info_group[0]))).append(Component.text("[图片]点击查看").color(TextColor.color(0, 0, 200)).clickEvent(ClickEvent.openUrl(msgq.content.substring(7)))));
                        Bukkit.broadcast(Component.text("<" + sender_info_group[1] + "> ").color(TextColor.color(150, 160, 255)).hoverEvent(HoverEvent.showText(Component.text("QQ: "+sender_info_group[0]))).append(Component.text(msgq.content).color(TextColor.color(255, 255, 255))));
                    }
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("Experienced an exception" + e + " (on network?) while uploading status.\nIf the problem persists, please tell MineCreeper2086 to check if the target host is down.");
        }
    }

    public static Statistics uploadStats(){
        Statistics stats = new Statistics();
        OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();
        ArrayList<PlayerStat> playerStats = new ArrayList<>();
        Gson gson = new Gson();
        for(OfflinePlayer player:allPlayers){
            PlayerStat ps = new PlayerStat();
            ps.name = player.getName();
            ps.stats.coal_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.COAL_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_COAL_ORE);
            stats.coal_mined += ps.stats.coal_mined;
            ps.stats.copper_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.COPPER_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_COPPER_ORE);
            stats.copper_mined += ps.stats.copper_mined;
            ps.stats.diamond_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.DIAMOND_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_DIAMOND_ORE);
            stats.diamond_mined += ps.stats.diamond_mined;
            ps.stats.distance = player.getStatistic(Statistic.WALK_ONE_CM) + player.getStatistic(Statistic.BOAT_ONE_CM) + player.getStatistic(Statistic.MINECART_ONE_CM) + player.getStatistic(Statistic.HORSE_ONE_CM) + + player.getStatistic(Statistic.PIG_ONE_CM) + player.getStatistic(Statistic.SPRINT_ONE_CM) + player.getStatistic(Statistic.SWIM_ONE_CM) + player.getStatistic(Statistic.CROUCH_ONE_CM);
            stats.distance += ps.stats.distance;
            ps.stats.emerald_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.EMERALD_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_EMERALD_ORE);
            stats.emerald_mined += ps.stats.emerald_mined;
            ps.stats.game_time = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            stats.game_time += ps.stats.game_time;
            ps.stats.gold_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.GOLD_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_GOLD_ORE);
            stats.gold_mined += ps.stats.gold_mined;
            ps.stats.iron_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.IRON_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_IRON_ORE);
            stats.iron_mined += ps.stats.iron_mined;
            ps.stats.lapis_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.LAPIS_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_LAPIS_ORE);
            stats.lapis_mined += ps.stats.lapis_mined;
            ps.stats.netherite_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.ANCIENT_DEBRIS);
            stats.netherite_mined += ps.stats.netherite_mined;
            ps.stats.place_torch = player.getStatistic(Statistic.USE_ITEM, Material.TORCH);
            stats.place_torch += ps.stats.place_torch;
            ps.stats.place_lantern =player.getStatistic(Statistic.USE_ITEM, Material.LANTERN);
            stats.place_lantern += ps.stats.place_lantern;
            ps.stats.quartz_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.NETHER_QUARTZ_ORE);
            stats.quartz_mined += ps.stats.quartz_mined;
            ps.stats.redstone_mined = player.getStatistic(Statistic.MINE_BLOCK, Material.REDSTONE_ORE) + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_REDSTONE_ORE);
            stats.redstone_mined += ps.stats.redstone_mined;
            ps.stats.damage = player.getStatistic(Statistic.DAMAGE_DEALT);
            stats.damage += ps.stats.damage;
            ps.stats.deaths = player.getStatistic(Statistic.DEATHS);
            stats.deaths += ps.stats.deaths;
            playerStats.add(ps);
        }
        totalUser = allPlayers.length;
        String data = gson.toJson(playerStats);
        try {
            Request.sendPostRequest("http://127.0.0.1:8080/qo/upload/stats",data);
        } catch (Exception e) {
            Bukkit.getLogger().warning("Experienced an exception (on network?) while uploading statistics.\nIf the problem persists, please tell MineCreeper2086 to check if the target host is down.");
        }
        return stats;
    }

    public static class DetailedStatus {
        int onlinecount = 0;
        ArrayList<BriefPlayerInfo> players = new ArrayList<>();
        float tps = 0;
        float mspt = 0;
        int cload = 0;
        String format = "text";
        long[] tick_time;
        long game_time = 0;
    }

    public static class DiskUsage {
        long total = 0;
        long free = 0;
    }

    public static class StatusSample {
        int onlinecount = 0;
        int totalcount = 0;
        ArrayList<BriefPlayerInfo> players = new ArrayList<>();
        long timestamp = 0;
        Statistics total = null;
        float mspt = 0;
        float mspt_3s = 0;
        long[] tick_time;
        long game_time = 0;
    }

    public static class Statistics {
        long distance = 0;
        int place_torch = 0;
        int place_lantern = 0;
        long game_time = 0;
        int coal_mined = 0;
        int iron_mined = 0;
        int copper_mined = 0;
        int gold_mined = 0;
        int lapis_mined = 0;
        int emerald_mined = 0;
        int redstone_mined = 0;
        int diamond_mined = 0;
        int quartz_mined = 0;
        int netherite_mined = 0;
        int damage = 0;
        int deaths = 0;
    }

    public static class PlayerStat {
        String name = "";
        Statistics stats = new Statistics();
    }

    public static class BriefPlayerInfo {
        String name = "";
        int ping = 0;
        String world = "";
        int x = 0;
        int y = 0;
        int z = 0;
    }
}
