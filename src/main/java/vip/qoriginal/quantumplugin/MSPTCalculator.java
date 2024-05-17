package vip.qoriginal.quantumplugin;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import vip.qoriginal.quantumplugin.patch.Knowledge;

import java.util.ArrayList;
import java.util.Calendar;

public class MSPTCalculator implements Listener {
    /** 最终展现在返回结果的MilliSecond Per Tick值 */
    public static float mspt = 0f;
    public static ArrayList<Long> recent_60tick = new ArrayList<>();
    /** 记录一个游戏刻开始的毫秒时间 */
    private static long starttime = 0;
    /** 记录上一次 <code>mspt > 77</code> 的时间 */
    private static long lasterror = 0;
    private static int counter = 0;
    /**
     * 监听游戏刻开始
     * @param startEvent 游戏刻开始事件
     */
    @EventHandler
    public void onServerTickStart(ServerTickStartEvent startEvent) {
        //MSPT的主要实现区，更新starttime
        starttime = System.currentTimeMillis();
        //实现在主线程执行command|@命令
        if(!StatusUpload.command.contentEquals("")) {
            try {
                if(StatusUpload.command.indexOf("echo_off ")==0) {
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(),StatusUpload.command.substring(9));
                } else if(StatusUpload.command.indexOf("changepass ")==0) {
                    String[] args = StatusUpload.command.split(" ");
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(),"authme changepassword "+args[1]+" "+args[2]);
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(),"kick "+args[1]+" \"密码已更改，请重新登录！\"");
                } else {
                    //ChatCommandListener.sendMsg("serverbroadcast",Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(),StatusUpload.command)?"执行成功":"执行失败");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            StatusUpload.command = "";
        }
        someExtraWorks();
        //村民治愈进度条更新
    }
    /**
     * 监听游戏刻结束
     * @param endEvent 游戏刻结束事件
     */
    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent endEvent) {
        //MSPT的主要实现区，通过当前时间与starttime
        if(starttime!=0) {
            //我不知道为什么要加这一段 但是不加这一段他会报错。。。
            if(Float.isNaN(mspt)) {
                mspt = System.currentTimeMillis()-starttime;
                Bukkit.getLogger().warning("Why you get NaN in prev mspt?");
            } else {
                //MSPT的计算公式：0.95 × 先前MSPT + 0.05 × 本游戏刻MSPT
                mspt = mspt * 0.95f + (System.currentTimeMillis() - starttime) * 0.05f;
            }
            recent_60tick.add(System.currentTimeMillis()-starttime);
            //告警逻辑
            if(System.currentTimeMillis()-lasterror>120000 && mspt>77.0) {
                try {
                    //sendMsg("serverbroadcast", "【警告】MSPT值超过77.0，已经开始造成服务器卡顿！\n最近一分钟的TPS是："+(float) Bukkit.getServer().getTPS()[0]);
                    lasterror = System.currentTimeMillis();
                } catch (Exception e) {}
            }
        }
    }

    public static float getR3s() {
        int sum = 0;
        for(Long l:recent_60tick) sum += l;
        float result = (float) sum / recent_60tick.size();
        recent_60tick.clear();
        return result;
    }
    public static ArrayList<Long> getRecent60t(){
        return recent_60tick;
    }
    private static String f(int i) {
        if(i>=10) return i+"";
        else return "0"+i;
    }

    private static void someExtraWorks() {
        Block b = Bukkit.getWorld("world").getBlockAt(-2039,67,811);
        if(b.getChunk().isLoaded()) {
            if (b.getType() == Material.DARK_OAK_WALL_SIGN) {
                Sign sign = (Sign) b.getState();
                Calendar calendar = Calendar.getInstance();
                String time = f(calendar.get(Calendar.HOUR_OF_DAY))+":"+f(calendar.get(Calendar.MINUTE))+":"+f(calendar.get(Calendar.SECOND));
                sign.line(1,Component.text(time).decorate(TextDecoration.BOLD).append(Component.text(" UTC+8")));
                sign.update();
            }
        }

        b = Bukkit.getWorld("world").getBlockAt(-1696,66,687);
        if(b.getChunk().isLoaded()) {
            if (b.getType() == Material.DARK_OAK_WALL_SIGN) {
                Sign sign = (Sign) b.getState();
                Inventory i = ((Chest) Bukkit.getWorld("world").getBlockAt(-1702,70,720).getState()).getBlockInventory();
                Inventory i2 = ((Chest) Bukkit.getWorld("world").getBlockAt(-1702,70,719).getState()).getBlockInventory();
                int amount = 0;
                for(ItemStack itemStack:i.getContents()) if(itemStack!=null && itemStack.getType() == Material.BONE_MEAL) amount += itemStack.getAmount();
                for(ItemStack itemStack:i2.getContents()) if(itemStack!=null && itemStack.getType() == Material.BONE_MEAL) amount += itemStack.getAmount();
                sign.line(1,Component.text(amount+" / 3456"));
                sign.update();
            }
        }

        b = Bukkit.getWorld("world").getBlockAt(-1695,66,687);
        if(b.getChunk().isLoaded()) {
            if (b.getType() == Material.DARK_OAK_WALL_SIGN) {
                Sign sign = (Sign) b.getState();
                sign.update();
            }
        }


    }
}
