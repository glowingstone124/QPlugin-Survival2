package vip.qoriginal.quantumplugin.lightscan;

import com.google.gson.GsonBuilder;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import vip.qoriginal.quantumplugin.CommandMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** One job at a time, bounded chunk-load batches; all world access stays on the server thread. */
public final class LightScanCommand implements TabExecutor, AutoCloseable {
    private static final String PERMISSION = "quantum.lightscan";
    private static final int SNAPSHOT_BATCH_SIZE = 4;
    private final JavaPlugin plugin;
    private final LightScanWorker worker = new LightScanWorker();
    private volatile boolean closed;
    private volatile Job job;

    public LightScanCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        var command = Objects.requireNonNull(plugin.getCommand("lightscan"));
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            CommandMessages.error(sender, "你没有光源扫描权限。");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            Job current = job;
            CommandMessages.info(sender, current == null ? "没有扫描任务。" : current.describe());
        } else if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            Job current = job;
            if (current == null || !current.running()) CommandMessages.warning(sender, "没有正在运行的扫描任务。");
            else {
                current.cancel();
                CommandMessages.info(sender, "已请求取消；已完成的区块数据会保留，并标记为不完整导出。");
            }
        } else if (args.length > 0 && args[0].equalsIgnoreCase("scan")) {
            try {
                start(sender, args);
            } catch (IllegalArgumentException exception) {
                CommandMessages.error(sender, exception.getMessage());
            }
        } else help(sender);
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        if (closed) throw new IllegalArgumentException("扫描服务已关闭。");
        if (job != null && job.running()) throw new IllegalArgumentException("已有扫描任务，请先查询 status 或 cancel。");
        if (args.length != 2 && args.length != 6 && args.length != 8) {
            help(sender);
            return;
        }
        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) throw new IllegalArgumentException("找不到已加载的世界: " + args[1]);
        ScanBounds bounds = null;
        if (args.length >= 6) {
            try {
                int x1 = Integer.parseInt(args[2]);
                int z1 = Integer.parseInt(args[3]);
                int x2 = Integer.parseInt(args[4]);
                int z2 = Integer.parseInt(args[5]);
                int minY = args.length == 8 ? Integer.parseInt(args[6]) : world.getMinHeight();
                int maxY = args.length == 8 ? Integer.parseInt(args[7]) : world.getMaxHeight() - 1;
                if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
                    throw new IllegalArgumentException("Y 范围必须在 " + world.getMinHeight() + " 到 " + (world.getMaxHeight() - 1) + " 内。");
                }
                bounds = new ScanBounds(Math.min(x1, x2), minY, Math.min(z1, z2),
                        Math.max(x1, x2), maxY, Math.max(z1, z2));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("坐标必须为整数。");
            }
        }
        List<RegionChunkIndex.Position> loaded = new ArrayList<>();
        for (var chunk : world.getLoadedChunks()) loaded.add(new RegionChunkIndex.Position(chunk.getX(), chunk.getZ()));
        String dimension = switch (world.getEnvironment()) {
            case NETHER -> "DIM-1";
            case THE_END -> "DIM1";
            default -> "";
        };
        Path worldFolder = world.getWorldFolder().toPath();
        Job next = new Job(world, sender, bounds, loaded, worldFolder, dimension);
        job = next;
        worker.execute(() -> run(next));
        CommandMessages.info(sender, "开始扫描世界 " + next.worldName + " 的光源和空间亮度；完成后同时生成最长边不超过 8K 的 sources.png。/lightscan status 查询进度。只读取已保存/已加载区块，不生成新地形。逐方块亮度数据量较大。");
    }

    private void run(Job current) {
        String outcome = "completed";
        try {
            worker.requireWorkerThread();
            checkCancelled(current);
            Path parent = plugin.getDataFolder().toPath().resolve("light-scans");
            Files.createDirectories(parent);
            String name = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(current.started)
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
            current.output = Files.createDirectory(parent.resolve(name));
            writeMetadata(current, "running", null);
            Path regions = current.worldFolder.resolve(current.dimension).resolve("region");
            if (!Files.exists(regions)) regions = current.worldFolder.resolve("region");
            long discoveryStarted = System.nanoTime();
            var chunks = RegionChunkIndex.discover(regions, current.loaded, current.bounds, () -> current.cancelled || closed);
            current.discoveryNanos = System.nanoTime() - discoveryStarted;
            current.total = chunks.size();
            current.sourceMap = LightSourceMap.create(current.requestedBounds, chunks);
            current.state = "scanning";
            try (var sources = Files.newBufferedWriter(current.output.resolve("sources.csv"), StandardCharsets.UTF_8);
                 var summary = Files.newBufferedWriter(current.output.resolve("chunks.csv"), StandardCharsets.UTF_8);
                 var lighting = LightScanCsv.openLighting(current.output.resolve("lighting.csv.gz"))) {
                sources.write(LightScanCsv.SOURCES_HEADER);
                summary.write(LightScanCsv.CHUNKS_HEADER);
                lighting.write(LightScanCsv.LIGHTING_HEADER);
                var positions = chunks.iterator();
                while (positions.hasNext()) {
                    checkCancelled(current);
                    List<RegionChunkIndex.Position> batch = new ArrayList<>(SNAPSHOT_BATCH_SIZE);
                    while (positions.hasNext() && batch.size() < SNAPSHOT_BATCH_SIZE) {
                        batch.add(positions.next());
                    }
                    List<CompletableFuture<ChunkSnapshot>> requests = snapshots(current, batch);
                    for (int index = 0; index < batch.size(); index++) {
                        RegionChunkIndex.Position pos = batch.get(index);
                        CompletableFuture<ChunkSnapshot> request = requests.get(index);
                        ChunkSnapshot snapshot;
                        long waitStarted = System.nanoTime();
                        try {
                            snapshot = request.get(60, TimeUnit.SECONDS);
                        } finally {
                            current.snapshotWaitNanos += System.nanoTime() - waitStarted;
                            request.cancel(false);
                            current.pendingSnapshots.remove(request);
                        }
                        if (snapshot == null) {
                            current.skipped++;
                            continue;
                        }
                        long processingStarted = System.nanoTime();
                        var result = processSnapshot(current, pos, snapshot, sources, lighting);
                        checkCancelled(current);
                        summary.write(result.chunkCsv());
                        result.materials().forEach((material, totals) -> current.materials.merge(material, totals, LightScanCsv::add));
                        current.sourceMap.markScanned(pos.x(), pos.z(), current.bounds);
                        current.sources += result.sources();
                        current.scanned++;
                        current.processingNanos += System.nanoTime() - processingStarted;
                    }
                }
            } finally {
                current.cancelPendingSnapshots();
            }
            checkCancelled(current);
        } catch (CancellationException | InterruptedException exception) {
            outcome = "cancelled";
        } catch (Exception exception) {
            outcome = "failed";
            current.error = exception.toString();
            plugin.getLogger().log(Level.WARNING, "光源扫描失败", exception);
        } finally {
            // Allow a partial export to be finalized even after an executor interruption.
            Thread.interrupted();
            if (current.cancelled || closed) outcome = "cancelled";
            if (current.output != null) {
                try {
                    try (var writer = Files.newBufferedWriter(current.output.resolve("materials.csv"), StandardCharsets.UTF_8)) {
                        LightScanCsv.writeMaterials(writer, current.materials);
                    }
                    LightSourceMap sourceMap = current.sourceMap;
                    if (sourceMap != null) {
                        try {
                            long mapStarted = System.nanoTime();
                            sourceMap.write(current.output.resolve("sources.png"));
                            current.sourceMapNanos = System.nanoTime() - mapStarted;
                            current.sourceMapMetadata = sourceMap.metadata();
                        } finally {
                            // Completed jobs stay queryable through /lightscan status; do not retain up to 64 MiB.
                            current.sourceMap = null;
                        }
                    }
                    if (current.cancelled || closed) outcome = "cancelled";
                    writeMetadata(current, outcome, Instant.now());
                } catch (IOException exception) {
                    outcome = "failed";
                    current.error = "无法写入导出汇总: " + exception;
                    current.sourceMap = null;
                    plugin.getLogger().log(Level.WARNING, current.error, exception);
                    try {
                        writeMetadata(current, outcome, Instant.now());
                    } catch (IOException metadataException) {
                        plugin.getLogger().log(Level.WARNING, "无法更新失败任务的 metadata.json", metadataException);
                    }
                }
            }
            current.state = outcome;
            notice(current.sender, current.describe());
        }
    }

    private LightScanCsv.Summary processSnapshot(Job current, RegionChunkIndex.Position pos, ChunkSnapshot snapshot,
                                                 java.io.Writer sources, java.io.Writer lighting) throws IOException {
        worker.requireWorkerThread();
        Map<Material, LightScanCsv.State[]> airStates = new EnumMap<>(Material.class);
        Map<BlockData, LightScanCsv.State[]> states = new HashMap<>();
        return LightScanCsv.scanTo(current.worldName, pos.x(), pos.z(), snapshot.getCaptureFullTime(),
                current.bounds, (x, y, z) -> {
                    var material = snapshot.getBlockType(x, y, z);
                    int blockLight = snapshot.getBlockEmittedLight(x, y, z);
                    int skyLight = snapshot.getBlockSkyLight(x, y, z);
                    int lightIndex = blockLight * 16 + skyLight;
                    LightScanCsv.State[] variants;
                    if (material.isAir()) {
                        variants = airStates.computeIfAbsent(material, ignored -> new LightScanCsv.State[256]);
                    } else {
                        BlockData data = snapshot.getBlockData(x, y, z);
                        variants = states.computeIfAbsent(data, ignored -> new LightScanCsv.State[256]);
                        LightScanCsv.State cached = variants[lightIndex];
                        if (cached != null) return cached;
                        int emission = data.getLightEmission();
                        cached = new LightScanCsv.State(material.name(), emission > 0 ? data.getAsString() : "",
                                emission, blockLight, skyLight);
                        variants[lightIndex] = cached;
                        return cached;
                    }
                    LightScanCsv.State cached = variants[lightIndex];
                    if (cached == null) {
                        cached = new LightScanCsv.State(material.name(), "", 0, blockLight, skyLight);
                        variants[lightIndex] = cached;
                    }
                    return cached;
                }, () -> current.cancelled || closed || Thread.currentThread().isInterrupted(), sources, lighting,
                current.sourceMap::add);
    }

    private List<CompletableFuture<ChunkSnapshot>> snapshots(Job current,
                                                              List<RegionChunkIndex.Position> positions) {
        List<CompletableFuture<ChunkSnapshot>> results = new ArrayList<>(positions.size());
        for (int ignored = 0; ignored < positions.size(); ignored++) {
            CompletableFuture<ChunkSnapshot> result = new CompletableFuture<>();
            results.add(result);
            current.pendingSnapshots.add(result);
        }
        if (closed || current.cancelled) {
            results.forEach(result -> result.cancel(false));
            return results;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (closed || current.cancelled || !current.running()) {
                results.forEach(result -> result.cancel(false));
                return;
            }
            if (plugin.getServer().getWorld(current.worldId) != current.world) {
                var exception = new IllegalStateException("扫描世界已卸载。");
                results.forEach(result -> result.completeExceptionally(exception));
                return;
            }
            for (int index = 0; index < positions.size(); index++) {
                requestSnapshot(current, positions.get(index), results.get(index));
            }
        });
        return results;
    }

    private void requestSnapshot(Job current, RegionChunkIndex.Position pos,
                                 CompletableFuture<ChunkSnapshot> result) {
        if (closed || current.cancelled || !current.running() || result.isCancelled()) {
            result.cancel(false);
            return;
        }
        boolean previouslyLoaded = current.world.isChunkLoaded(pos.x(), pos.z());
        try {
            current.world.getChunkAtAsync(pos.x(), pos.z(), false).whenComplete((chunk, error) -> {
                // Paper completes chunk loads on the main thread.
                try {
                    if (error != null) result.completeExceptionally(error);
                    else if (closed || current.cancelled || !current.running() || result.isCancelled()) result.cancel(false);
                    else result.complete(chunk == null ? null : chunk.getChunkSnapshot(false, false, false, true));
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                } finally {
                    if (chunk != null && !previouslyLoaded) current.world.unloadChunkRequest(pos.x(), pos.z());
                }
            });
        } catch (Exception exception) {
            result.completeExceptionally(exception);
        }
    }

    private void writeMetadata(Job current, String state, Instant finished) throws IOException {
        worker.requireWorkerThread();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", 2);
        meta.put("status", state);
        meta.put("complete", state.equals("completed") && current.skipped == 0);
        meta.put("world", current.worldName);
        meta.put("world_uuid", current.worldId.toString());
        meta.put("started_at", current.started.toString());
        meta.put("finished_at", finished == null ? null : finished.toString());
        meta.put("scope", current.requestedBounds == null ? "saved_and_loaded_chunks" : "bounded_saved_and_loaded_chunks");
        meta.put("bounds_inclusive", current.bounds);
        meta.put("candidate_chunks", current.total);
        meta.put("scanned_chunks", current.scanned);
        meta.put("skipped_chunks", current.skipped);
        meta.put("source_count", current.sources);
        meta.put("source_definition", "BlockData.getLightEmission() > 0; includes natural and artificial block sources; excludes skylight and entity/dynamic lighting");
        meta.put("lighting_definition", "Every block position including air and zero light; stored propagated block light and raw skylight, both 0-15. max_stored_light=max(block_light,sky_light), without time/weather skylight darkening.");
        meta.put("source_map", current.sourceMapMetadata);
        Instant measuredUntil = finished == null ? Instant.now() : finished;
        long elapsedMillis = Math.max(0, Duration.between(current.started, measuredUntil).toMillis());
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("elapsed_ms", elapsedMillis);
        performance.put("discovery_ms", TimeUnit.NANOSECONDS.toMillis(current.discoveryNanos));
        performance.put("snapshot_stall_ms", TimeUnit.NANOSECONDS.toMillis(current.snapshotWaitNanos));
        performance.put("scan_and_export_ms", TimeUnit.NANOSECONDS.toMillis(current.processingNanos));
        performance.put("source_map_write_ms", TimeUnit.NANOSECONDS.toMillis(current.sourceMapNanos));
        performance.put("scanned_chunks_per_second", elapsedMillis == 0 ? 0.0 : current.scanned * 1000.0 / elapsedMillis);
        meta.put("performance", performance);
        meta.put("consistency", "Per-chunk snapshots over time, not an atomic world snapshot. New chunks after discovery are not included.");
        meta.put("error", current.error);
        try (var writer = Files.newBufferedWriter(current.output.resolve("metadata.json"), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(meta, writer);
        }
    }

    private void checkCancelled(Job current) {
        if (closed || current.cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private void notice(CommandSender sender, String message) {
        plugin.getLogger().info(message);
        if (!closed) plugin.getServer().getScheduler().runTask(plugin, () -> CommandMessages.info(sender, message));
    }

    private void help(CommandSender sender) {
        CommandMessages.info(sender, "/lightscan scan <世界> [x1 z1 x2 z2 [minY maxY]]");
        CommandMessages.info(sender, "/lightscan status | cancel；导出到插件目录 light-scans/，含光源 CSV/PNG、空间亮度 CSV.gz、区块热力图数据和材质统计。");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        List<String> options = args.length == 1 ? List.of("scan", "status", "cancel")
                : args.length == 2 && args[0].equalsIgnoreCase("scan")
                ? plugin.getServer().getWorlds().stream().map(World::getName).toList() : List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    @Override
    public void close() {
        closed = true;
        if (job != null && job.running()) job.cancel();
        worker.close();
    }

    private static final class Job {
        final World world;
        final UUID worldId;
        final String worldName;
        final CommandSender sender;
        final ScanBounds requestedBounds;
        final ScanBounds bounds;
        final List<RegionChunkIndex.Position> loaded;
        final Path worldFolder;
        final String dimension;
        final Instant started = Instant.now();
        final Map<String, LightScanCsv.Totals> materials = new HashMap<>();
        volatile boolean cancelled;
        final Set<CompletableFuture<ChunkSnapshot>> pendingSnapshots = ConcurrentHashMap.newKeySet();
        volatile LightSourceMap sourceMap;
        volatile Map<String, Object> sourceMapMetadata;
        volatile String state = "discovering";
        volatile Path output;
        volatile int total;
        volatile int scanned;
        volatile int skipped;
        volatile long sources;
        volatile long discoveryNanos;
        volatile long snapshotWaitNanos;
        volatile long processingNanos;
        volatile long sourceMapNanos;
        volatile String error;

        Job(World world, CommandSender sender, ScanBounds bounds, List<RegionChunkIndex.Position> loaded,
            Path worldFolder, String dimension) {
            this.world = world;
            this.worldId = world.getUID();
            this.worldName = world.getName();
            this.sender = sender;
            this.requestedBounds = bounds;
            this.bounds = bounds == null ? new ScanBounds(-30_000_000, world.getMinHeight(), -30_000_000,
                    30_000_000, world.getMaxHeight() - 1, 30_000_000) : bounds;
            this.loaded = loaded;
            this.worldFolder = worldFolder;
            this.dimension = dimension;
        }

        boolean running() {
            return state.equals("discovering") || state.equals("scanning");
        }

        void cancel() {
            cancelled = true;
            cancelPendingSnapshots();
        }

        void cancelPendingSnapshots() {
            pendingSnapshots.forEach(pending -> pending.cancel(false));
            pendingSnapshots.clear();
        }

        String describe() {
            String status = switch (state) {
                case "discovering" -> "枚举区块";
                case "scanning" -> cancelled ? "正在取消" : "扫描中";
                case "completed" -> "扫描完成";
                case "cancelled" -> "已取消（部分导出）";
                default -> "失败（导出不完整）";
            };
            return worldName + "：" + status + "，区块 " + scanned + "/" + total + "，跳过 " + skipped
                    + "，光源 " + sources + (output == null ? "" : "，目录 " + output)
                    + (error == null ? "" : "，错误 " + error);
        }
    }
}
