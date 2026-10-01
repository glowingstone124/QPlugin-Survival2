package vip.qoriginal.quantumplugin.lightscan;

/** Inclusive block coordinates. Null bounds on a job mean the whole world. */
public record ScanBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public ScanBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("扫描范围不能为空。");
        }
        if (minX < -30_000_000 || maxX > 30_000_000 || minZ < -30_000_000 || maxZ > 30_000_000) {
            throw new IllegalArgumentException("X/Z 坐标必须在 ±30000000 内。");
        }
    }

    public boolean includesChunk(int x, int z) {
        return x >= Math.floorDiv(minX, 16) && x <= Math.floorDiv(maxX, 16)
                && z >= Math.floorDiv(minZ, 16) && z <= Math.floorDiv(maxZ, 16);
    }
}
