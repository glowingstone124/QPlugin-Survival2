package vip.qoriginal.quantumplugin.registration;

import org.bukkit.entity.Player;

public final class ReservedMinecraftRegistrationTest implements MinecraftRegistrationTest {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public StartResult start(Player player, Session session) {
        return StartResult.unavailable();
    }

    @Override
    public void cancel(Player player) {
        // Reserved for the future world-based registration test implementation.
    }
}
