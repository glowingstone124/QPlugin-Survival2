package vip.qoriginal.quantumplugin;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayDeque;
import java.util.ArrayList;

public class MSPTCalculator implements Listener {
    public static volatile float mspt;
    private static final int THREE_SECOND_TICKS = 60;
    private static final ArrayDeque<Float> recent3SecondTicks = new ArrayDeque<>(THREE_SECOND_TICKS);
    private static final ArrayDeque<Float> tickList = new ArrayDeque<>(THREE_SECOND_TICKS);
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
        addSample(recent3SecondTicks, sample);
        addSample(tickList, sample);
    }

    public static float getR3s() {
        synchronized (recent3SecondTicks) {
            if (recent3SecondTicks.isEmpty()) return 0f;
            float sum = 0;
            for (float value : recent3SecondTicks) sum += value;
            return sum / recent3SecondTicks.size();
        }
    }

    public static ArrayList<Float> getRecent60t() {
        synchronized (tickList) { return new ArrayList<>(tickList); }
    }

    private static void addSample(ArrayDeque<Float> samples, float value) {
        synchronized (samples) {
            if (samples.size() >= THREE_SECOND_TICKS) samples.removeFirst();
            samples.addLast(value);
        }
    }
}
