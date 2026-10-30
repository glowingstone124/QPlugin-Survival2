package vip.qoriginal.quantumplugin.lightscan;

import java.io.IOException;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.util.zip.Deflater;
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
    public record Summary(String chunkCsv, long sources, Map<String, Totals> materials) { }

    public interface Volume {
        State state(int x, int y, int z);
    }

    @FunctionalInterface
    public interface SourceConsumer {
        void accept(int x, int z, int emission);
    }

    private LightScanCsv() { }

    public static Result scan(String world, int chunkX, int chunkZ, long captureTime, ScanBounds bounds,
                              Volume volume, BooleanSupplier cancelled) {
        var sources = new StringWriter();
        var lighting = new StringWriter();
        try {
            Summary summary = scanTo(world, chunkX, chunkZ, captureTime, bounds, volume, cancelled,
                    sources, lighting, (x, z, emission) -> { });
            return new Result(sources.toString(), lighting.toString(), summary.chunkCsv(), summary.sources(),
                    summary.materials());
        } catch (IOException exception) {
            // StringWriter never throws, but keep the streaming implementation's checked contract honest.
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Scans one complete chunk and commits its rows only after calculation succeeds. Rows are retained in
     * bounded segments, avoiding the former multi-megabyte StringBuilder plus String copy on every chunk.
     */
    public static Summary scanTo(String world, int chunkX, int chunkZ, long captureTime, ScanBounds bounds,
                                 Volume volume, BooleanSupplier cancelled, Writer sourcesWriter,
                                 Writer lightingWriter, SourceConsumer sourceConsumer) throws IOException {
        Objects.requireNonNull(world);
        Objects.requireNonNull(bounds);
        Objects.requireNonNull(volume);
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(sourcesWriter);
        Objects.requireNonNull(lightingWriter);
        Objects.requireNonNull(sourceConsumer);
        int minX = Math.max(bounds.minX(), chunkX * 16);
        int maxX = Math.min(bounds.maxX(), chunkX * 16 + 15);
        int minZ = Math.max(bounds.minZ(), chunkZ * 16);
        int maxZ = Math.min(bounds.maxZ(), chunkZ * 16 + 15);
        int chunkBlockX = chunkX * 16;
        int chunkBlockZ = chunkZ * 16;
        SegmentedRows rows = new SegmentedRows();
        SegmentedRows lighting = new SegmentedRows();
        SourcePoints sourcePoints = new SourcePoints();
        Map<String, Totals> materials = new HashMap<>();
        Map<String, String> csvCells = new HashMap<>();
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
                    State state = volume.state(x - chunkBlockX, y, z - chunkBlockZ);
                    int blockLight = state.blockLight();
                    int skyLight = state.skyLight();
                    int sourceEmission = state.emission();
                    blockLightSum += blockLight;
                    skyLightSum += skyLight;
                    maxBlockLight = Math.max(maxBlockLight, blockLight);
                    maxSkyLight = Math.max(maxSkyLight, skyLight);
                    blockHistogram[blockLight]++;
                    skyHistogram[skyLight]++;
                    lighting.append(prefix).append(x).append(',').append(y).append(',').append(z).append(',')
                            .append(chunkX).append(',').append(chunkZ).append(',')
                            .append(csvCells.computeIfAbsent(state.material(), LightScanCsv::cell)).append(',')
                            .append(sourceEmission).append(',').append(blockLight).append(',').append(skyLight).append(',')
                            .append(Math.max(blockLight, skyLight)).append(',').append(captureTime).endRow();
                    if (sourceEmission <= 0) continue;
                    sources++;
                    emission += sourceEmission;
                    maxEmission = Math.max(maxEmission, sourceEmission);
                    materials.merge(state.material(), new Totals(1, sourceEmission), LightScanCsv::add);
                    sourcePoints.add(x, z, sourceEmission);
                    rows.append(prefix).append(x).append(',').append(y).append(',').append(z).append(',')
                            .append(chunkX).append(',').append(chunkZ).append(',')
                            .append(csvCells.computeIfAbsent(state.material(), LightScanCsv::cell)).append(',')
                            .append(csvCells.computeIfAbsent(state.data(), LightScanCsv::cell)).append(',')
                            .append(sourceEmission).append(',').append(blockLight).append(',').append(skyLight).append(',')
                            .append(captureTime).endRow();
                }
            }
        }
        long blocks = (long) (maxX - minX + 1) * (maxZ - minZ + 1) * (bounds.maxY() - bounds.minY() + 1);
        String chunkRow = prefix + chunkX + "," + chunkZ + "," + (chunkX * 16 + 7.5) + "," + (chunkZ * 16 + 7.5)
                + "," + minX + "," + maxX + "," + bounds.minY() + "," + bounds.maxY() + "," + minZ + "," + maxZ
                + "," + blocks + "," + sources + "," + emission + "," + maxEmission + "," + captureTime
                + "," + blockLightSum + "," + skyLightSum + "," + maxBlockLight + "," + maxSkyLight
                + histogramValues(blockHistogram) + histogramValues(skyHistogram) + "\n";
        rows.writeTo(sourcesWriter);
        lighting.writeTo(lightingWriter);
        sourcePoints.forEach(sourceConsumer);
        return new Summary(chunkRow, sources, materials);
    }

    public static Writer openLighting(Path path) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FastGzipOutputStream(Files.newOutputStream(path), 65536),
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

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(OutputStream output, int size) throws IOException {
            super(output, size);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private static final class SegmentedRows {
        private static final int SEGMENT_CHARS = 64 * 1024;
        private static final int COPY_CHARS = 8 * 1024;
        private final List<StringBuilder> segments = new ArrayList<>();
        private StringBuilder current = new StringBuilder(SEGMENT_CHARS + 256);

        SegmentedRows append(String value) {
            current.append(value);
            return this;
        }

        SegmentedRows append(char value) {
            current.append(value);
            return this;
        }

        SegmentedRows append(int value) {
            current.append(value);
            return this;
        }

        SegmentedRows append(long value) {
            current.append(value);
            return this;
        }

        void endRow() {
            current.append('\n');
            if (current.length() >= SEGMENT_CHARS) {
                segments.add(current);
                current = new StringBuilder(SEGMENT_CHARS + 256);
            }
        }

        void writeTo(Writer writer) throws IOException {
            char[] buffer = new char[COPY_CHARS];
            for (StringBuilder segment : segments) writeSegment(writer, segment, buffer);
            writeSegment(writer, current, buffer);
        }

        private static void writeSegment(Writer writer, StringBuilder segment, char[] buffer) throws IOException {
            for (int offset = 0; offset < segment.length(); offset += buffer.length) {
                int length = Math.min(buffer.length, segment.length() - offset);
                segment.getChars(offset, offset + length, buffer, 0);
                writer.write(buffer, 0, length);
            }
        }
    }

    private static final class SourcePoints {
        private int[] x = new int[64];
        private int[] z = new int[64];
        private byte[] emission = new byte[64];
        private int size;

        void add(int sourceX, int sourceZ, int sourceEmission) {
            if (size == x.length) {
                int capacity = Math.multiplyExact(size, 2);
                x = Arrays.copyOf(x, capacity);
                z = Arrays.copyOf(z, capacity);
                emission = Arrays.copyOf(emission, capacity);
            }
            x[size] = sourceX;
            z[size] = sourceZ;
            emission[size] = (byte) sourceEmission;
            size++;
        }

        void forEach(SourceConsumer consumer) {
            for (int i = 0; i < size; i++) consumer.accept(x[i], z[i], Byte.toUnsignedInt(emission[i]));
        }
    }
}
