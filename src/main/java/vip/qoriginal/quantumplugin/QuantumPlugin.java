package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.KeyedBossBar;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import vip.qoriginal.quantumplugin.combatZone.*;
import vip.qoriginal.quantumplugin.metro.SegmentMap;
import vip.qoriginal.quantumplugin.patch.*;
import vip.qoriginal.quantumplugin.industry.StoneFarm;
import vip.qoriginal.quantumplugin.metro.Speed;
import vip.qoriginal.quantumplugin.metro.LoadChunk;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


public final class QuantumPlugin extends JavaPlugin {

    private WebMsgGetter webMsgGetterTask;
    boolean enableMetro = true;
    private static QuantumPlugin instance;
    PlayerInventoryViewer piv = new PlayerInventoryViewer();
    private TextDisplay td = new TextDisplay();
    LeaveMessageComponent leaveMessageComponent = new LeaveMessageComponent();
    Login login = new Login();
    ChatSync cs = new ChatSync();
    public void init() {
        //webMsgGetterTask = new WebMsgGetter();
        System.out.println("QPlugin for Combat Zone, Please do not use this version in regular server!!!");
        try {
            JoinLeaveListener.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int delay = 0;
        int period = 20;
        JSONObject stopObj = new JSONObject();
        stopObj.put("timestamp", System.currentTimeMillis());
        stopObj.put("stat", 0);
        try {
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/alive/upload", stopObj.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        piv.init();
        getServer().getScheduler().scheduleSyncRepeatingTask(this, webMsgGetterTask, delay, period);
        Listener[] needReg = {
                new Login(),
                new JoinLeaveListener(),
                new ChatCommandListener(),
                new MSPTCalculator(),
                new Knowledge(),
                new ChatSync(),
                /*new Chat(),*/
                new SpeedMonitor(this),
                new NamePrefix(),
                new PlayerEventListener(),
                new PlayerInventoryViewer(),
                new BuffSnowball(),
                new CustomItemStack(),
                new FriendlyTnt(),
        };
        Arrays.stream(needReg).forEach(e -> getServer().getPluginManager().registerEvents(e, this));
        ChatSync cs = new ChatSync();
        cs.init();
        if (enableMetro) {
            getServer().getPluginManager().registerEvents(new Speed(), this);
            getServer().getPluginManager().registerEvents(new LoadChunk(this), this);
        }
        StatusUpload su = new StatusUpload();
        new BukkitRunnable() {
            @Override
            public void run() {
                su.run();
                SegmentMap.refresh();
            }
        }.runTaskTimer(this, 0L, 10L);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (@NotNull Iterator<KeyedBossBar> it = Bukkit.getBossBars(); it.hasNext(); ) {
                    BossBar bar = it.next();
                    if(bar.getTitle().contentEquals("治疗进度")) {
                        bar.removeAll();
                    }
                }
            }
        }.runTaskTimerAsynchronously(this, 0L, 30L);

        new BukkitRunnable() {
            @Override
            public void run() {
                getServer().getOnlinePlayers().forEach(player -> {
                    try {
                        Request.sendPostRequest((Config.INSTANCE.getAPI_ENDPOINT()+ "/qo/online?name=" + player.getName() + "&ip=" + Objects.requireNonNull(player.getAddress()).getHostName()).trim(), "");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }.runTaskTimer(this, 0L, 20*20L/* 20 seconds */);
        Block b = Objects.requireNonNull(Bukkit.getWorld("world")).getBlockAt(-1782, 68, 720);
        if (b.getChunk().load()) {
            if (b.getType() == Material.LEVER) {
                BlockData data = b.getBlockData();
                if (data.getAsString().contains("powered=true")) StoneFarm.console_state = 10;
            }
        }
        SegmentMap.init();
        /*Objects.requireNonNull(this.getCommand("firework")).setExecutor(new Firework());
        Objects.requireNonNull(this.getCommand("newyeartnt")).setExecutor(new FriendlyTnt());
        Objects.requireNonNull(this.getCommand("newyeardumplings")).setExecutor(new BuffSnowball());*/
        Ranking ranking = new Ranking();
    }

    public void initCombat() {
        getServer().getPluginManager().registerEvents(new RestrictZones(), this);
        getServer().getPluginManager().registerEvents(new CombatPoints(), this);
        getServer().getPluginManager().registerEvents(new Respawn(), this);
        Objects.requireNonNull(getCommand("shop")).setExecutor(new ShopCommand());
        Bukkit.getScheduler().runTaskTimer(this,
                new Runnable() {
                    @Override
                    public void run() {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            CombatPoints.PlayerStats stats = CombatPoint.INSTANCE.getPlayerStats().get(player.getUniqueId());
                            if (stats == null) continue;

                            int score = stats.getPoints();

                            Component subtitle = Component.text("格斗点数: " + score)
                                    .color(TextColor.color(0, 255, 255));

                            Title title = Title.title(
                                    Component.empty(),
                                    subtitle,
                                    Title.Times.times(Duration.ZERO, Duration.ofDays(9999), Duration.ZERO)
                            );

                            player.showTitle(title);
                        }
                    }
                },
                0L,
                40L
        );
    }
    @Override
    public void onEnable() {
        instance = this;
        //init();
        initCombat();
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
            Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/alive/upload", stopObj.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println("Ended.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("querybind") && args.length == 1) {
            Player s = (Player) sender;
            String name = args[0];
            String result = null;
            try {
                result = Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/registry?name=" + name).get();
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
                                                        \s
                         qq号: %s
                                                        \s
                        \s""";
                s.sendMessage(String.format(message, name, relationship.qq));
            }
        } else if (command.getName().equalsIgnoreCase("viewInventory") && args.length == 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command!");
                return true;
            }
            try {
                String result = Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/registry?name=" + args[0]).get();
                JsonObject queryObj = (JsonObject) JsonParser.parseString(result);
                if (queryObj.get("code").getAsInt() != 0) {
                    sender.sendMessage("该玩家不存在！");
                    return true;
                }
                String res2 = Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/inventory/request?name=" + args[0] + "&from=" + sender.getName()).get();
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
                    sender.sendMessage(Component.text("请求未通过。可能你之前已经发送了请求，也可能当前的请求数已经过多。").color(TextColor.color(67, 205, 128)));
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else if (sender instanceof Player s && command.getName().equalsIgnoreCase("login")) {
            if (args.length != 1) {
                sender.sendMessage("请正确输入密码。");
                return true;
            }
            login.performLogin(s, args[0]);
            return true;
        }
        return false;
    }

    public static boolean isShutup(Player player) {
        boolean istagged = false;
        for (String s : player.getScoreboardTags()) if (s.contentEquals("muteqq")) istagged = true;
        return istagged;
    }

    public static boolean isIndicatorEnabled(Player player) {
        AtomicBoolean istagged = new AtomicBoolean(false);
        player.getScoreboardTags().forEach(tag -> {
            if (tag.contentEquals("di")) istagged.set(true);
        });
        return istagged.get();
    }

}
