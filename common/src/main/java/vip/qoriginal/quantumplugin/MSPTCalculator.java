package vip.qoriginal.quantumplugin;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;

public class MSPTCalculator implements Listener {
    public static volatile float mspt;
    private static final ArrayList<Float> recent60Ticks = new ArrayList<>();
    private static final ArrayList<Float> tickList = new ArrayList<>();
    private static volatile Runnable tickHook = () -> {};
    private long startNanos;

    public static void setTickHook(Runnable hook) {
        tickHook = hook == null ? () -> {} : hook;
    }

    @EventHandler
    public void onServerTickStart(ServerTickStartEvent event) {
        startNanos = System.nanoTime();
        tickHook.run();
    }

    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent event) {
        if (startNanos == 0) return;
        float sample = (System.nanoTime() - startNanos) / 1_000_000f;
        mspt = mspt == 0 || Float.isNaN(mspt) ? sample : mspt * .95f + sample * .05f;
        synchronized (recent60Ticks) { recent60Ticks.add(mspt); }
        add_to_tick_list(mspt);
    }

    public static float getR3s() {
        synchronized (recent60Ticks) {
            if (recent60Ticks.isEmpty()) return 0f;
            float sum = 0;
            for (float value : recent60Ticks) sum += value;
            int count = recent60Ticks.size();
            recent60Ticks.clear();
            return sum / count;
        }
    }

    public static ArrayList<Float> getRecent60t() {
        synchronized (tickList) { return new ArrayList<>(tickList); }
    }

    public static void add_to_tick_list(float value) {
        synchronized (tickList) {
            if (tickList.size() >= 60) tickList.removeFirst();
            tickList.add(value);
        }
    }
}
