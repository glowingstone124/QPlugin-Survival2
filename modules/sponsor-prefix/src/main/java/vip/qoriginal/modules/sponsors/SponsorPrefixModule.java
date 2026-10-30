package vip.qoriginal.modules.sponsors;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import vip.qoriginal.quantumplugin.hot.api.HotModule;
import vip.qoriginal.quantumplugin.hot.api.ModuleContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** Migrated from NamePrefix: this module owns no Timer, thread or Bukkit registration. */
public final class SponsorPrefixModule implements HotModule {
    private Set<String> sponsors = Set.of();

    @Override public void prepare(ModuleContext context) throws Exception {
        // Chambers did not previously install NamePrefix.
        if (context.hostName().equals("QuantumPlugin-Chambers")) return;
        Path file = context.serverDirectory().resolve("sponsors.txt");
        if (Files.isRegularFile(file)) {
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(262_145);
                if (bytes.length > 262_144) throw new IllegalArgumentException("sponsors.txt exceeds 256 KiB");
                update(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        context.watchTextFile(file, 262_144, 1200, this::update);
        context.listen(PlayerJoinEvent.class, EventPriority.NORMAL, false, event -> {
            String name = event.getPlayer().getName();
            String displayName = sponsors.contains(name) ? "§b[SPONSOR]§f" + name : switch (name) {
                case "MineCreeper2086" -> "§a[Master]§f" + name;
                case "glowingstone124" -> "§a[ADMIN]§f" + name;
                default -> name;
            };
            event.getPlayer().displayName(Component.text(displayName));
        });
    }
    private void update(String text) {
        sponsors = text.lines().map(String::trim).filter(value -> value.matches("[A-Za-z0-9_]{1,16}"))
            .collect(Collectors.toUnmodifiableSet());
    }
}
