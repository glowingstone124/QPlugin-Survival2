package vip.qoriginal.quantumplugin.hot.runtime;

import vip.qoriginal.quantumplugin.hot.api.HotModule;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** CLI used by Gradle/Orin. Existing release IDs are immutable, including their exported ZIP. */
public final class ReleasePackager {
    private ReleasePackager() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 5) throw new IllegalArgumentException("releaseId outputDirectory --module moduleJar... --host hostName hostJar...");
        Map<String, Path> hosts = new TreeMap<>();
        List<Path> modules = new ArrayList<>();
        for (int i = 2; i < args.length;) {
            if (args[i].equals("--module") && i + 1 < args.length) { modules.add(Path.of(args[i + 1])); i += 2; }
            else if (args[i].equals("--host") && i + 2 < args.length) {
                if (hosts.put(args[i + 1], Path.of(args[i + 2])) != null) throw new IllegalArgumentException("Duplicate host");
                i += 3;
            } else throw new IllegalArgumentException("Expected --module <jar> or --host <name> <jar>");
        }
        Path release = publish(Path.of(args[1]), args[0], modules, hosts);
        System.out.println("Immutable QPlugin release: " + release);
        System.out.println("Orin artifact: " + release.resolveSibling(release.getFileName() + ".zip"));
    }
    public static Path publish(Path output, String releaseId, Path module, Map<String, Path> hosts) throws IOException {
        return publish(output, releaseId, List.of(module), hosts);
    }
    public static Path publish(Path output, String releaseId, List<Path> moduleJars, Map<String, Path> hosts) throws IOException {
        ReleaseManifest.requireName(releaseId);
        if (moduleJars.isEmpty() || moduleJars.size() > 16 || hosts.isEmpty() || hosts.size() > 16)
            throw new IllegalArgumentException("A release needs 1..16 modules and 1..16 hosts");
        Files.createDirectories(output);
        Path staging = Files.createTempDirectory(output, ".staging-");
        try {
            List<ReleaseManifest.Artifact> modules = new ArrayList<>();
            Set<String> files = new HashSet<>();
            for (Path module : moduleJars) {
                ModuleDescriptor descriptor = ModuleDescriptor.read(module);
                Path moduleCopy = staging.resolve(descriptor.id() + ".jar");
                if (!files.add(moduleCopy.getFileName().toString())) throw new IOException("Duplicate module ID");
                Files.copy(module, moduleCopy);
                modules.add(artifact(descriptor.id(), descriptor.version(), moduleCopy));
            }
            List<ReleaseManifest.Artifact> plugins = new ArrayList<>();
            Map<String, String> fingerprints = new TreeMap<>();
            for (var host : hosts.entrySet()) {
                String name = ReleaseManifest.requireName(host.getKey());
                Path destination = staging.resolve(name + ".jar");
                if (!files.add(destination.getFileName().toString())) throw new IOException("Plugin and module filenames collide");
                Files.copy(host.getValue(), destination);
                fingerprints.put(name, HostFingerprint.code(destination));
                plugins.add(artifact(name, releaseId, destination));
            }
            ReleaseManifest manifest = new ReleaseManifest(1, HotModule.API_VERSION, releaseId, fingerprints, modules, plugins);
            AtomicFiles.write(staging.resolve("manifest.json"), ReleaseManifest.JSON.toJson(manifest) + "\n");
            Path target = output.resolve(releaseId);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) verifyExisting(target, manifest);
            else Files.move(staging, target);
            exportZip(output, target);
            return target;
        } finally { if (Files.exists(staging)) deleteStaging(staging); }
    }
    public static ReleaseManifest.Artifact artifact(String id, String version, Path file) throws IOException {
        return new ReleaseManifest.Artifact(id, version, file.getFileName().toString(), HostFingerprint.sha256(file), Files.size(file));
    }
    private static void verifyExisting(Path directory, ReleaseManifest expected) throws IOException {
        if (!ReleaseManifest.read(directory).equals(expected)) throw new IOException("Release ID already contains different bytes; use a new ID");
        for (var artifact : expected.modules()) ReleaseManifest.verify(directory, artifact);
        for (var artifact : expected.plugins()) ReleaseManifest.verify(directory, artifact);
    }
    private static void exportZip(Path output, Path directory) throws IOException {
        Path temporary = Files.createTempFile(output, ".qhot-zip-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary)); var files = Files.list(directory)) {
                for (Path file : files.sorted().toList()) {
                    ZipEntry entry = new ZipEntry(file.getFileName().toString()); entry.setTime(0);
                    zip.putNextEntry(entry); Files.copy(file, zip); zip.closeEntry();
                }
            }
            Path destination = output.resolve(directory.getFileName() + ".zip");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(destination) || !HostFingerprint.sha256(temporary).equals(HostFingerprint.sha256(destination)))
                    throw new IOException("Release ZIP is immutable");
            } else Files.move(temporary, destination);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void deleteStaging(Path directory) throws IOException {
        try (var files = Files.walk(directory)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
}
