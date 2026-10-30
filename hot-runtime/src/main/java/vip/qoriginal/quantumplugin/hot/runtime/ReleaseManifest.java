package vip.qoriginal.quantumplugin.hot.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import vip.qoriginal.quantumplugin.hot.api.HotModule;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public record ReleaseManifest(int formatVersion, int apiVersion, String releaseId, Map<String, String> hosts,
                              List<Artifact> modules, List<Artifact> plugins) {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public record Artifact(String id, String version, String file, String sha256, long size) {
        public Artifact {
            requireName(id); requireName(version); requireName(file);
            if (!digest(sha256) || size < 1 || size > 268_435_456L) throw new IllegalArgumentException("Invalid artifact digest or size");
        }
    }
    public ReleaseManifest {
        if (formatVersion != 1 || apiVersion != HotModule.API_VERSION) throw new IllegalArgumentException("Unsupported release/API version");
        requireName(releaseId);
        if (hosts == null || hosts.isEmpty() || hosts.size() > 16) throw new IllegalArgumentException("Missing host requirements");
        hosts.forEach((name, hash) -> { requireName(name); if (!digest(hash)) throw new IllegalArgumentException("Invalid host fingerprint"); });
        hosts = Collections.unmodifiableMap(new TreeMap<>(hosts));
        if (modules == null || modules.isEmpty() || modules.size() > 16 || plugins == null || plugins.size() > 16)
            throw new IllegalArgumentException("Invalid artifact count");
        modules = List.copyOf(modules); plugins = List.copyOf(plugins);
        Set<String> names = new HashSet<>(), ids = new HashSet<>();
        for (Artifact artifact : modules) {
            if (!ids.add(artifact.id()) || !names.add(artifact.file())) throw new IllegalArgumentException("Duplicate module/artifact");
        }
        for (Artifact artifact : plugins) if (!names.add(artifact.file())) throw new IllegalArgumentException("Duplicate artifact file");
    }
    public static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    public static String requireName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}") || name.contains(".."))
            throw new IllegalArgumentException("Invalid release, module or file name");
        return name;
    }
    public static ReleaseManifest read(Path directory) throws IOException {
        Path manifest = directory.resolve("manifest.json");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifest) > 65_536) throw new IOException("Invalid release manifest");
        try {
            ReleaseManifest result = JSON.fromJson(Files.readString(manifest), ReleaseManifest.class);
            if (result == null) throw new IllegalArgumentException("Empty manifest");
            return result;
        } catch (RuntimeException error) { throw new IOException("Invalid release manifest", error); }
    }
    public static Path verify(Path directory, Artifact artifact) throws IOException {
        Path file = directory.resolve(artifact.file());
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != artifact.size()
                || !HostFingerprint.sha256(file).equals(artifact.sha256())) throw new IOException("Artifact checksum mismatch: " + artifact.id());
        return file;
    }
}
