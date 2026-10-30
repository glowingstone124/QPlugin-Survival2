package vip.qoriginal.modules.daily;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import vip.qoriginal.quantumplugin.hot.api.HotModule;
import vip.qoriginal.quantumplugin.hot.api.ModuleContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/** Daily announcements keep their once-per-day marker in the existing player PDC across generations. */
public final class DailyLoginModule implements HotModule {
    private record Config(boolean enabled, ZoneId zone, String template, String url) { }
    private static final Config DEFAULT = new Config(true, ZoneId.of("Asia/Shanghai"),
        "四周年活动火热进行中！选择你希望的阵营，加入混战！详情查看->{url}", "https://qoriginal.vip/collapse");
    private Config config = DEFAULT;
    private String lastText;
    private NamespacedKey marker;

    @Override public void prepare(ModuleContext context) throws Exception {
        if (context.hostName().equals("QuantumPlugin-Chambers")) return;
        // Both existing survival and creative plugins are named QuantumPlugin in plugin.yml.
        String namespace = context.hostName().equals("QuantumPlugin-Creative") ? "quantumplugin"
            : context.hostName().toLowerCase(Locale.ROOT);
        marker = new NamespacedKey(namespace, "daily_login_ad_last_shown");
        Path file = context.serverDirectory().resolve("daily-login-ad.json");
        if (Files.isRegularFile(file)) {
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(65_537);
                if (bytes.length > 65_536) throw new IllegalArgumentException("daily-login-ad.json exceeds 64 KiB");
                reload(new String(bytes, StandardCharsets.UTF_8), context);
            }
        }
        context.watchTextFile(file, 65_536, 100, text -> reload(text, context));
        // Login's guest/session changes remain in the permanent host. Announcements run afterwards.
        context.listen(PlayerJoinEvent.class, EventPriority.MONITOR, false, event -> show(event.getPlayer()));
    }

    private void reload(String text, ModuleContext context) {
        if (Objects.equals(lastText, text)) return;
        lastText = text;
        try {
            if (text.isBlank()) { config = DEFAULT; return; }
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            Config candidate = new Config(
                json.has("enabled") && !json.get("enabled").isJsonNull() ? json.get("enabled").getAsBoolean() : DEFAULT.enabled(),
                ZoneId.of(string(json, "timezone", DEFAULT.zone().getId())),
                string(json, "template", DEFAULT.template()), string(json, "url", DEFAULT.url()));
            config = candidate;
        } catch (RuntimeException error) {
            context.logger().warning("Invalid daily-login-ad.json; keeping the last valid announcement: " + error.getMessage());
        }
    }
    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }
    private void show(Player player) {
        Config current = config;
        if (!current.enabled()) return;
        var data = player.getPersistentDataContainer();
        String today = LocalDate.now(current.zone()).toString();
        if (today.equals(data.get(marker, PersistentDataType.STRING))) return;
        player.sendMessage(render(current.template(), current.url()));
        data.set(marker, PersistentDataType.STRING, today);
    }
    static Component render(String template, String url) {
        int index = template.indexOf("{url}");
        if (index < 0) return Component.text(template, NamedTextColor.GOLD);
        Component link = Component.text(url, NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(url)).hoverEvent(HoverEvent.showText(Component.text("点击查看活动详情")));
        return Component.text(template.substring(0, index), NamedTextColor.GOLD).append(link)
            .append(Component.text(template.substring(index + 5), NamedTextColor.GOLD));
    }
}
