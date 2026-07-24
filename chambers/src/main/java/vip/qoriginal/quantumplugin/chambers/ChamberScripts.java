package vip.qoriginal.quantumplugin.chambers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public final class ChamberScripts {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int MAX_SCRIPTS = 128;
    private static final int MAX_MESSAGES_PER_SCRIPT = 64;
    private static final long MAX_DELAY_TICKS = 20L * 60L * 10L;
    private final List<MessageScript> scripts;

    private ChamberScripts(List<MessageScript> scripts) {
        this.scripts = List.copyOf(scripts);
    }

    public static ChamberScripts load(Path scriptsDirectory) {
        if (!Files.exists(scriptsDirectory)) {
            return new ChamberScripts(List.of());
        }
        if (!Files.isDirectory(scriptsDirectory)) {
            throw new IllegalArgumentException(scriptsDirectory + " must be a directory");
        }
        try (Stream<Path> files = Files.list(scriptsDirectory)) {
            List<Path> scriptFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            if (scriptFiles.size() > MAX_SCRIPTS) {
                throw new IllegalArgumentException(scriptsDirectory + " contains too many message scripts");
            }
            List<MessageScript> scripts = new ArrayList<>(scriptFiles.size());
            for (Path file : scriptFiles) {
                scripts.add(loadScript(file));
            }
            return new ChamberScripts(scripts);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read " + scriptsDirectory, exception);
        }
    }

    public void fireEvent(
            TriggerType type,
            ScriptContext context,
            Set<String> firedScripts
    ) {
        scripts.stream()
                .filter(script -> script.trigger.type == type)
                .forEach(script -> fire(script, context, firedScripts));
    }

    public void fireRegions(
            Location playerLocation,
            ScriptContext context,
            Set<String> firedScripts
    ) {
        scripts.stream()
                .filter(script -> script.trigger.matchesRegion(playerLocation, context.origin))
                .forEach(script -> fire(script, context, firedScripts));
    }

    public void fireInteraction(
            Action action,
            Block clickedBlock,
            ScriptContext context,
            Set<String> firedScripts
    ) {
        if (clickedBlock == null) {
            return;
        }
        scripts.stream()
                .filter(script -> script.trigger.matchesInteraction(action, clickedBlock, context.origin))
                .forEach(script -> fire(script, context, firedScripts));
    }

    private void fire(MessageScript script, ScriptContext context, Set<String> firedScripts) {
        String firedKey = context.chamber.id() + "/" + script.id;
        if (script.trigger.once && !firedScripts.add(firedKey)) {
            return;
        }
        for (MessageLine line : script.messages) {
            Runnable delivery = () -> {
                if (!context.player.isOnline() || !context.isValid()) {
                    return;
                }
                Component component = MINI_MESSAGE.deserialize(expand(line.text, context));
                switch (line.channel) {
                    case CHAT -> context.player.sendMessage(component);
                    case ACTION_BAR -> context.player.sendActionBar(component);
                    case TITLE -> context.player.showTitle(Title.title(component, Component.empty()));
                }
            };
            if (line.delayTicks == 0) {
                delivery.run();
            } else {
                context.plugin.getServer().getScheduler().runTaskLater(
                        context.plugin,
                        delivery,
                        line.delayTicks
                );
            }
        }
    }

    private static MessageScript loadScript(Path file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection triggerSection = config.getConfigurationSection("trigger");
        if (triggerSection == null) {
            throw new IllegalArgumentException(file + " is missing trigger");
        }
        Trigger trigger = Trigger.load(triggerSection, file);
        List<Map<?, ?>> rawMessages = config.getMapList("messages");
        if (rawMessages.isEmpty()) {
            throw new IllegalArgumentException(file + " is missing messages");
        }
        if (rawMessages.size() > MAX_MESSAGES_PER_SCRIPT) {
            throw new IllegalArgumentException(file + " contains too many messages");
        }
        List<MessageLine> messages = new ArrayList<>(rawMessages.size());
        for (Map<?, ?> raw : rawMessages) {
            String text = raw.get("text") == null ? "" : raw.get("text").toString();
            if (text.isBlank()) {
                throw new IllegalArgumentException(file + " contains a blank message");
            }
            long delay = number(raw.get("delay-ticks"), 0L);
            if (delay < 0 || delay > MAX_DELAY_TICKS) {
                throw new IllegalArgumentException(file + " contains an invalid delay-ticks");
            }
            MessageChannel channel = MessageChannel.parse(
                    raw.get("channel") == null ? "chat" : raw.get("channel").toString()
            );
            messages.add(new MessageLine(delay, channel, text));
        }
        String fileName = file.getFileName().toString();
        String id = fileName.substring(0, fileName.lastIndexOf('.'));
        return new MessageScript(id, trigger, messages);
    }

    private static String expand(String text, ScriptContext context) {
        return text
                .replace("{player}", context.player.getName())
                .replace("{chamber}", context.chamber.id())
                .replace("{completed}", Integer.toString(context.completed))
                .replace("{total}", Integer.toString(context.total));
    }

    private static long number(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("expected a number, got " + value);
        }
    }

    private record MessageScript(String id, Trigger trigger, List<MessageLine> messages) {
        private MessageScript {
            messages = List.copyOf(messages);
        }
    }

    private record MessageLine(long delayTicks, MessageChannel channel, String text) {
    }

    private record RelativeBlock(int x, int y, int z) {
        boolean matches(Block block, Location origin) {
            return block.getX() == origin.getBlockX() + x
                    && block.getY() == origin.getBlockY() + y
                    && block.getZ() == origin.getBlockZ() + z;
        }
    }

    private record Trigger(
            TriggerType type,
            boolean once,
            ChamberRegion region,
            Action action,
            Material material,
            RelativeBlock position
    ) {
        static Trigger load(ConfigurationSection section, Path file) {
            TriggerType type = TriggerType.parse(section.getString("type", ""));
            boolean once = section.getBoolean("once", true);
            ChamberRegion region = null;
            Action action = null;
            Material material = null;
            RelativeBlock position = null;
            if (type == TriggerType.REGION) {
                region = readRegion(section, file);
            } else if (type == TriggerType.INTERACT_BLOCK) {
                String actionName = section.getString("action");
                if (actionName != null && !actionName.isBlank()) {
                    try {
                        action = Action.valueOf(actionName.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException(file + " has an invalid interaction action");
                    }
                }
                String materialName = section.getString("material");
                if (materialName != null && !materialName.isBlank()) {
                    material = Material.matchMaterial(materialName);
                    if (material == null) {
                        throw new IllegalArgumentException(file + " has an invalid interaction material");
                    }
                }
                ConfigurationSection positionSection = section.getConfigurationSection("position");
                if (positionSection != null) {
                    position = new RelativeBlock(
                            requiredInt(positionSection, "x", file),
                            requiredInt(positionSection, "y", file),
                            requiredInt(positionSection, "z", file)
                    );
                }
            }
            return new Trigger(type, once, region, action, material, position);
        }

        boolean matchesRegion(Location location, Location origin) {
            return type == TriggerType.REGION
                    && region != null
                    && region.contains(location, origin.getWorld(), origin);
        }

        boolean matchesInteraction(Action actualAction, Block block, Location origin) {
            return type == TriggerType.INTERACT_BLOCK
                    && (action == null || action == actualAction)
                    && (material == null || material == block.getType())
                    && (position == null || position.matches(block, origin));
        }

        private static ChamberRegion readRegion(ConfigurationSection section, Path file) {
            ConfigurationSection min = section.getConfigurationSection("min");
            ConfigurationSection max = section.getConfigurationSection("max");
            if (min == null || max == null) {
                throw new IllegalArgumentException(file + " region trigger requires min and max");
            }
            int minX = Math.min(requiredInt(min, "x", file), requiredInt(max, "x", file));
            int minY = Math.min(requiredInt(min, "y", file), requiredInt(max, "y", file));
            int minZ = Math.min(requiredInt(min, "z", file), requiredInt(max, "z", file));
            int maxX = Math.max(requiredInt(min, "x", file), requiredInt(max, "x", file));
            int maxY = Math.max(requiredInt(min, "y", file), requiredInt(max, "y", file));
            int maxZ = Math.max(requiredInt(min, "z", file), requiredInt(max, "z", file));
            return new ChamberRegion(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private static int requiredInt(ConfigurationSection section, String key, Path file) {
            if (!section.isInt(key)) {
                throw new IllegalArgumentException(file + " is missing integer " + key);
            }
            return section.getInt(key);
        }
    }

    public record ScriptContext(
            ChambersPlugin plugin,
            Player player,
            ChamberDefinition chamber,
            Location origin,
            int completed,
            int total,
            BooleanSupplier validity
    ) {
        boolean isValid() {
            return validity.getAsBoolean();
        }
    }

    public enum TriggerType {
        ENTER,
        REGION,
        INTERACT_BLOCK,
        COMPLETE,
        FAIL;

        static TriggerType parse(String value) {
            try {
                return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown message trigger type: " + value);
            }
        }
    }

    private enum MessageChannel {
        CHAT,
        ACTION_BAR,
        TITLE;

        static MessageChannel parse(String value) {
            try {
                return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown message channel: " + value);
            }
        }
    }
}
