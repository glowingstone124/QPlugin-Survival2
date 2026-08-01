package vip.qoriginal.quantumplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;

public final class DailyLoginAdvertisement {
    private static final String CONFIG_FILE_NAME = "daily-login-ad.json";
    private static final String URL_PLACEHOLDER = "{url}";
    private static final AdvertisementConfig DEFAULT_CONFIG = new AdvertisementConfig(
            true,
            "Asia/Shanghai",
            "四周年活动火热进行中！选择你希望的阵营，加入混战！详情查看->{url}",
            "https://qoriginal.vip/collapse"
    );

    private static AdvertisementConfig config = DEFAULT_CONFIG;

    private DailyLoginAdvertisement() {
    }

    public static void init(JavaPlugin plugin) {
        File configFile = new File(CONFIG_FILE_NAME);
        try {
            copyDefaultConfig(plugin, configFile);
            try (Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                config = new AdvertisementConfig(
                        getBoolean(json, "enabled", DEFAULT_CONFIG.enabled()),
                        getString(json, "timezone", DEFAULT_CONFIG.timezone()),
                        getString(json, "template", DEFAULT_CONFIG.template()),
                        getString(json, "url", DEFAULT_CONFIG.url())
                );
                ZoneId.of(config.timezone());
            }
        } catch (Exception exception) {
            config = DEFAULT_CONFIG;
            plugin.getLogger().warning("无法加载 " + CONFIG_FILE_NAME + "，将使用默认广告配置: "
                    + exception.getMessage());
        }
    }

    public static void showIfFirstLoginToday(Player player) {
        AdvertisementConfig currentConfig = config;
        if (!currentConfig.enabled()) {
            return;
        }

        NamespacedKey lastShownKey = new NamespacedKey(
                PluginContext.getPlugin(), "daily_login_ad_last_shown"
        );
        PersistentDataContainer data = player.getPersistentDataContainer();
        String today = LocalDate.now(ZoneId.of(currentConfig.timezone())).toString();
        if (today.equals(data.get(lastShownKey, PersistentDataType.STRING))) {
            return;
        }

        data.set(lastShownKey, PersistentDataType.STRING, today);
        player.sendMessage(render(currentConfig.template(), currentConfig.url()));
    }

    private static void copyDefaultConfig(JavaPlugin plugin, File configFile) throws IOException {
        if (configFile.isFile()) {
            return;
        }

        if (configFile.toPath().getParent() != null) {
            Files.createDirectories(configFile.toPath().getParent());
        }
        try (InputStream defaultConfig = plugin.getResource(CONFIG_FILE_NAME)) {
            if (defaultConfig == null) {
                throw new IOException("插件中缺少默认配置资源");
            }
            Files.copy(defaultConfig, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }

    private static String getString(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    static Component render(String template, String url) {
        int placeholderIndex = template.indexOf(URL_PLACEHOLDER);
        if (placeholderIndex < 0) {
            return Component.text(template, NamedTextColor.GOLD);
        }

        Component link = Component.text(url, NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("点击查看活动详情")));

        return Component.text(template.substring(0, placeholderIndex), NamedTextColor.GOLD)
                .append(link)
                .append(Component.text(
                        template.substring(placeholderIndex + URL_PLACEHOLDER.length()),
                        NamedTextColor.GOLD
                ));
    }

    private record AdvertisementConfig(boolean enabled, String timezone, String template, String url) {
    }
}
