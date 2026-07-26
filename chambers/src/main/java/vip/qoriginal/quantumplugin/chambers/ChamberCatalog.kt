package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.structure.Structure;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ChamberCatalog(
        List<ChamberDefinition> chambers,
        Location lobby,
        String templateWorldName,
        String instanceWorldPrefix,
        int selectionCount,
        ChamberPosition placementOrigin,
        int placementGap
) {
    private static final String STRUCTURE_FILE_NAME = "structure.nbt";
    private static final String GOAL_FILE_NAME = "goal.yml";

    public ChamberCatalog {
        chambers = List.copyOf(chambers);
        lobby = lobby == null ? null : lobby.clone();
    }

    public static ChamberCatalog load(File globalConfigFile) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(globalConfigFile);
        String templateWorldName = config.getString("world.template", "chambers_template").trim();
        String instanceWorldPrefix = config.getString("world.instance-prefix", "qchamber_").trim();
        validateWorldNames(templateWorldName, instanceWorldPrefix);

        int selectionCount = config.getInt("selection-count", 1);
        if (selectionCount <= 0) {
            throw new IllegalArgumentException("selection-count must be positive");
        }
        int placementGap = config.getInt("placement.gap", 32);
        if (placementGap < 0) {
            throw new IllegalArgumentException("placement.gap must not be negative");
        }
        ChamberPosition placementOrigin = readPosition(
                requiredSection(config, "placement.origin", "chambers.yml"),
                "chambers.yml placement.origin"
        );

        File chambersDirectory = new File(globalConfigFile.getParentFile(), "chambers");
        if (!chambersDirectory.isDirectory() && !chambersDirectory.mkdirs()) {
            throw new IllegalArgumentException("unable to create chambers directory " + chambersDirectory);
        }
        Path chambersRoot = chambersDirectory.toPath().toAbsolutePath().normalize();
        Set<String> uniqueIds = new HashSet<>();
        List<String> pool = config.getStringList("pool");
        List<ChamberDefinition> chambers = new ArrayList<>(pool.size());
        for (String id : pool) {
            if (!id.matches("[A-Za-z0-9_.-]+") || !uniqueIds.add(id)) {
                throw new IllegalArgumentException("pool contains an unsafe or duplicate chamber id: " + id);
            }
            Path chamberDirectory = chambersRoot.resolve(id).normalize();
            if (!chamberDirectory.getParent().equals(chambersRoot) || !chamberDirectory.toFile().isDirectory()) {
                throw new IllegalArgumentException("missing chamber directory: " + chamberDirectory);
            }
            chambers.add(loadChamber(id, chamberDirectory));
        }

        ConfigurationSection lobbySection = config.getConfigurationSection("lobby");
        Location lobby = lobbySection == null ? null : readWorldLocation(lobbySection, "chambers.yml lobby");
        return new ChamberCatalog(
                chambers,
                lobby,
                templateWorldName,
                instanceWorldPrefix,
                selectionCount,
                placementOrigin,
                placementGap
        );
    }

    public List<ChamberDefinition> selectForRun() {
        if (chambers.isEmpty()) {
            return List.of();
        }
        List<ChamberDefinition> shuffled = new ArrayList<>(chambers);
        Collections.shuffle(shuffled);
        return List.copyOf(shuffled.subList(0, Math.min(selectionCount, shuffled.size())));
    }

    private static ChamberDefinition loadChamber(String id, Path directory) {
        File structureFile = directory.resolve(STRUCTURE_FILE_NAME).toFile();
        File goalFile = directory.resolve(GOAL_FILE_NAME).toFile();
        if (!structureFile.isFile()) {
            throw new IllegalArgumentException(id + " is missing " + STRUCTURE_FILE_NAME);
        }
        if (!goalFile.isFile()) {
            throw new IllegalArgumentException(id + " is missing " + GOAL_FILE_NAME);
        }

        Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(structureFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to load structure for " + id, exception);
        }

        YamlConfiguration goal = YamlConfiguration.loadConfiguration(goalFile);
        ChamberPosition spawn = readPosition(requiredSection(goal, "spawn", id), id + " spawn");
        ChamberRegion goalRegion = readRegion(requiredSection(goal, "goal", id), id + " goal");
        int timeLimitSeconds = goal.getInt("time-limit-seconds");
        if (timeLimitSeconds <= 0) {
            throw new IllegalArgumentException(id + " time-limit-seconds must be positive");
        }
        String title = nonBlankText(goal.getString("title"), id);
        String objective = nonBlankText(goal.getString("objective"), "抵达测试室出口");
        return new ChamberDefinition(
                id,
                title,
                objective,
                structure,
                goal.getBoolean("include-entities", true),
                spawn,
                goalRegion,
                timeLimitSeconds,
                ChamberScripts.load(directory.resolve("scripts"))
        );
    }

    private static String nonBlankText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void validateWorldNames(String templateWorldName, String instanceWorldPrefix) {
        if (!templateWorldName.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("world.template contains unsupported characters");
        }
        if (!instanceWorldPrefix.matches("[A-Za-z0-9_.-]+") || instanceWorldPrefix.length() < 3) {
            throw new IllegalArgumentException("world.instance-prefix must contain at least three safe characters");
        }
        if (templateWorldName.startsWith(instanceWorldPrefix)) {
            throw new IllegalArgumentException("world.template must not start with world.instance-prefix");
        }
    }

    private static ConfigurationSection requiredSection(
            ConfigurationSection parent,
            String path,
            String source
    ) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException(source + " is missing " + path);
        }
        return section;
    }

    private static Location readWorldLocation(ConfigurationSection section, String path) {
        String worldName = section.getString("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalArgumentException(path + " references an unloaded world");
        }
        ChamberPosition position = readPosition(section, path);
        return position.in(world);
    }

    private static ChamberPosition readPosition(ConfigurationSection section, String path) {
        requireNumber(section, "x", path);
        requireNumber(section, "y", path);
        requireNumber(section, "z", path);
        return new ChamberPosition(
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw", 0.0),
                (float) section.getDouble("pitch", 0.0)
        );
    }

    private static ChamberRegion readRegion(ConfigurationSection section, String path) {
        ConfigurationSection min = requiredSection(section, "min", path);
        ConfigurationSection max = requiredSection(section, "max", path);
        int minX = Math.min(readBlockCoordinate(min, "x", path + ".min"), readBlockCoordinate(max, "x", path + ".max"));
        int minY = Math.min(readBlockCoordinate(min, "y", path + ".min"), readBlockCoordinate(max, "y", path + ".max"));
        int minZ = Math.min(readBlockCoordinate(min, "z", path + ".min"), readBlockCoordinate(max, "z", path + ".max"));
        int maxX = Math.max(readBlockCoordinate(min, "x", path + ".min"), readBlockCoordinate(max, "x", path + ".max"));
        int maxY = Math.max(readBlockCoordinate(min, "y", path + ".min"), readBlockCoordinate(max, "y", path + ".max"));
        int maxZ = Math.max(readBlockCoordinate(min, "z", path + ".min"), readBlockCoordinate(max, "z", path + ".max"));
        return new ChamberRegion(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int readBlockCoordinate(ConfigurationSection section, String key, String path) {
        requireNumber(section, key, path);
        return section.getInt(key);
    }

    private static void requireNumber(ConfigurationSection section, String key, String path) {
        if (!section.isInt(key) && !section.isDouble(key)) {
            throw new IllegalArgumentException(path + " is missing numeric " + key);
        }
    }
}
