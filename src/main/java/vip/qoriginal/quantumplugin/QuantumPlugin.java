package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import org.bukkit.event.Listener;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import vip.qoriginal.quantumplugin.metro.SegmentMap;
import vip.qoriginal.quantumplugin.patch.CustomItemStack;
import vip.qoriginal.quantumplugin.patch.Knowledge;
import vip.qoriginal.quantumplugin.patch.SpeedMonitor;
import vip.qoriginal.quantumplugin.industry.StoneFarm;
import vip.qoriginal.quantumplugin.metro.Speed;
import vip.qoriginal.quantumplugin.metro.LoadChunk;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public final class QuantumPlugin extends JavaPlugin {

    private WebMsgGetter webMsgGetterTask;
    boolean enableMetro = true;
    private static QuantumPlugin instance;
    PlayerInventoryViewer piv = new PlayerInventoryViewer();
    @Override
    public void onEnable() {
        instance = this;
        System.out.println("1.14.5.5.1 Started.");
        webMsgGetterTask = new WebMsgGetter();
        int delay = 0;
        int period = 20;
        JSONObject stopObj = new JSONObject();
        stopObj.put("timestamp", System.currentTimeMillis());
        stopObj.put("stat", 0);
        try {
            Request.sendPostRequest("http://qoriginal.vip:8080/qo/alive/upload", stopObj.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        piv.init();
        getServer().getScheduler().scheduleSyncRepeatingTask(this, webMsgGetterTask, delay, period);
        Listener[] needReg = {
                new JoinLeaveListener(),
                new ChatCommandListener(),
                new MSPTCalculator(),
                new Knowledge(),
                new ChatSync(),
                new SpeedMonitor(this),
                new NamePrefix(),
                new PlayerEventListener(),
                new PlayerInventoryViewer(),
                new CustomItemStack()
        };
        for (Listener listener : needReg) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
        ChatSync cs = new ChatSync();
        if (enableMetro){
            getServer().getPluginManager().registerEvents(new Speed(), this);
            getServer().getPluginManager().registerEvents(new LoadChunk(this), this);
        }
        Timer timer = new Timer();
        timer.schedule(new StatusUpload(), 1000, 2000);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                SegmentMap.refresh();
            }
        }, 0, 500);
        Block b = Bukkit.getWorld("world").getBlockAt(-1782,68,720);
        if(b.getChunk().load()) {
            if(b.getType() == Material.LEVER) {
                BlockData data = b.getBlockData();
                if(data.getAsString().contains("powered=true")) StoneFarm.console_state = 10;
            }
        }
        SegmentMap.init();
    }
    public static QuantumPlugin getInstance() {
        return instance;
    }
    @Override
    public void onDisable() {
        webMsgGetterTask.cancel();
        JSONObject stopObj = new JSONObject();
        stopObj.put("timestamp", System.currentTimeMillis());
        stopObj.put("stat", 1);
        try {
            Request.sendPostRequest("http://qoriginal.vip:8080/qo/alive/upload", stopObj.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
            e.customName(Component.text("中子束").color(TextColor.color(72, 72, 72)));
            s.setHealth(0.1f);
            s.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE);
            s.damage(s.getHealth() + 5, e);
            int i = 0;
            while (!s.isDead() && ++i != 10) s.damage(5, e);
            if (!s.isDead()) s.setHealth(0f);
            return true;
        } else if (command.getName().equalsIgnoreCase("myloc")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player s = (Player) sender;
            String world_name = s.getWorld().getName();
            Component common_component = Component.text("玩家").color(TextColor.color(255, 212, 40))
                    .append(Component.text("[" + s.getName() + "]").color(TextColor.color(128, 212, 28)))
                    .append(Component.text("发布了自己的位置：")).appendNewline()
                    .append(Component.text("x: " + s.getLocation().getBlockX() + ", y: " + s.getLocation().getBlockY() + ", z: " + s.getLocation().getBlockZ() + " (" + world_name + ")"));
            ChatSync cs = new ChatSync();
            cs.sendChatMsg("玩家" + s.getName() + "发布了自己的位置：" + "x: " + s.getLocation().getBlockX() + ", y: " + s.getLocation().getBlockY() + ", z: " + s.getLocation().getBlockZ() + " (" + world_name + ")");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (world_name.equals(player.getWorld().getName())) {
                    player.sendMessage(common_component.append(Component.text("[" + Math.round(player.getLocation().distance(s.getLocation()) * 100) / 100f + "方块外]").color(TextColor.color(192, 168, 216))));
                    player.sendMessage(Component.text("用粒子效果高亮显示方位").decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.suggestCommand("/highlight " + s.getLocation().getBlockX() + " " + s.getLocation().getBlockY() + " " + s.getLocation().getBlockZ())));
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
            Location l = new Location(s.getWorld(), Float.parseFloat(args[0]), Float.parseFloat(args[1]), Float.parseFloat(args[2]));
            double distance = s.getLocation().distance(l);
            if (distance != 0) {
                double factor = 15 / distance;
                Location particle = new Location(s.getWorld(),
                        s.getLocation().getBlockX() * (1 - factor) + l.getBlockX() * factor,
                        s.getLocation().getBlockY() + 1.5,
                        s.getLocation().getBlockZ() * (1 - factor) + l.getBlockZ() * factor);
                s.getWorld().spawnParticle(Particle.FIREWORKS_SPARK, particle, 70, 3, 1, 3);
                s.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, particle, 70, 1, 1, 1);
                s.sendMessage("如果没有展示粒子效果试试转个身重来？");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("shutup") && args.length == 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            Player s = (Player) sender;
            if (args[0].contentEquals("query"))
                s.sendMessage(Component.text("当前向QQ同步消息的状态为：").append(isShutup(s) ? Component.text("关闭").color(TextColor.color(255, 0, 0)) : Component.text("开启").color(TextColor.color(0, 255, 0))));
            else if (args[0].contentEquals("enable")) {
                s.removeScoreboardTag("muteqq");
                s.sendMessage("已经启用QQ同步");
            } else if (args[0].contentEquals("disable")) {
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
            Component common_component = Component.text("玩家").color(TextColor.color(255, 212, 40))
                    .append(Component.text("[" + s.getName() + "]").color(TextColor.color(128, 212, 28)))
                    .append(Component.text("手上有" + s.getInventory().getItemInMainHand().getAmount() + "个"))
                    .append(s.getInventory().getItemInMainHand().displayName());
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(common_component);
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("bind") && args.length == 2) {
            if (args.length != 2) {
                sender.sendMessage("Usage: /bind <forum_account>");
                return true;
            }
            Player s = (Player) sender;
            Component common_component = Component.text("您的游戏id").color(TextColor.color(255, 212, 40))
                    .append(Component.text("[" + s.getName() + "]").color(TextColor.color(128, 212, 28)))
                    .append(Component.text("将要绑定到" + args[1] + "论坛账户。请输入/bindauth来确认"))
                    .append(s.getInventory().getItemInMainHand().displayName());
            s.sendMessage(common_component);
            s.setMetadata("need_confirm_binding", new FixedMetadataValue(this, args[1]));
        } else if (command.getName().equalsIgnoreCase("bindauth") && args.length == 1) {
            Player s = (Player) sender;
            if (s.hasMetadata("need_confirm_binding")) {
                List<MetadataValue> values = s.getMetadata("need_confirm_binding");
                String forumAccount = values.get(0).asString();
                try {
                    Request.sendPostRequest("http://qoriginal.vip:8080/qo/upload/link?name" + s.getName() + "&forum" + forumAccount, "");
                } catch (Exception e) {
                    s.sendMessage("由于系统错误，绑定无法完成");
                }
                s.sendMessage("已成功绑定到论坛账户：" + forumAccount);
                s.removeMetadata("need_confirm_binding", this);
            } else {
                s.sendMessage("没有需要确认的绑定。");
            }
        } else if (command.getName().equalsIgnoreCase("querybind") && args.length == 1) {
            Player s = (Player) sender;
            String name = args[0];
            String result = null;
            try {
                result = Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/registry?name=" + name);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            BindResponse relationship = new Gson().fromJson(result, BindResponse.class);
            if (relationship.code == 1) {
                s.sendMessage("你查询的用户名不存在");
            } else {
                String message = """
                        ==============================
                        查询结果
                        ==============================
                        用户名: %s
                                                        
                        qq号: %s
                                                        
                        """;
                s.sendMessage(String.format(message, name, relationship.qq));
            }
        } else if (command.getName().equalsIgnoreCase("viewInventory") && args.length == 1){
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            try {
                String result = Request.sendGetRequest("http://qoriginal.vip:8080/qo/download/registry?name=" + args[0]);
                JsonObject queryObj = (JsonObject) JsonParser.parseString(result);
                if (queryObj.get("code").getAsInt() != 0){
                    sender.sendMessage("该玩家不存在！");
                    return true;
                }
                String res2 = Request.sendGetRequest("http://qoriginal.vip:8080/qo/inventory/request?name=" + args[0] + "&from=" + sender.getName());
                if (JsonParser.parseString(res2).getAsJsonObject().get("code").getAsInt() == 0) {
                    String key = JsonParser.parseString(res2).getAsJsonObject().get("key").getAsString();
                    sender.sendMessage(Component.text("已经发送请求，请等待对方验证。")
                            .color(TextColor.color(67, 205, 128))
                            .append(Component.text("\n"))
                            .append(Component.text("保管好你的Key: " + key)
                                    .clickEvent(ClickEvent.copyToClipboard(key))
                            )
                    );
                    piv.insertKey(args[0], key);
                } else {
                    sender.sendMessage(Component.text("请求未通过。可能你之前已经发送了请求，也可能当前的请求数已经过多。").color(TextColor.color(67,205,128)));
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    public static boolean isShutup(Player player) {
        boolean istagged = false;
        for(String s:player.getScoreboardTags()) if(s.contentEquals("muteqq")) istagged = true;
        return istagged;
    }
}
