package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.function.Consumer;

public class PlayerEventListener implements Listener {
    private static volatile Consumer<String> messageSink = message -> {};

    public static void setMessageSink(Consumer<String> sink) {
        messageSink = sink == null ? message -> {} : sink;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Component death = event.deathMessage();
        String detail = death == null ? "" : PlainTextComponentSerializer.plainText().serialize(death);
        messageSink.accept("玩家" + event.getEntity().getName() + "死了，" + detail);
    }

}
