package vip.qoriginal.quantumplugin.hot.runtime;

import vip.qoriginal.quantumplugin.hot.api.HotModule;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.jar.JarFile;

public record ModuleDescriptor(String id, String version, String entrypoint) {
    public static ModuleDescriptor read(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            var descriptor = archive.getJarEntry("META-INF/qplugin-module.properties");
            if (descriptor == null) throw new IOException("Missing module descriptor");
            Properties fields = new Properties();
            try (var input = archive.getInputStream(descriptor)) {
                byte[] bytes = input.readNBytes(8193);
                if (bytes.length > 8192) throw new IOException("Module descriptor is too large");
                fields.load(new ByteArrayInputStream(bytes));
            }
            String id = ReleaseManifest.requireName(fields.getProperty("id"));
            String version = ReleaseManifest.requireName(fields.getProperty("version"));
            String entrypoint = fields.getProperty("entrypoint", "");
            if (!Integer.toString(HotModule.API_VERSION).equals(fields.getProperty("apiVersion")) ||
                    !entrypoint.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) throw new IOException("Incompatible module API/entrypoint");
            var entries = archive.entries();
            var names = new HashSet<String>();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!names.add(name)) throw new IOException("Duplicate module JAR entry");
                if (name.endsWith(".class") && (name.startsWith("vip/qoriginal/quantumplugin/hot/") || name.startsWith("org/bukkit/")
                        || name.startsWith("net/minecraft/") || name.startsWith("io/papermc/") || name.startsWith("java/")))
                    throw new IOException("Module bundles host/API classes");
            }
            return new ModuleDescriptor(id, version, entrypoint);
        } catch (IllegalArgumentException error) { throw new IOException("Invalid module descriptor", error); }
    }
}
