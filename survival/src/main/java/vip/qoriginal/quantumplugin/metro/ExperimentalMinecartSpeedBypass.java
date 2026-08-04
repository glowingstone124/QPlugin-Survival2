package vip.qoriginal.quantumplugin.metro;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftMinecart;
import org.bukkit.entity.Minecart;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Temporarily removes the 26.2 old-behavior speed cap from experimental minecarts. */
public final class ExperimentalMinecartSpeedBypass {
    private static final Field BEHAVIOR_FIELD = findBehaviorField();
    private static final ConcurrentHashMap<UUID, OldMinecartBehavior> ORIGINAL_BEHAVIORS = new ConcurrentHashMap<>();
    private static boolean warned;

    private ExperimentalMinecartSpeedBypass() {
    }

    static void enable(Minecart minecart) {
        if (!(minecart instanceof CraftMinecart craftMinecart)) {
            warnOnce("当前服务端没有提供 CraftMinecart，无法安装实验矿车速度补丁", null);
            return;
        }

        AbstractMinecart handle = craftMinecart.getHandle();
        if (handle.getBehavior() instanceof UnlockedOldMinecartBehavior) {
            return;
        }
        if (!(handle.getBehavior() instanceof OldMinecartBehavior oldBehavior)) {
            // The official improved behavior has no 2 block/tick cap.
            return;
        }
        if (BEHAVIOR_FIELD == null) {
            warnOnce("无法找到 26.2 AbstractMinecart.behavior 字段，实验矿车速度补丁未安装", null);
            return;
        }

        try {
            ORIGINAL_BEHAVIORS.putIfAbsent(minecart.getUniqueId(), oldBehavior);
            BEHAVIOR_FIELD.set(handle, new UnlockedOldMinecartBehavior(handle));
        } catch (IllegalAccessException | RuntimeException exception) {
            ORIGINAL_BEHAVIORS.remove(minecart.getUniqueId(), oldBehavior);
            warnOnce("无法替换 26.2 旧矿车行为，实验矿车速度补丁未安装", exception);
        }
    }

    static void disable(Minecart minecart) {
        OldMinecartBehavior original = ORIGINAL_BEHAVIORS.remove(minecart.getUniqueId());
        if (!(minecart instanceof CraftMinecart craftMinecart)
                || !(craftMinecart.getHandle().getBehavior() instanceof UnlockedOldMinecartBehavior)
                || BEHAVIOR_FIELD == null) {
            return;
        }

        try {
            BEHAVIOR_FIELD.set(
                    craftMinecart.getHandle(),
                    original != null ? original : new OldMinecartBehavior(craftMinecart.getHandle())
            );
        } catch (IllegalAccessException | RuntimeException exception) {
            warnOnce("无法恢复 26.2 原版旧矿车行为", exception);
        }
    }

    static void forget(UUID minecartId) {
        ORIGINAL_BEHAVIORS.remove(minecartId);
    }

    public static void restoreAll() {
        for (UUID minecartId : ORIGINAL_BEHAVIORS.keySet()) {
            if (Bukkit.getEntity(minecartId) instanceof Minecart minecart) {
                disable(minecart);
            } else {
                forget(minecartId);
            }
        }
    }

    private static Field findBehaviorField() {
        try {
            Field field = AbstractMinecart.class.getDeclaredField("behavior");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void warnOnce(String message, Throwable throwable) {
        if (warned) {
            return;
        }
        warned = true;
        if (throwable == null) {
            Bukkit.getLogger().warning("[QuantumPlugin] " + message);
        } else {
            Bukkit.getLogger().log(java.util.logging.Level.WARNING, "[QuantumPlugin] " + message, throwable);
        }
    }
}
