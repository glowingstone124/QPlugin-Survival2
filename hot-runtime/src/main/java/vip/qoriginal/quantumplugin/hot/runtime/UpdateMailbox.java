package vip.qoriginal.quantumplugin.hot.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Local-filesystem management channel, never a Minecraft plugin message or public HTTP endpoint. */
public final class UpdateMailbox {
    public record Request(String requestId, String instanceId, String releaseId, String expectedActive, long createdAt) { }
    private final Path root;
    private final String instanceId;
    private final Function<Request, CompletableFuture<HotSwapManager.Result>> apply;
    private final Executor writer;
    private final Logger logger;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public UpdateMailbox(Path root, String instanceId, Function<Request, CompletableFuture<HotSwapManager.Result>> apply,
                         Executor writer, Logger logger) throws IOException {
        this.root = root; this.instanceId = instanceId; this.apply = apply; this.writer = writer; this.logger = logger;
        Files.createDirectories(root.resolve("requests")); Files.createDirectories(root.resolve("results"));
    }
    public void poll() throws IOException {
        try (var entries = Files.list(root.resolve("requests"))) {
            for (Path file : entries.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().limit(16).toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - 5);
                if (!uuid(id) || !inFlight.add(id)) continue;
                if (Files.exists(root.resolve("results").resolve(name))) { Files.deleteIfExists(file); inFlight.remove(id); continue; }
                CompletableFuture<HotSwapManager.Result> result;
                try {
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096)
                        throw new IOException("Invalid request file");
                    Request request = ReleaseManifest.JSON.fromJson(Files.readString(file), Request.class);
                    if (request == null || !id.equals(request.requestId()) || !instanceId.equals(request.instanceId()) ||
                            System.currentTimeMillis() - request.createdAt() < 0 || System.currentTimeMillis() - request.createdAt() > 120_000)
                        throw new IOException("Stale request or different server process");
                    ReleaseManifest.requireName(request.releaseId());
                    if (request.expectedActive() != null) ReleaseManifest.requireName(request.expectedActive());
                    result = apply.apply(request);
                } catch (Exception error) {
                    result = CompletableFuture.completedFuture(new HotSwapManager.Result(false, "INVALID_REQUEST", null, "Invalid or stale request"));
                }
                result.whenCompleteAsync((outcome, error) -> {
                    try {
                        var response = new LinkedHashMap<String, Object>();
                        response.put("requestId", id); response.put("instanceId", instanceId);
                        response.put("result", error == null ? outcome : new HotSwapManager.Result(false, "FAILED", null, "Host update failed"));
                        AtomicFiles.write(root.resolve("results").resolve(name), ReleaseManifest.JSON.toJson(response) + "\n");
                        Files.deleteIfExists(file);
                    } catch (IOException failure) { logger.log(Level.WARNING, "Could not persist update result", failure); }
                    finally { inFlight.remove(id); }
                }, writer);
            }
        }
    }
    private static boolean uuid(String id) { return id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"); }
}
