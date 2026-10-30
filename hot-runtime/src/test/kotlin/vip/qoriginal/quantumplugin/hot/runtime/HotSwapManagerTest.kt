package vip.qoriginal.quantumplugin.hot.runtime

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import vip.qoriginal.quantumplugin.hot.api.HotModule
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.logging.Logger
import javax.tools.ToolProvider
import kotlin.test.*

class HotSwapManagerTest {
    private val directory = Files.createTempDirectory("qhot-runtime-test")
    private val key = "qhot.test.${UUID.randomUUID()}"
    private val host = jar(directory.resolve("host.jar"), mapOf("host.class" to "stable-host"))
    private val platform = FakePlatform(directory)
    private var saved: Pair<String, String?>? = null
    private var failPersistence = false
    private val manager = HotSwapManager(platform, HostFingerprint.code(host)) { active, previous ->
        if (failPersistence) throw IOException("simulated full disk")
        saved = active to previous
    }

    @AfterTest fun cleanup() {
        manager.close()
        System.clearProperty(key)
        System.clearProperty("$key.close")
        directory.toFile().deleteRecursively()
    }

    @Test fun `real versioned JARs swap implementation and old queued callbacks stay invalid`() {
        val first = publish("v1")
        assertTrue(manager.activate(manager.prepare(first), null).ok())
        val stale = platform.tasks.last().callback
        platform.tick()
        assertEquals("v1", System.getProperty(key))
        val second = publish("v2")
        assertTrue(manager.activate(manager.prepare(second), "v1").ok())
        platform.tick()
        assertEquals("v2", System.getProperty(key))
        stale.run()
        assertEquals("v2", System.getProperty(key))
        assertEquals(1, platform.tasks.count { !it.closed })
        assertEquals("v1", System.getProperty("$key.close"))
        assertEquals("v2" to "v1", saved)
    }

    @Test fun `candidate health failure restores old generation and revokes both old and failed queued callbacks`() {
        manager.activate(manager.prepare(publish("v1")), null)
        val stale = platform.tasks.last().callback
        val outcome = manager.activate(manager.prepare(publish("broken", failHealth = true)), "v1")
        assertFalse(outcome.ok())
        assertEquals("ROLLED_BACK", outcome.code())
        assertEquals("v1", manager.status().activeRelease())
        assertEquals("v1" to null, saved)
        platform.tick()
        assertEquals("v1", System.getProperty(key))
        System.setProperty(key, "sentinel")
        stale.run()
        platform.tasks.first { it.closed && it !== platform.tasks.first() }.callback.run()
        assertEquals("sentinel", System.getProperty(key))
        assertEquals(1, platform.tasks.count { !it.closed })
    }

    @Test fun `persistent pointer failure rolls back without retiring the previous module`() {
        manager.activate(manager.prepare(publish("v1")), null)
        failPersistence = true
        val outcome = manager.activate(manager.prepare(publish("v2")), "v1")
        assertEquals("ROLLED_BACK", outcome.code())
        assertEquals("v1", manager.status().activeRelease())
        assertEquals("v2", System.getProperty("$key.close"))
        platform.tick()
        assertEquals("v1", System.getProperty(key))
    }

    @Test fun `compare and set rejects stale deploy requests before touching active callbacks`() {
        manager.activate(manager.prepare(publish("v1")), null)
        val outcome = manager.activate(manager.prepare(publish("v2")), "wrong-version")
        assertEquals("CONFLICT", outcome.code())
        assertEquals(1, platform.tasks.count { !it.closed })
        platform.tick()
        assertEquals("v1", System.getProperty(key))
    }

    @Test fun `host code and dependency changes require a backend restart`() {
        val otherHost = jar(directory.resolve("changed-host.jar"), mapOf("host.class" to "different-host"))
        val module = module("v1")
        val release = ReleasePackager.publish(directory.resolve("releases"), "v1", module, mapOf("QuantumPlugin" to otherHost))
        assertFailsWith<HotSwapManager.RestartRequiredException> { manager.prepare(release) }
        assertNull(manager.status().activeRelease())
    }

    @Test fun `tampered or symlinked module bytes never load`() {
        val release = publish("v1")
        Files.writeString(release.resolve("demo.jar"), "tampered")
        assertFailsWith<IOException> { manager.prepare(release) }
        assertNull(System.getProperty(key))
        val second = publish("v2")
        val bytes = Files.readAllBytes(second.resolve("demo.jar"))
        val external = directory.resolve("external.jar")
        Files.write(external, bytes)
        Files.delete(second.resolve("demo.jar"))
        Files.createSymbolicLink(second.resolve("demo.jar"), external)
        assertFailsWith<IOException> { manager.prepare(second) }
    }

    @Test fun `release publication is immutable and repeatable`() {
        val module = module("v1")
        val root = directory.resolve("releases")
        val first = ReleasePackager.publish(root, "v1", module, mapOf("QuantumPlugin" to host))
        val zipHash = HostFingerprint.sha256(root.resolve("v1.zip"))
        assertEquals(first, ReleasePackager.publish(root, "v1", module, mapOf("QuantumPlugin" to host)))
        assertEquals(zipHash, HostFingerprint.sha256(root.resolve("v1.zip")))
        assertFailsWith<IOException> { ReleasePackager.publish(root, "v1", module("changed"), mapOf("QuantumPlugin" to host)) }
    }

    @Test fun `multi-module release rolls back every registration when one candidate fails health`() {
        val releases = directory.resolve("releases")
        val first = ReleasePackager.publish(releases, "multi-v1", listOf(module("a1", id = "a"), module("b1", id = "b")), mapOf("QuantumPlugin" to host))
        assertTrue(manager.activate(manager.prepare(first), null).ok())
        val next = ReleasePackager.publish(releases, "multi-v2", listOf(module("a2", id = "a"), module("b2", failHealth = true, id = "b")), mapOf("QuantumPlugin" to host))
        assertEquals("ROLLED_BACK", manager.activate(manager.prepare(next), "multi-v1").code())
        assertEquals("multi-v1", manager.status().activeRelease())
        assertEquals(listOf("a1", "b1"), manager.status().modules().map { it.version() })
        assertEquals(2, platform.tasks.count { !it.closed })
        assertFailsWith<IOException> { ReleasePackager.publish(releases, "duplicates", listOf(module("x"), module("y")), mapOf("QuantumPlugin" to host)) }
    }

    @Test fun `host fingerprint ignores bundled module changes but detects parent class changes`() {
        val first = jar(directory.resolve("host-one.jar"), mapOf("Host.class" to "same", "hot-modules/default.jar" to "v1"))
        val second = jar(directory.resolve("host-two.jar"), mapOf("hot-modules/default.jar" to "v2", "Host.class" to "same"))
        val third = jar(directory.resolve("host-three.jar"), mapOf("Host.class" to "changed", "hot-modules/default.jar" to "v2"))
        assertEquals(HostFingerprint.code(first), HostFingerprint.code(second))
        assertNotEquals(HostFingerprint.code(first), HostFingerprint.code(third))
    }

    @Test fun `restart restoration preserves the last known previous release`() {
        val prepared = manager.prepare(publish("v2"))
        assertTrue(manager.restore(prepared, "v1").ok())
        assertEquals("v1", manager.status().previousRelease())
        assertEquals("v2" to "v1", saved)
    }

    @Test fun `fingerprint encoding cannot hide a removed class inside another resource`() {
        val first = jar(directory.resolve("separate.jar"), mapOf("A.txt" to "prefix", "B.class" to "class-content"))
        val second = jar(directory.resolve("merged.jar"), mapOf("A.txt" to "prefix\u0000B.class\u0000class-content"))
        assertNotEquals(HostFingerprint.code(first), HostFingerprint.code(second))
    }

    @Test fun `a lost success receipt can be retried without resetting the previous release`() {
        manager.activate(manager.prepare(publish("v1")), null)
        val release = publish("v2")
        manager.activate(manager.prepare(release), "v1")
        val duplicate = manager.activate(manager.prepare(release), "v1")
        assertEquals("ALREADY_ACTIVE", duplicate.code())
        assertTrue(duplicate.ok())
        assertEquals("v1", manager.status().previousRelease())
        assertEquals(1, platform.tasks.count { !it.closed })
    }

    @Test fun `shutdown disables all callbacks and prevents further activation`() {
        manager.activate(manager.prepare(publish("v1")), null)
        val callback = platform.tasks.last().callback
        val next = manager.prepare(publish("v2"))
        manager.close()
        System.setProperty(key, "stopped")
        callback.run()
        assertEquals("stopped", System.getProperty(key))
        assertEquals("STOPPED", manager.activate(next, "v1").code())
        assertTrue(platform.tasks.all { it.closed })
    }

    @Test fun `old cleanup failure marks the active host as requiring restart`() {
        manager.activate(manager.prepare(publish("v1", failClose = true)), null)
        val outcome = manager.activate(manager.prepare(publish("v2")), "v1")
        assertTrue(outcome.ok())
        assertEquals("ACTIVE_RESTART_REQUIRED", outcome.code())
        assertTrue(manager.status().restartRequired())
        assertEquals("RESTART_REQUIRED", manager.activate(manager.prepare(publish("v3")), "v2").code())
    }

    @Test fun `duplicate request cannot hide a prepared module cleanup failure`() {
        val release = publish("v1", failClose = true)
        manager.activate(manager.prepare(release), null)
        val outcome = manager.activate(manager.prepare(release), null)
        assertFalse(outcome.ok())
        assertEquals("RESTART_REQUIRED", outcome.code())
        assertEquals("v1", manager.status().activeRelease())
        assertTrue(manager.status().restartRequired())
    }

    @Test fun `module close Error leaves the confirmed new generation running and requires restart`() {
        val source = module("v1", closeError = true)
        val release = ReleasePackager.publish(directory.resolve("releases"), "v1", source, mapOf("QuantumPlugin" to host))
        manager.activate(manager.prepare(release), null)
        assertEquals("ACTIVE_RESTART_REQUIRED", manager.activate(manager.prepare(publish("v2")), "v1").code())
        platform.tick()
        assertEquals("v2", System.getProperty(key))
        assertTrue(manager.status().restartRequired())
    }

    @Test fun `manifest rejects traversal and unsupported API before loading business classes`() {
        assertFailsWith<IllegalArgumentException> { ReleaseManifest.requireName("../server") }
        assertFailsWith<IllegalArgumentException> { ReleaseManifest.requireName("a/b") }
        val release = publish("v1")
        val file = release.resolve("manifest.json")
        Files.writeString(file, Files.readString(file).replace("\"apiVersion\": 1", "\"apiVersion\": 999"))
        assertFailsWith<IOException> { manager.prepare(release) }
    }

    private fun publish(version: String, failHealth: Boolean = false, failClose: Boolean = false): Path =
        ReleasePackager.publish(directory.resolve("releases"), version, module(version, failHealth, failClose), mapOf("QuantumPlugin" to host))

    private fun module(version: String, failHealth: Boolean = false, failClose: Boolean = false, closeError: Boolean = false, id: String = "demo"): Path {
        val build = Files.createTempDirectory(directory, "compile-")
        val source = build.resolve("Entry.java")
        Files.writeString(source, """
            package fixture;
            import vip.qoriginal.quantumplugin.hot.api.*;
            public class Entry implements HotModule {
                public void prepare(ModuleContext context) {
                    context.repeatSync(() -> System.setProperty("$key", "$version"), 0, 1);
                }
                public void healthCheck() { ${if (failHealth) "throw new IllegalStateException(\"bad candidate\");" else ""} }
                public void close() { System.setProperty("$key.close", "$version"); ${if (closeError) "throw new AssertionError(\"cannot close\");" else if (failClose) "throw new IllegalStateException(\"cannot close\");" else ""} }
            }
        """.trimIndent())
        val apiPath = Path.of(HotModule::class.java.protectionDomain.codeSource.location.toURI())
        val bukkitPath = Path.of(Event::class.java.protectionDomain.codeSource.location.toURI())
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-classpath", "$apiPath${java.io.File.pathSeparator}$bukkitPath", "-d", build.toString(), source.toString()))
        val target = build.resolve("module.jar")
        JarOutputStream(Files.newOutputStream(target)).use { jar ->
            jar.putNextEntry(JarEntry("fixture/Entry.class")); jar.write(Files.readAllBytes(build.resolve("fixture/Entry.class"))); jar.closeEntry()
            jar.putNextEntry(JarEntry("META-INF/qplugin-module.properties"))
            jar.write("id=$id\nversion=$version\napiVersion=1\nentrypoint=fixture.Entry\n".toByteArray()); jar.closeEntry()
        }
        return target
    }

    private fun jar(file: Path, entries: Map<String, String>): Path {
        JarOutputStream(Files.newOutputStream(file)).use { jar ->
            entries.forEach { (name, text) -> jar.putNextEntry(JarEntry(name)); jar.write(text.toByteArray()); jar.closeEntry() }
        }
        return file
    }
}

private class FakePlatform(private val directory: Path) : ModulePlatform {
    data class Task(val callback: Runnable, var closed: Boolean = false)
    val tasks = mutableListOf<Task>()
    private val mainThread = Thread.currentThread()
    override fun requireMainThread() { check(Thread.currentThread() === mainThread) }
    override fun hostName() = "QuantumPlugin"
    override fun serverDirectory() = directory
    override fun dataDirectory() = directory.resolve("data")
    override fun logger() = Logger.getAnonymousLogger().apply { level = java.util.logging.Level.OFF }
    override fun <E : Event> listen(type: Class<E>, priority: EventPriority, ignoreCancelled: Boolean, callback: Consumer<E>): AutoCloseable = error("Unused in this fixture")
    override fun repeatSync(callback: Runnable, delayTicks: Long, periodTicks: Long): AutoCloseable {
        requireMainThread()
        val task = Task(callback); tasks += task
        return AutoCloseable { task.closed = true }
    }
    override fun watchTextFile(file: Path, maxBytes: Int, periodTicks: Long, callback: Consumer<String>): AutoCloseable = error("Unused in this fixture")
    fun tick() { tasks.toList().filterNot { it.closed }.forEach { it.callback.run() } }
}
