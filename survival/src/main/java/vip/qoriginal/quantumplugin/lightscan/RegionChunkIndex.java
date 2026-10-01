package vip.qoriginal.quantumplugin.lightscan;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;

/** Reads only Anvil location headers; block data is always obtained through Paper. */
public final class RegionChunkIndex {
    private static final Pattern FILE_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public record Position(int x, int z) implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            int result = Integer.compare(z, other.z);
            return result == 0 ? Integer.compare(x, other.x) : result;
        }
    }

    private RegionChunkIndex() { }

    public static Set<Position> discover(Path regionDirectory, Collection<Position> loaded, ScanBounds bounds)
            throws IOException {
        return discover(regionDirectory, loaded, bounds, () -> false);
    }

    public static Set<Position> discover(Path regionDirectory, Collection<Position> loaded, ScanBounds bounds,
                                         BooleanSupplier cancelled) throws IOException {
        Set<Position> result = new TreeSet<>();
        for (Position pos : loaded) {
            if (bounds == null || bounds.includesChunk(pos.x(), pos.z())) result.add(pos);
        }
        if (!Files.exists(regionDirectory)) return result;
        try (var paths = Files.newDirectoryStream(regionDirectory, "*.mca")) {
            for (Path path : paths) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                var matcher = FILE_NAME.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                long baseX = (long) regionX * 32;
                long baseZ = (long) regionZ * 32;
                if (baseX < -1_875_008 || baseX > 1_875_000 || baseZ < -1_875_008 || baseZ > 1_875_000) continue;
                if (bounds != null && (baseX + 31 < Math.floorDiv(bounds.minX(), 16)
                        || baseX > Math.floorDiv(bounds.maxX(), 16)
                        || baseZ + 31 < Math.floorDiv(bounds.minZ(), 16)
                        || baseZ > Math.floorDiv(bounds.maxZ(), 16))) continue;
                try (var file = new RandomAccessFile(path.toFile(), "r")) {
                    if (file.length() < 8192) throw new IOException("区域文件头不完整: " + path);
                    for (int i = 0; i < 1024; i++) {
                        int location = file.readInt();
                        if ((location >>> 8) < 2 || (location & 255) == 0) continue;
                        Position pos = new Position((int) baseX + i % 32, (int) baseZ + i / 32);
                        if (bounds == null || bounds.includesChunk(pos.x(), pos.z())) result.add(pos);
                    }
                }
            }
        }
        return result;
    }
}
