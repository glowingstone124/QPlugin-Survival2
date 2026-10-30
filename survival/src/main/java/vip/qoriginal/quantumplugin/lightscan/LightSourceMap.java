package vip.qoriginal.quantumplugin.lightscan;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Palette-indexed, north-up X/Z source heatmap with a hard 8K edge limit. */
final class LightSourceMap {
    static final int MAX_DIMENSION = 8192;

    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int blocksPerPixel;
    private final int width;
    private final int height;
    private final BufferedImage image;
    private final byte[] pixels;

    private LightSourceMap(int minX, int minZ, int maxX, int maxZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        long blockWidth = (long) maxX - minX + 1;
        long blockHeight = (long) maxZ - minZ + 1;
        blocksPerPixel = (int) Math.max(1, Math.max(ceilDiv(blockWidth, MAX_DIMENSION),
                ceilDiv(blockHeight, MAX_DIMENSION)));
        width = Math.toIntExact(ceilDiv(blockWidth, blocksPerPixel));
        height = Math.toIntExact(ceilDiv(blockHeight, blocksPerPixel));
        image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, palette());
        pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    static LightSourceMap create(ScanBounds requestedBounds, Collection<RegionChunkIndex.Position> chunks) {
        if (requestedBounds != null) {
            return new LightSourceMap(requestedBounds.minX(), requestedBounds.minZ(),
                    requestedBounds.maxX(), requestedBounds.maxZ());
        }
        if (chunks.isEmpty()) return new LightSourceMap(0, 0, 0, 0);
        int minChunkX = Integer.MAX_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (var chunk : chunks) {
            minChunkX = Math.min(minChunkX, chunk.x());
            minChunkZ = Math.min(minChunkZ, chunk.z());
            maxChunkX = Math.max(maxChunkX, chunk.x());
            maxChunkZ = Math.max(maxChunkZ, chunk.z());
        }
        return new LightSourceMap(Math.multiplyExact(minChunkX, 16), Math.multiplyExact(minChunkZ, 16),
                Math.addExact(Math.multiplyExact(maxChunkX, 16), 15),
                Math.addExact(Math.multiplyExact(maxChunkZ, 16), 15));
    }

    void add(int x, int z, int emission) {
        if (x < minX || x > maxX || z < minZ || z > maxZ || emission <= 0) return;
        int index = pixelIndex(x, z);
        int current = Byte.toUnsignedInt(pixels[index]);
        if (current == 0) current = 1;
        pixels[index] = (byte) Math.min(255, current + emission);
    }

    void markScanned(int chunkX, int chunkZ, ScanBounds scanBounds) {
        int chunkMinX = Math.max(scanBounds.minX(), Math.multiplyExact(chunkX, 16));
        int chunkMaxX = Math.min(scanBounds.maxX(), Math.addExact(Math.multiplyExact(chunkX, 16), 15));
        int chunkMinZ = Math.max(scanBounds.minZ(), Math.multiplyExact(chunkZ, 16));
        int chunkMaxZ = Math.min(scanBounds.maxZ(), Math.addExact(Math.multiplyExact(chunkZ, 16), 15));
        if (chunkMinX > chunkMaxX || chunkMinZ > chunkMaxZ) return;
        int minPixelX = pixelX(chunkMinX);
        int maxPixelX = pixelX(chunkMaxX);
        int minPixelZ = pixelZ(chunkMinZ);
        int maxPixelZ = pixelZ(chunkMaxZ);
        for (int pixelZ = minPixelZ; pixelZ <= maxPixelZ; pixelZ++) {
            int row = pixelZ * width;
            for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                int index = row + pixelX;
                if (pixels[index] == 0) pixels[index] = 1;
            }
        }
    }

    void write(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            if (!ImageIO.write(image, "PNG", temporary.toFile())) {
                throw new IOException("JRE 中没有可用的 PNG writer");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", "sources.png");
        result.put("width_pixels", width);
        result.put("height_pixels", height);
        result.put("blocks_per_pixel", blocksPerPixel);
        result.put("min_x", minX);
        result.put("max_x", maxX);
        result.put("min_z", minZ);
        result.put("max_z", maxZ);
        result.put("orientation", "north_up; x increases right; z increases down");
        result.put("encoding", "transparent=not scanned; dark=scanned without a source; color=saturating sum of source emission per pixel");
        return result;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int blocksPerPixel() {
        return blocksPerPixel;
    }

    int pixelValue(int pixelX, int pixelZ) {
        return Byte.toUnsignedInt(pixels[pixelZ * width + pixelX]);
    }

    private int pixelIndex(int x, int z) {
        return pixelZ(z) * width + pixelX(x);
    }

    private int pixelX(int x) {
        return (int) (((long) x - minX) / blocksPerPixel);
    }

    private int pixelZ(int z) {
        return (int) (((long) z - minZ) / blocksPerPixel);
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static IndexColorModel palette() {
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        byte[] alpha = new byte[256];
        red[1] = 13;
        green[1] = 18;
        blue[1] = 25;
        alpha[1] = (byte) 255;
        int[][] stops = {
                {24, 9, 52},
                {80, 20, 130},
                {190, 45, 70},
                {246, 164, 45},
                {255, 249, 210}
        };
        for (int i = 2; i < 256; i++) {
            double normalized = Math.sqrt((i - 1) / 254.0);
            double scaled = normalized * (stops.length - 1);
            int lower = Math.min(stops.length - 2, (int) scaled);
            double fraction = scaled - lower;
            red[i] = (byte) interpolate(stops[lower][0], stops[lower + 1][0], fraction);
            green[i] = (byte) interpolate(stops[lower][1], stops[lower + 1][1], fraction);
            blue[i] = (byte) interpolate(stops[lower][2], stops[lower + 1][2], fraction);
            alpha[i] = (byte) 255;
        }
        return new IndexColorModel(8, 256, red, green, blue, alpha);
    }

    private static int interpolate(int start, int end, double fraction) {
        return (int) Math.round(start + (end - start) * fraction);
    }
}
