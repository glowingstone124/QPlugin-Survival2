package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

public final class ChamberWorldManager {
    private final ChambersPlugin plugin;
    private final String templateWorldName;
    private final String instancePrefix;
    private final Path worldContainer;
    private final VoidChunkGenerator voidGenerator = new VoidChunkGenerator();
    private World templateWorld;

    public ChamberWorldManager(ChambersPlugin plugin, String templateWorldName, String instancePrefix) {
        this.plugin = plugin;
        this.templateWorldName = templateWorldName;
        this.instancePrefix = instancePrefix;
        this.worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    public void initialize() {
        cleanupStaleInstances();
        templateWorld = Bukkit.createWorld(
                new WorldCreator(templateWorldName)
                        .generator(voidGenerator)
                        .generateStructures(false)
        );
        if (templateWorld == null) {
            throw new IllegalStateException("unable to create or load template world " + templateWorldName);
        }
        templateWorld.setAutoSave(true);
    }

    public World templateWorld() {
        if (templateWorld == null) {
            throw new IllegalStateException("chamber template world is not initialized");
        }
        return templateWorld;
    }

    public World createInstance(UUID playerId) {
        String worldName = instancePrefix + playerId.toString().replace("-", "");
        Path destination = safeWorldPath(worldName);
        try {
            deleteDirectory(destination);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to reset chamber instance directory", exception);
        }
        World instance = Bukkit.createWorld(
                new WorldCreator(worldName)
                        .generator(voidGenerator)
                        .generateStructures(false)
        );
        if (instance == null) {
            tryDelete(destination);
            throw new IllegalStateException("unable to load chamber instance " + worldName);
        }
        instance.setAutoSave(false);
        return instance;
    }

    public void destroyInstance(World world) {
        if (!isInstanceWorldName(world.getName())) {
            plugin.getLogger().severe("Refusing to delete non-instance world " + world.getName());
            return;
        }
        Path folder = world.getWorldFolder().toPath();
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("Unable to unload chamber instance " + world.getName());
            return;
        }
        tryDelete(folder);
    }

    private void cleanupStaleInstances() {
        for (World world : Bukkit.getWorlds().stream().filter(value -> isInstanceWorldName(value.getName())).toList()) {
            if (!world.getPlayers().isEmpty() || !Bukkit.unloadWorld(world, false)) {
                throw new IllegalStateException("stale chamber world is still in use: " + world.getName());
            }
        }
        try (Stream<Path> children = Files.list(worldContainer)) {
            children.filter(Files::isDirectory)
                    .filter(path -> isInstanceWorldName(path.getFileName().toString()))
                    .forEach(this::tryDelete);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to inspect stale chamber instances", exception);
        }
    }

    private boolean isInstanceWorldName(String worldName) {
        if (!worldName.startsWith(instancePrefix)) {
            return false;
        }
        String suffix = worldName.substring(instancePrefix.length());
        return suffix.matches("[0-9a-f]{32}");
    }

    private Path safeWorldPath(String worldName) {
        Path path = worldContainer.resolve(worldName).normalize();
        if (!path.startsWith(worldContainer) || path.equals(worldContainer)) {
            throw new IllegalArgumentException("unsafe chamber world path");
        }
        return path;
    }

    private void tryDelete(Path directory) {
        try {
            deleteDirectory(directory);
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to delete chamber instance " + directory + ": " + exception.getMessage());
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
