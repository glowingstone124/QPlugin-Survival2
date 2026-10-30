package vip.qoriginal.quantumplugin.hot.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.ZipFile;

public final class HostFingerprint {
    private HostFingerprint() { }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65_536];
            for (int length; (length = input.read(buffer)) != -1;) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    /** Ignores ZIP timestamps/order and the bundled default module, but includes all host code/dependencies. */
    public static String code(Path jar) throws IOException {
        MessageDigest digest = digest();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.stream().filter(entry -> !entry.isDirectory() && !entry.getName().startsWith("hot-modules/")
                && !entry.getName().equals("META-INF/qplugin-build.properties")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            Set<String> names = new HashSet<>();
            for (var entry : entries) {
                if (!names.add(entry.getName())) throw new IOException("Duplicate host JAR entry");
                byte[] name = entry.getName().getBytes(StandardCharsets.UTF_8);
                if (entry.getSize() < 0) throw new IOException("Host JAR entry has unknown length");
                digest.update(ByteBuffer.allocate(12).putInt(name.length).putLong(entry.getSize()).array());
                digest.update(name);
                try (var input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[65_536];
                    for (int length; (length = input.read(buffer)) != -1;) digest.update(buffer, 0, length);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
