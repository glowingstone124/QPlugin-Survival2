package vip.qoriginal.quantumplugin.lightscan;

import java.io.IOException;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPOutputStream;

/** Pure snapshot processing, kept independent of Bukkit for reproducible export tests. */
public final class LightScanCsv {
    public static final String SOURCES_HEADER = "world,x,y,z,chunk_x,chunk_z,material,block_data,emission,block_light,sky_light,capture_full_time\n";
    public static final String LIGHTING_HEADER = "world,x,y,z,chunk_x,chunk_z,material,emission,block_light,sky_light,max_stored_light,capture_full_time\n";
    public static final String CHUNKS_HEADER = "world,chunk_x,chunk_z,center_x,center_z,min_x,max_x,min_y,max_y,min_z,max_z,scanned_blocks,source_count,emission_sum,max_emission,capture_full_time,block_light_sum,sky_light_sum,max_block_light,max_sky_light"
            + histogramHeader("block_light") + histogramHeader("sky_light") + "\n";
    public static final String MATERIALS_HEADER = "material,source_count,emission_sum\n";

    public record State(String material, String data, int emission, int blockLight, int skyLight) { }
    public record Totals(long sources, long emission) { }
    public record Result(String sourcesCsv, String lightingCsv, String chunkCsv, long sources, Map<String, Totals> materials) { }

    public interface Volume {
        State state(int x, int y, int z);
    }

    private LightScanCsv() { }

    public static Result scan(String world, int chunkX, int chunkZ, long captureTime, ScanBounds bounds,
                              Volume volume, BooleanSupplier cancelled) {
        int minX = Math.max(bounds.minX(), chunkX * 16);
        int maxX = Math.min(bounds.maxX(), chunkX * 16 + 15);
        int minZ = Math.max(bounds.minZ(), chunkZ * 16);
        int maxZ = Math.min(bounds.maxZ(), chunkZ * 16 + 15);
        StringBuilder rows = new StringBuilder();
        StringBuilder lighting = new StringBuilder();
        Map<String, Totals> materials = new HashMap<>();
        long sources = 0;
        long emission = 0;
        int maxEmission = 0;
        long blockLightSum = 0;
        long skyLightSum = 0;
        int maxBlockLight = 0;
        int maxSkyLight = 0;
        long[] blockHistogram = new long[16];
        long[] skyHistogram = new long[16];
        String prefix = cell(world) + ",";
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    State state = volume.state(Math.floorMod(x, 16), y, Math.floorMod(z, 16));
                    blockLightSum += state.blockLight();
                    skyLightSum += state.skyLight();
                    maxBlockLight = Math.max(maxBlockLight, state.blockLight());
                    maxSkyLight = Math.max(maxSkyLight, state.skyLight());
                    blockHistogram[state.blockLight()]++;
                    skyHistogram[state.skyLight()]++;
                    lighting.append(prefix).append(x).append(',').append(y).append(',').append(z).append(',')
                            .append(chunkX).append(',').append(chunkZ).append(',').append(cell(state.material())).append(',')
                            .append(state.emission()).append(',').append(state.blockLight()).append(',').append(state.skyLight()).append(',')
                            .append(Math.max(state.blockLight(), state.skyLight())).append(',').append(captureTime).append('\n');
                    if (state.emission() <= 0) continue;
                    sources++;
                    emission += state.emission();
                    maxEmission = Math.max(maxEmission, state.emission());
                    materials.merge(state.material(), new Totals(1, state.emission()), LightScanCsv::add);
                    rows.append(prefix).append(x).append(',').append(y).append(',').append(z).append(',')
                            .append(chunkX).append(',').append(chunkZ).append(',').append(cell(state.material())).append(',')
                            .append(cell(state.data())).append(',').append(state.emission()).append(',')
                            .append(state.blockLight()).append(',').append(state.skyLight()).append(',').append(captureTime).append('\n');
                }
            }
        }
        long blocks = (long) (maxX - minX + 1) * (maxZ - minZ + 1) * (bounds.maxY() - bounds.minY() + 1);
        String chunkRow = prefix + chunkX + "," + chunkZ + "," + (chunkX * 16 + 7.5) + "," + (chunkZ * 16 + 7.5)
                + "," + minX + "," + maxX + "," + bounds.minY() + "," + bounds.maxY() + "," + minZ + "," + maxZ
                + "," + blocks + "," + sources + "," + emission + "," + maxEmission + "," + captureTime
                + "," + blockLightSum + "," + skyLightSum + "," + maxBlockLight + "," + maxSkyLight
                + histogramValues(blockHistogram) + histogramValues(skyHistogram) + "\n";
        return new Result(rows.toString(), lighting.toString(), chunkRow, sources, materials);
    }

    public static Writer openLighting(Path path) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path), 65536),
                StandardCharsets.UTF_8), 65536);
    }

    private static String histogramHeader(String prefix) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 16; i++) result.append(',').append(prefix).append('_').append(i);
        return result.toString();
    }

    private static String histogramValues(long[] histogram) {
        StringBuilder result = new StringBuilder();
        for (long count : histogram) result.append(',').append(count);
        return result.toString();
    }

    public static Totals add(Totals a, Totals b) {
        return new Totals(a.sources() + b.sources(), a.emission() + b.emission());
    }

    public static void writeMaterials(Writer writer, Map<String, Totals> materials) throws IOException {
        writer.write(MATERIALS_HEADER);
        for (var entry : new TreeMap<>(materials).entrySet()) {
            writer.write(cell(entry.getKey()) + "," + entry.getValue().sources() + "," + entry.getValue().emission() + "\n");
        }
    }

    public static String cell(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
