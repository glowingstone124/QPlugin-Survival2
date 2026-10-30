package vip.qoriginal.quantumplugin.hot.runtime;

import vip.qoriginal.quantumplugin.hot.api.HotModule;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

/** Preparation is I/O work; every activation, callback and retirement is serialized by the server thread. */
public final class HotSwapManager implements AutoCloseable {
    public interface StateWriter { void save(String active, String previous) throws IOException; }
    public record Result(boolean ok, String code, String releaseId, String message) { }
    public record ModuleInfo(String id, String version) { }
    public record Status(String activeRelease, String previousRelease, boolean restartRequired, List<ModuleInfo> modules) { }
    private record Loaded(HotModule module, ManagedModuleContext context, URLClassLoader loader) implements AutoCloseable {
        @Override public void close() throws Exception {
            Throwable failure = null;
            try { context.suspend(); } catch (Throwable error) { failure = error; }
            try { module.close(); } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try { loader.close(); } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            rethrow(failure);
        }
    }
    public static final class PreparedRelease implements AutoCloseable {
        private final ReleaseManifest manifest;
        private final List<Loaded> loaded;
        private boolean closed;
        private PreparedRelease(ReleaseManifest manifest, List<Loaded> loaded) { this.manifest = manifest; this.loaded = loaded; }
        public String releaseId() { return manifest.releaseId(); }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            for (int i = loaded.size() - 1; i >= 0; i--) {
                try { loaded.get(i).close(); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            rethrow(failure);
        }
    }
    private final ModulePlatform platform;
    private final String hostFingerprint;
    private final StateWriter stateWriter;
    private PreparedRelease active;
    private volatile Status status = new Status(null, null, false, List.of());
    private boolean stopped;

    public HotSwapManager(ModulePlatform platform, String hostFingerprint, StateWriter stateWriter) {
        this.platform = platform; this.hostFingerprint = hostFingerprint; this.stateWriter = stateWriter;
    }
    public Status status() { return status; }

    public PreparedRelease prepare(Path releaseDirectory) throws Exception {
        ReleaseManifest manifest = ReleaseManifest.read(releaseDirectory);
        if (!manifest.releaseId().equals(releaseDirectory.getFileName().toString())) throw new IOException("Release ID differs from directory");
        if (!hostFingerprint.equals(manifest.hosts().get(platform.hostName())))
            throw new RestartRequiredException("Host code/dependencies differ; install the plugin JAR and restart this backend");
        List<Loaded> loaded = new ArrayList<>();
        PreparedRelease release = new PreparedRelease(manifest, loaded);
        try {
            for (var artifact : manifest.modules()) {
                Path jar = ReleaseManifest.verify(releaseDirectory, artifact);
                ModuleDescriptor descriptor = ModuleDescriptor.read(jar);
                if (!descriptor.id().equals(artifact.id()) || !descriptor.version().equals(artifact.version())) throw new IOException("Module descriptor differs from manifest");
                URLClassLoader loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, HotModule.class.getClassLoader());
                HotModule module;
                try {
                    Class<?> entry = Class.forName(descriptor.entrypoint(), true, loader);
                    if (entry.getClassLoader() != loader || !HotModule.class.isAssignableFrom(entry)) throw new IOException("Entrypoint must implement the shared host API");
                    module = (HotModule) entry.getDeclaredConstructor().newInstance();
                } catch (Throwable error) { loader.close(); throw error; }
                ManagedModuleContext context = new ManagedModuleContext(platform, descriptor.id());
                loaded.add(new Loaded(module, context, loader));
                Files.createDirectories(context.dataDirectory());
                module.prepare(context);
                context.prepared();
            }
            return release;
        } catch (Throwable error) {
            try { release.close(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    public Result activate(PreparedRelease candidate, String expectedActive) {
        return activate(candidate, expectedActive, status.activeRelease());
    }
    public Result restore(PreparedRelease candidate, String previousRelease) {
        if (previousRelease != null) ReleaseManifest.requireName(previousRelease);
        return activate(candidate, null, previousRelease);
    }
    private Result activate(PreparedRelease candidate, String expectedActive, String previousId) {
        platform.requireMainThread();
        if (!stopped && !status.restartRequired() && active != null && active.releaseId().equals(candidate.releaseId())) {
            boolean identical = active.manifest.equals(candidate.manifest);
            if (!retire(candidate)) return new Result(false, "RESTART_REQUIRED", candidate.releaseId(), "Prepared module cleanup failed; restart before another update");
            return new Result(identical, identical ? "ALREADY_ACTIVE" : "IMMUTABLE_RELEASE", candidate.releaseId(),
                identical ? "Identical release is already active" : "An active release ID cannot change its contents");
        }
        if (stopped || status.restartRequired() || !Objects.equals(status.activeRelease(), expectedActive)) {
            if (!retire(candidate)) return new Result(false, "RESTART_REQUIRED", candidate.releaseId(), "Prepared module cleanup failed; restart before another update");
            return new Result(false, stopped ? "STOPPED" : status.restartRequired() ? "RESTART_REQUIRED" : "CONFLICT", candidate.releaseId(), "Active release changed or host cannot accept updates");
        }
        PreparedRelease previous = active;
        Status before = status;
        try {
            if (previous != null) for (Loaded module : previous.loaded) module.context().suspend();
            for (Loaded module : candidate.loaded) module.context().activate();
            for (Loaded module : candidate.loaded) module.module().healthCheck();
            // Persist only after health succeeds. Persistence failure rolls back the in-memory transition as well.
            stateWriter.save(candidate.releaseId(), previousId);
            active = candidate;
            status = new Status(candidate.releaseId(), previousId, false,
                candidate.manifest.modules().stream().map(module -> new ModuleInfo(module.id(), module.version())).toList());
        } catch (Throwable error) {
            boolean cleanupOk = retire(candidate);
            boolean restored = true;
            if (previous != null) {
                for (Loaded module : previous.loaded) {
                    try { module.context().suspend(); module.context().activate(); }
                    catch (Throwable restore) { error.addSuppressed(restore); restored = false; }
                }
            }
            status = new Status(before.activeRelease(), before.previousRelease(), !cleanupOk || !restored, before.modules());
            platform.logger().log(Level.WARNING, "Hot update failed; previous module generation retained", error);
            return new Result(false, status.restartRequired() ? "RESTART_REQUIRED" : "ROLLED_BACK", candidate.releaseId(), "Activation or health check failed; previous release retained");
        }
        if (previous != null && !retire(previous)) {
            status = new Status(status.activeRelease(), status.previousRelease(), true, status.modules());
            return new Result(true, "ACTIVE_RESTART_REQUIRED", candidate.releaseId(), "New release is active; old resource cleanup failed, restart before another update");
        }
        return new Result(true, "ACTIVE", candidate.releaseId(), "Module release activated and health checked");
    }

    private boolean retire(PreparedRelease release) {
        try { release.close(); return true; }
        catch (Throwable error) {
            status = new Status(status.activeRelease(), status.previousRelease(), true, status.modules());
            platform.logger().log(Level.SEVERE, "Module retirement failed; restart before another update", error);
            return false;
        }
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception error) throw error;
        if (failure != null) throw new IllegalStateException(failure);
    }
    @Override public void close() {
        platform.requireMainThread(); stopped = true;
        if (active != null) retire(active);
        active = null;
    }
    public static final class RestartRequiredException extends IOException {
        public RestartRequiredException(String message) { super(message); }
    }
}
