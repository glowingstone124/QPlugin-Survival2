#!/usr/bin/env kotlin
@file:Import("json.main.kts")

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.system.exitProcess

val json = Gson()
val scriptDirectory = Path.of(System.getProperty("user.dir")).resolve("scripts").toAbsolutePath()
val repository = scriptDirectory.parent

fun option(name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

fun requiredPath(name: String): Path = Path.of(option(name) ?: error("$name is required")).toAbsolutePath().normalize()
fun readJson(file: Path): JsonObject = JsonParser.parseString(Files.readString(file)).asJsonObject
fun writeJson(file: Path, value: Any?) = Files.writeString(file, json.toJson(value) + "\n", StandardCharsets.UTF_8)
fun JsonObject.text(name: String): String = get(name)?.asString ?: error("Missing $name")
fun JsonObject.number(name: String): Long = get(name)?.asLong ?: error("Missing $name")
fun JsonObject.flag(name: String): Boolean = get(name)?.asBoolean ?: false
fun JsonObject.objectValue(name: String): JsonObject = getAsJsonObject(name) ?: error("Missing object $name")
fun JsonObject.arrayValue(name: String): JsonArray = getAsJsonArray(name) ?: error("Missing array $name")
fun JsonArray.strings(): List<String> = map { it.asString }

class Rcon(server: Path) : AutoCloseable {
    private val config = readJson(server.resolve("validation.json"))
    private val socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", config.objectValue("ports").get("rcon")!!.asInt), 10_000)
        soTimeout = 10_000
    }
    private var sequence = 0

    init {
        send(3, config.text("rcon_password"))
        var response = receive()
        if (response.second == 0) response = receive()
        require(response.first != -1 && response.second == 2) { "Local RCON authentication failed" }
    }

    private fun read(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = socket.getInputStream().read(result, offset, size - offset)
            if (count < 0) error("RCON connection closed")
            offset += count
        }
        return result
    }

    private fun send(kind: Int, text: String): Int {
        sequence++
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val body = ByteBuffer.allocate(8 + textBytes.size + 2).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sequence).putInt(kind).put(textBytes).put(0).put(0).array()
        socket.getOutputStream().write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array())
        socket.getOutputStream().write(body)
        socket.getOutputStream().flush()
        return sequence
    }

    private fun receive(): Triple<Int, Int, String> {
        val size = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
        require(size in 10..4 * 1024 * 1024) { "Invalid RCON packet size" }
        val body = ByteBuffer.wrap(read(size)).order(ByteOrder.LITTLE_ENDIAN)
        val request = body.int
        val kind = body.int
        val text = ByteArray(size - 10)
        body.get(text)
        return Triple(request, kind, String(text, StandardCharsets.UTF_8))
    }

    fun command(command: String): String {
        val request = send(2, command)
        val response = receive()
        require(response.first == request) { "Unexpected RCON response" }
        return response.third.trim()
    }

    fun probe(command: String): JsonObject {
        val line = command("qprobe $command").lineSequence().last()
        val value = JsonParser.parseString(line).asJsonObject
        require(!value.has("error")) { value.get("error")?.asString ?: "Probe failed" }
        return value
    }

    override fun close() = socket.close()
}

fun sample(server: Path, seconds: Double): JsonObject = Rcon(server).use { client ->
    client.probe("tick-reset")
    val start = client.probe("resources")
    Thread.sleep((seconds * 1_000).toLong())
    val end = client.probe("resources")
    val ticks = client.probe("ticks")
    val elapsed = (end.number("nanoTime") - start.number("nanoTime")) / 1e9
    JsonObject().apply {
        addProperty("seconds", elapsed)
        addProperty("cpuPercentOfOneCore", 100 * (end.number("processCpuNanos") - start.number("processCpuNanos")) / 1e9 / elapsed)
        add("ticksMillis", ticks)
        add("start", start)
        add("end", end)
    }
}

fun serveApi(server: Path) {
    val config = readJson(server.resolve("validation.json"))
    val port = config.objectValue("ports").get("api")!!.asInt
    val http = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    http.createContext("/") { exchange ->
        exchange.requestBody.use { it.readAllBytes() }
        val route = exchange.requestURI.path
        val value: Any = when {
            route == "/qo/msglist/download" -> mapOf("messages" to emptyList<Any>())
            route == "/qo/download/registry" -> mapOf("code" to 0, "qq" to 0, "score" to 12, "frozen" to false)
            route == "/qo/authorization/auto-login" -> mapOf("ok" to false)
            route == "/qo/download/logingreeting" -> mapOf("time" to mapOf("time" to 0),
                "online" to listOf(mapOf("id" to 1, "players" to emptyList<Any>()), mapOf("id" to 4, "players" to emptyList<Any>())))
            route == "/qo/download/ip" -> true
            route.endsWith("/download") -> emptyMap<String, Any>()
            else -> mapOf("ok" to true, "result" to true)
        }
        val body = json.toJson(value).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    http.executor = java.util.concurrent.Executors.newCachedThreadPool()
    http.start()
    println("Local QAPI stub listening on loopback:$port")
    Thread.currentThread().join()
}

data class ProcessResult(val code: Int, val stdout: String, val stderr: String)

fun run(command: List<String>, timeoutSeconds: Long = 120): ProcessResult {
    val process = ProcessBuilder(command).start()
    val stdout = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    val stderr = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        error("Command timed out: ${command.joinToString(" ")}")
    }
    return ProcessResult(process.exitValue(), stdout.get(), stderr.get())
}

fun deploy(release: String, hot: Path): ProcessResult = run(listOf(
    "kotlin", scriptDirectory.resolve("deploy-hot.main.kts").toString(), "--release", release, "--hot-dir", hot.toString(),
), 75)

fun sha(file: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
    .joinToString("") { "%02x".format(it) }

fun zipDirectory(source: Path, target: Path) {
    ZipOutputStream(Files.newOutputStream(target)).use { zip ->
        Files.list(source).use { stream -> stream.sorted().forEach { file ->
            zip.putNextEntry(ZipEntry(file.fileName.toString()))
            Files.newInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        } }
    }
}

fun corruptFixture(base: Path, target: Path, kind: String) {
    require(kind == "checksum" || kind == "host-mismatch") { "Unsupported corrupt fixture: $kind" }
    ZipFile(base.toFile()).use { source ->
        val manifest = JsonParser.parseString(
            source.getInputStream(source.getEntry("manifest.json") ?: error("Fixture has no manifest"))
                .bufferedReader(StandardCharsets.UTF_8).use { it.readText() },
        ).asJsonObject
        manifest.addProperty("releaseId", manifest.text("releaseId") + "-$kind")
        if (kind == "host-mismatch") {
            manifest.objectValue("hosts").addProperty("QuantumPlugin", "0".repeat(64))
        }
        ZipOutputStream(Files.newOutputStream(target)).use { output ->
            source.entries().asSequence().forEach { entry ->
                require(!entry.isDirectory) { "Fixture archive contains a directory entry" }
                output.putNextEntry(ZipEntry(entry.name))
                when {
                    entry.name == "manifest.json" -> output.write(json.toJson(manifest).toByteArray(StandardCharsets.UTF_8))
                    kind == "checksum" && entry.name == "sponsor-prefix.jar" -> {
                        source.getInputStream(entry).use { it.copyTo(output) }
                        output.write("tampered".toByteArray(StandardCharsets.US_ASCII))
                    }
                    else -> source.getInputStream(entry).use { it.copyTo(output) }
                }
                output.closeEntry()
            }
        }
    }
}

fun buildFixtures(server: Path, javaHome: Path, label: String): JsonObject {
    val hot = server.resolve("plugins/QuantumPlugin/hot")
    val status = readJson(hot.resolve("status.json"))
    require(status.flag("running") && status.text("phase") == "ready") { "Start the compatible local host first" }
    val apiVersion = status.get("apiVersion")!!.asInt
    val output = server.resolve("fixtures/$label")
    Files.createDirectories(output.parent)
    Files.createDirectory(output)
    val host = server.resolve("plugins/QuantumPlugin.jar")
    val libraries = Files.walk(server.resolve("libraries")).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList()
    }
    val classpath = (listOf(host) + libraries).joinToString(System.getProperty("path.separator"))
    val source = Files.readString(repository.resolve("modules/sponsor-prefix/src/main/java/vip/qoriginal/modules/sponsors/SponsorPrefixModule.java"))
    val releases = JsonObject()
    var lastManifest: MutableMap<String, Any?>? = null
    for (version in listOf("v1", "v2", "broken")) {
        val directory = Files.createDirectories(output.resolve(version))
        val classes = Files.createDirectories(directory.resolve("classes"))
        val entry = directory.resolve("SponsorPrefixModule.java")
        var changed = source.replace("§b[SPONSOR]§f", "§d[${version.uppercase()}]§f")
        val injected = """
            java.util.concurrent.atomic.AtomicLong ticks = (java.util.concurrent.atomic.AtomicLong) System.getProperties()
                .computeIfAbsent("qhot.validation.ticks.$version", key -> new java.util.concurrent.atomic.AtomicLong());
            java.util.concurrent.atomic.AtomicLong events = (java.util.concurrent.atomic.AtomicLong) System.getProperties()
                .computeIfAbsent("qhot.validation.events", key -> new java.util.concurrent.atomic.AtomicLong());
            context.repeatSync(() -> { System.setProperty("qhot.validation.version", "$version"); ticks.incrementAndGet(); }, 1, 1);
            context.listen(org.bukkit.event.server.ServerCommandEvent.class, EventPriority.NORMAL, false, event -> {
                if (event.getCommand().equals("__qhot_bench__")) events.incrementAndGet();
            });
        """.trimIndent()
        changed = changed.replace("// Chambers did not previously install NamePrefix.", injected)
        if (version == "broken") changed = changed.replace("private void update(String text)",
            "@Override public void healthCheck() { throw new IllegalStateException(\"validation health failure\"); }\n    private void update(String text)")
        Files.writeString(entry, changed)
        check(run(listOf(javaHome.resolve("bin/javac").toString(), "-encoding", "UTF-8", "-cp", classpath,
            "-d", classes.toString(), entry.toString())).code == 0)
        val releaseId = "$label-$version"
        val release = Files.createDirectory(directory.resolve(releaseId))
        val module = release.resolve("sponsor-prefix.jar")
        ZipOutputStream(Files.newOutputStream(module)).use { jar ->
            Files.walk(classes).use { stream -> stream.filter(Files::isRegularFile).sorted().forEach { file ->
                jar.putNextEntry(ZipEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.newInputStream(file).use { it.copyTo(jar) }; jar.closeEntry()
            } }
            jar.putNextEntry(ZipEntry("META-INF/qplugin-module.properties"))
            jar.write("id=sponsor-prefix\nversion=$releaseId\napiVersion=$apiVersion\nentrypoint=vip.qoriginal.modules.sponsors.SponsorPrefixModule\n".toByteArray())
            jar.closeEntry()
        }
        val modules = arrayListOf(module)
        ZipFile(host.toFile()).use { jar ->
            jar.entries().asSequence().filter { it.name.startsWith("hot-modules/") && it.name.endsWith(".jar") &&
                !it.name.endsWith("/sponsor-prefix.jar") }.forEach { embedded ->
                val file = release.resolve(Path.of(embedded.name).fileName.toString())
                jar.getInputStream(embedded).use { input -> Files.newOutputStream(file).use(input::copyTo) }
                modules.add(file)
            }
        }
        for ((identifier, entrypoint, sourceFile, before, after) in listOf(
            arrayOf("combat-feedback", "vip.qoriginal.modules.combat.CombatFeedbackModule",
                "modules/combat-feedback/src/main/java/vip/qoriginal/modules/combat/CombatFeedbackModule.java",
                "伤害提示已开启。", "伤害提示已开启[${version.uppercase()}]。"),
            arrayOf("text-displays", "vip.qoriginal.modules.text.TextDisplaysModule",
                "modules/text-displays/src/main/java/vip/qoriginal/modules/text/TextDisplaysModule.java",
                "displays.exec(player, String.join(\" \", args))", "displays.exec(player, \"${version.uppercase()}:\" + String.join(\" \", args))"),
        )) {
            val moduleFile = release.resolve("$identifier.jar")
            if (!Files.exists(moduleFile)) continue
            val build = Files.createDirectory(directory.resolve(identifier))
            val originalSource = Files.readString(repository.resolve(sourceFile))
            require(before in originalSource) { "Fixture marker changed: $identifier" }
            val entrySource = build.resolve(entrypoint.substringAfterLast('.') + ".java")
            Files.writeString(entrySource, originalSource.replace(before, after))
            check(run(listOf(javaHome.resolve("bin/javac").toString(), "-encoding", "UTF-8", "-cp",
                "$classpath${System.getProperty("path.separator")}$moduleFile", "-d", build.toString(), entrySource.toString())).code == 0)
            val replacement = build.resolve(entrypoint.replace('.', '/') + ".class")
            val temporary = directory.resolve("$identifier-new.jar")
            ZipFile(moduleFile.toFile()).use { incoming -> ZipOutputStream(Files.newOutputStream(temporary)).use { outgoing ->
                val className = entrypoint.replace('.', '/') + ".class"
                incoming.entries().asSequence().filter { it.name !in setOf(className, "META-INF/qplugin-module.properties") }.forEach { old ->
                    outgoing.putNextEntry(ZipEntry(old.name)); incoming.getInputStream(old).use { it.copyTo(outgoing) }; outgoing.closeEntry()
                }
                outgoing.putNextEntry(ZipEntry(className)); Files.newInputStream(replacement).use { it.copyTo(outgoing) }; outgoing.closeEntry()
                outgoing.putNextEntry(ZipEntry("META-INF/qplugin-module.properties"))
                outgoing.write("id=$identifier\nversion=$releaseId\napiVersion=$apiVersion\nentrypoint=$entrypoint\n".toByteArray()); outgoing.closeEntry()
            } }
            Files.move(temporary, moduleFile, StandardCopyOption.REPLACE_EXISTING)
        }
        fun artifact(file: Path, id: String, versionValue: String) = linkedMapOf<String, Any>(
            "id" to id, "version" to versionValue, "file" to file.fileName.toString(),
            "sha256" to sha(file), "size" to Files.size(file),
        )
        val descriptors = modules.map { file ->
            ZipFile(file.toFile()).use { jar ->
                val properties = Properties().apply { jar.getInputStream(jar.getEntry("META-INF/qplugin-module.properties")).use { load(it) } }
                artifact(file, properties.getProperty("id"), properties.getProperty("version"))
            }
        }
        Files.copy(host, release.resolve("QuantumPlugin.jar"))
        val manifest = linkedMapOf<String, Any?>(
            "formatVersion" to 1, "apiVersion" to apiVersion, "releaseId" to releaseId,
            "hosts" to mapOf("QuantumPlugin" to status.text("hostCodeSha256")), "modules" to descriptors,
            "plugins" to listOf(artifact(release.resolve("QuantumPlugin.jar"), "QuantumPlugin", releaseId)),
        )
        writeJson(release.resolve("manifest.json"), manifest)
        val archive = output.resolve("$releaseId.zip")
        zipDirectory(release, archive)
        releases.addProperty(version, archive.toString())
        lastManifest = manifest
    }
    val idleBuild = Files.createDirectory(output.resolve("idle"))
    val idleSource = idleBuild.resolve("IdleModule.java")
    Files.writeString(idleSource, "package fixture; public final class IdleModule implements vip.qoriginal.quantumplugin.hot.api.HotModule { public void prepare(vip.qoriginal.quantumplugin.hot.api.ModuleContext context) { } }")
    check(run(listOf(javaHome.resolve("bin/javac").toString(), "-cp", classpath, "-d", idleBuild.toString(), idleSource.toString())).code == 0)
    val idleJar = idleBuild.resolve("validation-idle.jar")
    val idleId = "$label-idle"
    ZipOutputStream(Files.newOutputStream(idleJar)).use { jar ->
        jar.putNextEntry(ZipEntry("fixture/IdleModule.class")); Files.newInputStream(idleBuild.resolve("fixture/IdleModule.class")).use { it.copyTo(jar) }; jar.closeEntry()
        jar.putNextEntry(ZipEntry("META-INF/qplugin-module.properties"));
        jar.write("id=validation-idle\nversion=$idleId\napiVersion=$apiVersion\nentrypoint=fixture.IdleModule\n".toByteArray()); jar.closeEntry()
    }
    val idleArchive = output.resolve("$idleId.zip")
    val idleManifest = lastManifest ?: error("No fixtures built")
    idleManifest["releaseId"] = idleId
    idleManifest["modules"] = listOf(mapOf("id" to "validation-idle", "version" to idleId,
        "file" to idleJar.fileName.toString(), "sha256" to sha(idleJar), "size" to Files.size(idleJar)))
    ZipOutputStream(Files.newOutputStream(idleArchive)).use { zip ->
        for ((name, file) in listOf(idleJar.fileName.toString() to idleJar, "QuantumPlugin.jar" to host)) {
            zip.putNextEntry(ZipEntry(name)); Files.newInputStream(file).use { it.copyTo(zip) }; zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(json.toJson(idleManifest).toByteArray()); zip.closeEntry()
    }
    releases.addProperty("idle", idleArchive.toString())
    writeJson(output.resolve("fixtures.json"), releases)
    return releases
}

fun exercise(server: Path, fixtureFile: Path) {
    val hot = server.resolve("plugins/QuantumPlugin/hot")
    val fixtures = readJson(fixtureFile)
    val evidence = linkedMapOf<String, Any?>("deploys" to arrayListOf<Any>(), "checks" to arrayListOf<String>())
    val checks = evidence.getValue("checks") as MutableList<String>
    val deploys = evidence.getValue("deploys") as MutableList<Any>
    val report = server.resolve("integration.json")
    fun status() = readJson(hot.resolve("status.json"))
    fun check(name: String, condition: Boolean) {
        require(condition) { name }; checks += name; println("PASS $name"); writeJson(report, evidence)
    }
    fun deployVersion(version: String, expected: Int = 0): ProcessResult {
        val start = System.nanoTime()
        val result = deploy(fixtures.text(version), hot)
        deploys += mapOf("version" to version, "exit" to result.code, "seconds" to (System.nanoTime() - start) / 1e9,
            "stdout" to result.stdout.trim(), "stderr" to result.stderr.trim())
        check("deploy $version", result.code == expected)
        if (expected == 0) {
            val receipt = JsonParser.parseString(result.stdout.trim()).asJsonObject
            check("receipt matches published ready status", status().text("activeRelease") == receipt.text("releaseId") && status().text("phase") == "ready")
        }
        return result
    }
    fun messages(value: JsonObject) = value.arrayValue("messages").strings()
    fun settledResources(client: Rcon): JsonObject {
        // Initial async reads enqueue one-shot main-thread tasks. Compare steady registrations.
        Thread.sleep(250)
        return List(4) {
            client.probe("resources").also { Thread.sleep(100) }
        }.minBy { it.number("hostTasks") }
    }
    val initial = status()
    Rcon(server).use { client ->
        check("full plugin enabled", client.probe("resources").flag("hostEnabled"))
        client.probe("spawn QHotTester")
        deployVersion("v1"); client.probe("track")
        val first = client.probe("hot-event QHotTester clear")
        check("v1 business code and daily announcement", "[V1]" in first.text("display") && messages(first).size == 1 && first.text("dailyMarker").isNotBlank())
        check("one announcement per day", messages(client.probe("hot-event QHotTester")).isEmpty())
        val before = settledResources(client); evidence["beforeResources"] = before
        deployVersion("v2"); client.probe("track")
        val second = client.probe("hot-event QHotTester")
        check("v2 code changed and player marker survived", "[V2]" in second.text("display") && messages(second).isEmpty() && second.text("dailyMarker") == first.text("dailyMarker"))
        val stopped = client.probe("resources"); Thread.sleep(400); val advanced = client.probe("resources")
        check("retired v1 tick callback stopped", stopped.get("qhot.validation.ticks.v1")?.asString == advanced.get("qhot.validation.ticks.v1")?.asString)
        check("v2 tick callback running", (advanced.get("qhot.validation.ticks.v2")?.asLong ?: 0) > (stopped.get("qhot.validation.ticks.v2")?.asLong ?: 0))
        val oldState = readJson(hot.resolve("state.json"))
        val failed = deployVersion("broken", 1)
        check("health failure confirmed rollback", JsonParser.parseString(failed.stdout.trim()).asJsonObject.text("code") == "ROLLED_BACK")
        check("failed transaction kept persistent pointers", readJson(hot.resolve("state.json")) == oldState)
        check("old business handlers restored", "[V2]" in client.probe("hot-event QHotTester").text("display") && status().arrayValue("modules").size() == initial.arrayValue("modules").size())
        client.command("qhot rollback")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (status().text("activeRelease") != oldState.text("previous") && System.nanoTime() < deadline) Thread.sleep(50)
        val rolledBack = client.probe("hot-event QHotTester")
        check("manual rollback restored v1 without duplicate announcement",
            "[V1]" in rolledBack.text("display") && messages(rolledBack).isEmpty())

        val stateFile = hot.resolve("state.json")
        val stateBytes = Files.readAllBytes(stateFile)
        val active = status().text("activeRelease")
        Files.delete(stateFile)
        Files.createDirectory(stateFile)
        try {
            val stateFailure = deployVersion("v2", 1)
            check("state write failure rolled back on real filesystem",
                JsonParser.parseString(stateFailure.stdout.trim()).asJsonObject.text("code") == "ROLLED_BACK" &&
                    status().text("activeRelease") == active)
            check("state failure restored live business code",
                "[V1]" in client.probe("hot-event QHotTester").text("display"))
        } finally {
            Files.delete(stateFile)
            Files.write(stateFile, stateBytes)
        }

        repeat(10) { deployVersion(if (it % 2 == 0) "v2" else "v1"); client.probe("track") }
        val finalResources = settledResources(client); evidence["afterResources"] = finalResources
        check("listener and task counts stable", listOf("hostTasks", "modulePlayerJoinHandlers", "moduleCommandHandlers").all {
            finalResources.get(it)?.asString == before.get(it)?.asString
        })
        client.probe("gc"); Thread.sleep(1_000); client.probe("gc"); Thread.sleep(1_000)
        val resources = client.probe("resources"); evidence["resources"] = resources
        check("only active module loaders remain", resources.number("aliveTrackedLoaders") == initial.arrayValue("modules").size().toLong())

        // Corrupt candidates use unique IDs and never alter immutable releases already on the host.
        val base = Path.of(fixtures.text("v2"))
        for ((kind, expected) in listOf("checksum" to 1, "host-mismatch" to 20)) {
            val archive = fixtureFile.parent.resolve("$kind.zip")
            corruptFixture(base, archive, kind)
            fixtures.addProperty(kind, archive.toString())
            val activeBeforeFailure = status().text("activeRelease")
            deployVersion(kind, expected)
            check("$kind did not alter active version", status().text("activeRelease") == activeBeforeFailure)
        }

        val config = server.resolve("daily-login-ad.json")
        val original = if (Files.exists(config)) Files.readAllBytes(config) else null
        try {
            writeJson(config, mapOf("template" to "Local announcement {url}", "url" to "https://example.org/local"))
            Thread.sleep(6_000)
            val announced = client.probe("hot-event QHotTester clear")
            check("daily config live reload", messages(announced) == listOf("Local announcement https://example.org/local"))
            Files.writeString(config, "{invalid", StandardCharsets.UTF_8)
            Thread.sleep(6_000)
            check("invalid config kept last valid message", messages(client.probe("hot-event QHotTester clear")) == messages(announced))
            writeJson(config, mapOf("enabled" to false))
            Thread.sleep(6_000)
            val disabled = client.probe("hot-event QHotTester clear")
            check("disabled announcement preserves empty marker", messages(disabled).isEmpty() && disabled.text("dailyMarker").isEmpty())
        } finally {
            if (original == null) Files.deleteIfExists(config) else Files.write(config, original)
        }
        evidence["finalState"] = status(); writeJson(report, evidence)
    }
    println("Evidence: $report")
}

fun moduleSplit(server: Path, fixtureFile: Path) {
    val hot = server.resolve("plugins/QuantumPlugin/hot")
    val fixtures = readJson(fixtureFile)
    val initial = readJson(hot.resolve("status.json"))
    val checks = arrayListOf<String>()
    val report = linkedMapOf<String, Any?>("checks" to checks, "instanceId" to initial.text("instanceId"))
    fun check(name: String, condition: Boolean) { require(condition) { name }; checks += name; writeJson(server.resolve("module-split-validation.json"), report); println("PASS $name") }
    fun deployVersion(version: String, expected: Int = 0): JsonObject {
        val result = deploy(fixtures.text(version), hot); check("deploy $version", result.code == expected)
        return JsonParser.parseString(result.stdout.trim()).asJsonObject
    }
    fun messages(value: JsonObject) = value.arrayValue("messages").strings()
    Rcon(server).use { client ->
        client.probe("spawn QHotTester"); deployVersion("v1"); client.probe("track")
        val first = client.probe("module-command QHotTester damageindicator enable")
        check("v1 command implementation loaded from new child module", first.flag("indicator") && messages(first).any { "[V1]" in it })
        check("managed command completion active", client.probe("module-tab QHotTester damageindicator d").arrayValue("suggestions").strings() == listOf("disable"))
        check("one hit event produces one chat and one actionbar", messages(client.probe("combat-event QHotTester")) == listOf("Hit!", "Hit!"))
        client.probe("text-target QHotTester"); client.probe("module-command QHotTester summontext First")
        check("v1 text command implementation active", "V1:First" in client.probe("text-count").arrayValue("texts").strings())
        for (activityCommand in listOf("firework launch", "newyeartnt give", "newyeardumplings")) {
            val result = client.probe("module-command QHotTester $activityCommand")
            check("activity window preserved: $activityCommand", messages(result).count { it == "该活动还未开始。" } == 1)
        }
        deployVersion("v2"); client.probe("track")
        check("player preference survives hot replacement", client.probe("module-command QHotTester damageindicator query").flag("indicator"))
        val second = client.probe("module-command QHotTester damageindicator enable")
        check("v2 command replaced v1 without duplicate execution", messages(second).size == 1 && "[V2]" in messages(second).single())
        client.probe("module-command QHotTester summontext Second")
        val texts = client.probe("text-count").arrayValue("texts").strings()
        check("text displays persist while command code changes", "V1:First" in texts && "V2:Second" in texts)
        check("health failure rolls back the entire candidate", deployVersion("broken", 1).text("code") == "ROLLED_BACK")
        val restored = client.probe("module-command QHotTester damageindicator enable")
        check("failed activation restores old command handler", messages(restored).size == 1 && "[V2]" in messages(restored).single())
        client.command("qhot rollback")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var status = readJson(hot.resolve("status.json"))
        while (System.nanoTime() < deadline && !(status.text("activeRelease").endsWith("-v1") && status.text("phase") == "ready")) {
            Thread.sleep(100); status = readJson(hot.resolve("status.json"))
        }
        check("manual rollback restores v1 command implementation", messages(client.probe("module-command QHotTester damageindicator enable")).any { "[V1]" in it })
        check("module events are not duplicated after rollback", messages(client.probe("combat-event QHotTester")) == listOf("Hit!", "Hit!"))
        check("game server process unchanged throughout updates", status.text("instanceId") == initial.text("instanceId"))
        check("all five modules remain active", status.arrayValue("modules").size() == 5 && !status.flag("restartRequired"))
        client.probe("track"); client.probe("gc"); Thread.sleep(500); client.probe("gc"); Thread.sleep(500)
        val resources = client.probe("resources")
        check("old command closures release retired class loaders", resources.number("aliveTrackedLoaders") == 5L)
        report["resources"] = resources; writeJson(server.resolve("module-split-validation.json"), report)
    }
}

fun performance(server: Path, fixtureFile: Path, seconds: Double) {
    require(seconds in 10.0..50.0) { "Use windows between 10 and 50 seconds" }
    val hot = server.resolve("plugins/QuantumPlugin/hot")
    val fixtures = readJson(fixtureFile)
    val report = linkedMapOf<String, Any?>("microbenchmarks" to arrayListOf<Any>(), "idleWindows" to arrayListOf<Any>())
    val destination = server.resolve("performance.json")
    val status = readJson(hot.resolve("status.json"))
    val prefix = "builtin-${status.text("hostCodeSha256").take(12)}-"
    val builtin = Files.list(hot.resolve("releases")).use { stream -> stream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(prefix) }.findFirst().orElseThrow().fileName.toString() }
    fun save() = writeJson(destination, report)
    fun waitRelease(release: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val current = readJson(hot.resolve("status.json"))
            if (current.text("activeRelease") == release && current.text("phase") == "ready") return
            Thread.sleep(50)
        }
        error("Activation did not finish")
    }
    Rcon(server).use { client ->
        repeat(3) { repetition ->
            val file = server.resolve("plugins/QHotProbe/benchmark.json")
            Files.deleteIfExists(file)
            client.probe("benchmark 100000 120")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(100)
            val value = readJson(file); (report.getValue("microbenchmarks") as MutableList<Any>).add(value); save()
            println("Callback benchmark ${repetition + 1}: ${json.toJson(value)}")
        }
        client.command("fakeplayer remove QHotTester")
        for (mode in listOf("business", "idle", "idle", "business")) {
            if (mode == "business") { client.command("qhot apply $builtin"); waitRelease(builtin) }
            else require(deploy(fixtures.text("idle"), hot).code == 0)
            client.probe("gc"); Thread.sleep(3_000)
            val measured = sample(server, seconds)
            measured.addProperty("mode", mode)
            val start = measured.objectValue("start"); val end = measured.objectValue("end")
            measured.addProperty("hotIoCpuPercentOfOneCore", 100 * (end.number("hotIoCpuNanos") - start.number("hotIoCpuNanos")) / 1e9 / measured.get("seconds")!!.asDouble)
            (report.getValue("idleWindows") as MutableList<Any>).add(measured); save()
            println("$mode: ${json.toJson(measured)}")
        }
        client.command("qhot apply $builtin"); waitRelease(builtin)
        report["finalState"] = readJson(hot.resolve("status.json")); save()
    }
    println("Evidence: $destination")
}

fun usage(): Nothing {
    println("""
        Usage: kotlin scripts/paper-tools.main.kts COMMAND --server-dir DIR [options]
        Commands:
          serve-api
          rcon [server command...]
          sample --seconds N
          fixtures --java-home JDK --label NAME
          exercise --fixtures FILE
          module-split --fixtures FILE
          performance --fixtures FILE [--seconds 30]
    """.trimIndent())
    exitProcess(0)
}

if (args.isEmpty() || args.contains("--help")) usage()
val command = args.first()
val server = requiredPath("--server-dir")
when (command) {
    "serve-api" -> serveApi(server)
    "rcon" -> Rcon(server).use { client ->
        val serverCommand = args.drop(1).filterNot { it == "--server-dir" || it == option("--server-dir") }.joinToString(" ")
        println(client.command(serverCommand.ifBlank { "qprobe resources" }))
    }
    "sample" -> println(json.toJson(sample(server, option("--seconds")?.toDoubleOrNull() ?: error("--seconds is required"))))
    "fixtures" -> println(json.toJson(buildFixtures(server, requiredPath("--java-home"), option("--label") ?: error("--label is required"))))
    "exercise" -> exercise(server, requiredPath("--fixtures"))
    "module-split" -> moduleSplit(server, requiredPath("--fixtures"))
    "performance" -> performance(server, requiredPath("--fixtures"), option("--seconds")?.toDoubleOrNull() ?: 30.0)
    else -> error("Unknown command: $command")
}
