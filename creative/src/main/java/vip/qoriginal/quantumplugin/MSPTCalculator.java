package vip.qoriginal.quantumplugin;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Calendar;

public class MSPTCalculator implements Listener {
    /** 最终展现在返回结果的MilliSecond Per Tick值 */
    public static float mspt = 0f;
    public static ArrayList<Float> recent_60tick = new ArrayList<>();
    public static ArrayList<Float> tick_list = new ArrayList<>();
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
            recent_60tick.add(mspt);
            //告警逻辑
            if(System.currentTimeMillis()-lasterror>120000 && mspt>77.0) {
                try {
                    lasterror = System.currentTimeMillis();
                } catch (Exception e) {}
            }
        }
    }

    public static float getR3s() {
        int sum = 0;
        for(float l:recent_60tick) sum += l;
        float result = (float) sum / recent_60tick.size();
        recent_60tick.clear();
        return result;
    }
    public static ArrayList<Float> getRecent60t(){
        return tick_list;
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
    public static void add_to_tick_list(float f) {
        if (tick_list.size() >= 60) {
            tick_list.removeFirst();
        }
        tick_list.add(f);
    }
}
