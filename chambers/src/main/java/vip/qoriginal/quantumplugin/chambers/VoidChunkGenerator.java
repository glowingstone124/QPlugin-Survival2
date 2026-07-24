package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

public final class VoidChunkGenerator extends ChunkGenerator {
    @Override
    public @NotNull ChunkData generateChunkData(
            @NotNull World world,
            @NotNull Random random,
            int chunkX,
            int chunkZ,
            @NotNull BiomeGrid biome
    ) {
        return createChunkData(world);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
