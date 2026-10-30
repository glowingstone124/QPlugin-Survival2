#!/usr/bin/env kotlin
@file:Import("json.main.kts")

import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.system.exitProcess

val json: Gson = GsonBuilder().disableHtmlEscaping().create()
val namePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
val hashPattern = Regex("[0-9a-f]{64}")
val supportedApis = setOf(1, 2)
val maxArchiveBytes = 512L * 1024 * 1024

fun safeName(value: String?): String {
    require(value != null && namePattern.matches(value) && ".." !in value) { "Invalid release/module/file name" }
    return value
}

fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString ?: error("Missing string: $name")
fun JsonObject.integer(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
    ?.asInt ?: error("Missing integer: $name")
fun JsonObject.long(name: String): Long = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
    ?.asLong ?: error("Missing integer: $name")
fun JsonObject.bool(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

fun readObject(file: Path, limit: Long = 65_536): JsonObject {
    require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) in 1..limit) {
        "Invalid JSON file: ${file.fileName}"
    }
    return Files.newBufferedReader(file, StandardCharsets.UTF_8).use(JsonParser::parseReader).also {
        require(it.isJsonObject) { "Expected JSON object: ${file.fileName}" }
    }.asJsonObject
}

fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun removeTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file); return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
        }
    })
}

data class ZipMetadata(val name: String, val size: Long, val symlink: Boolean)
fun RandomAccessFile.ushort(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
fun RandomAccessFile.uint(): Long = readUnsignedByte().toLong() or (readUnsignedByte().toLong() shl 8) or
    (readUnsignedByte().toLong() shl 16) or (readUnsignedByte().toLong() shl 24)

fun zipMetadata(archive: Path): List<ZipMetadata> {
    require(!Files.isSymbolicLink(archive) && Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) { "Invalid release archive" }
    RandomAccessFile(archive.toFile(), "r").use { file ->
        val start = (file.length() - 65_557).coerceAtLeast(0)
        var position = file.length() - 22
        var eocd = -1L
        while (position >= start) {
            file.seek(position)
            if (file.uint() == 0x06054b50L) { eocd = position; break }
            position--
        }
        require(eocd >= 0) { "Invalid ZIP end record" }
        file.seek(eocd + 8)
        val diskCount = file.ushort()
        val count = file.ushort()
        val centralSize = file.uint()
        val centralOffset = file.uint()
        require(count == diskCount && count <= 33 && count != 0xffff && centralOffset != 0xffffffffL &&
            centralOffset + centralSize <= eocd) { "Unsupported or oversized release archive" }
        file.seek(centralOffset)
        val result = ArrayList<ZipMetadata>(count)
        repeat(count) {
            require(file.uint() == 0x02014b50L) { "Invalid ZIP central entry" }
            file.skipBytes(4)
            val flags = file.ushort()
            file.skipBytes(6)
            file.uint(); file.uint()
            val size = file.uint()
            val nameLength = file.ushort()
            val extraLength = file.ushort()
            val commentLength = file.ushort()
            file.skipBytes(4)
            val external = file.uint()
            file.skipBytes(4)
            require(nameLength in 1..256 && flags and 1 == 0) { "Invalid ZIP entry" }
            val bytes = ByteArray(nameLength)
            file.readFully(bytes)
            require(bytes.all { it.toInt() in 0..127 }) { "Release names must be ASCII" }
            val name = String(bytes, StandardCharsets.US_ASCII)
            result += ZipMetadata(name, size, (external ushr 16).toInt() and 0xf000 == 0xa000)
            file.skipBytes(extraLength + commentLength)
        }
        require(result.sumOf { it.size } <= maxArchiveBytes) { "Release archive exceeds limits" }
        return result
    }
}

fun unpack(archive: Path): Path {
    val metadata = zipMetadata(archive)
    val names = linkedSetOf<String>()
    metadata.forEach {
        safeName(it.name)
        require(!it.symlink && !it.name.endsWith('/') && names.add(it.name)) { "Duplicate, directory or symlink ZIP entry" }
    }
    val temporary = Files.createTempDirectory("qhot-unpack-")
    try {
        val expected = metadata.associateBy { it.name }
        ZipInputStream(Files.newInputStream(archive)).use { input ->
            var count = 0
            while (true) {
                val entry = input.nextEntry ?: break
                val info = expected[entry.name] ?: error("ZIP entry differs from central directory")
                val target = temporary.resolve(entry.name)
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                    var copied = 0L
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= info.size) { "ZIP entry exceeds declared size" }
                        output.write(buffer, 0, read)
                    }
                    require(copied == info.size) { "ZIP entry size mismatch" }
                }
                count++
            }
            require(count == metadata.size) { "ZIP entry count mismatch" }
        }
        return temporary
    } catch (error: Throwable) {
        removeTree(temporary); throw error
    }
}

fun validate(directory: Path): JsonObject {
    require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Invalid release directory" }
    val manifest = readObject(directory.resolve("manifest.json"))
    require(manifest.integer("formatVersion") == 1 && manifest.integer("apiVersion") in supportedApis) { "Unsupported release format/API" }
    safeName(manifest.string("releaseId"))
    val hosts = manifest.getAsJsonObject("hosts") ?: error("Missing host requirements")
    require(hosts.size() in 1..16)
    hosts.entrySet().forEach { (host, digest) ->
        safeName(host); require(digest.isJsonPrimitive && hashPattern.matches(digest.asString)) { "Invalid host fingerprint" }
    }
    val modules = manifest.getAsJsonArray("modules") ?: error("Missing modules")
    val plugins = manifest.getAsJsonArray("plugins") ?: error("Missing plugins")
    require(modules.size() in 1..16 && plugins.size() <= 16) { "Invalid artifact count" }
    val files = linkedSetOf<String>()
    val ids = linkedSetOf<String>()
    for ((artifact, module) in modules.map { it.asJsonObject to true } + plugins.map { it.asJsonObject to false }) {
        val id = safeName(artifact.string("id"))
        safeName(artifact.string("version"))
        val fileName = safeName(artifact.string("file"))
        require(files.add(fileName) && (!module || ids.add(id))) { "Duplicate artifact" }
        val file = directory.resolve(fileName)
        val size = artifact.long("size")
        require(hashPattern.matches(artifact.string("sha256")) && size in 1..268_435_456 &&
            !Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(file) == size && sha256(file) == artifact.string("sha256")) { "Artifact mismatch: $fileName" }
    }
    val actual = Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }
    require(actual == files + "manifest.json") { "Unexpected release files" }
    return manifest
}

fun stage(source: Path, root: Path, manifest: JsonObject): Path {
    val releases = root.resolve("releases")
    Files.createDirectories(releases)
    require(!Files.isSymbolicLink(releases)) { "Release root must not be a symlink" }
    val target = releases.resolve(manifest.string("releaseId"))
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        require(validate(target) == manifest) { "Release ID is immutable" }
        return target
    }
    val temporary = Files.createTempDirectory(releases, ".incoming-")
    try {
        Files.list(source).use { stream -> stream.forEach { Files.copy(it, temporary.resolve(it.fileName)) } }
        require(validate(temporary) == manifest) { "Source release changed while copying" }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.FileAlreadyExistsException) { require(validate(target) == manifest) }
        return target
    } finally { removeTree(temporary) }
}

fun writeAtomic(file: Path, value: JsonObject) {
    Files.createDirectories(file.parent)
    require(!Files.isSymbolicLink(file.parent)) { "Management directory must not be a symlink" }
    val temporary = Files.createTempFile(file.parent, ".request-", ".tmp")
    try {
        Files.writeString(temporary, json.toJson(value), StandardCharsets.UTF_8)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}

fun deploy(source: Path, root: Path, timeout: Double, expectedHost: String?): JsonObject {
    val status = readObject(root.resolve("status.json"))
    if (expectedHost != null) require(status.string("hostName") == safeName(expectedHost)) {
        "Wrong backend: expected $expectedHost, found ${status.string("hostName")}"
    }
    require(status.integer("formatVersion") == 1 && status.integer("apiVersion") in supportedApis && status.bool("running") &&
        status.string("phase") == "ready" && !status.bool("restartRequired") &&
        kotlin.math.abs(System.currentTimeMillis() - status.long("updatedAt")) <= 30_000) {
        "Host is stopped, stale, busy or requires restart"
    }
    val extracted = if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) null else unpack(source)
    val directory = extracted ?: source
    val manifest: JsonObject
    try {
        manifest = validate(directory)
        val host = status.string("hostName")
        if (manifest.integer("apiVersion") != status.integer("apiVersion") ||
            manifest.getAsJsonObject("hosts")?.get(host)?.asString != status.string("hostCodeSha256")) {
            return JsonObject().apply {
                addProperty("ok", false); addProperty("code", "RESTART_REQUIRED")
                addProperty("releaseId", manifest.string("releaseId"))
                addProperty("message", "Host code/dependencies changed; restart with the full plugin")
            }
        }
        stage(directory, root, manifest)
    } finally { if (extracted != null) removeTree(extracted) }
    val requests = root.resolve("requests")
    val results = root.resolve("results")
    Files.createDirectories(requests)
    require(!Files.isSymbolicLink(requests) && !Files.isSymbolicLink(results)) { "Management directory symlink" }
    val id = UUID.randomUUID().toString()
    val request = JsonObject().apply {
        addProperty("requestId", id); addProperty("instanceId", status.string("instanceId"))
        addProperty("releaseId", manifest.string("releaseId")); add("expectedActive", status.get("activeRelease"))
        addProperty("createdAt", System.currentTimeMillis())
    }
    writeAtomic(requests.resolve("$id.json"), request)
    val deadline = System.nanoTime() + (timeout * 1_000_000_000).toLong()
    while (System.nanoTime() < deadline) {
        val receipt = results.resolve("$id.json")
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            val response = readObject(receipt)
            require(response.string("requestId") == id && response.string("instanceId") == status.string("instanceId")) {
                "Deployment receipt does not match request/server process"
            }
            val result = response.getAsJsonObject("result") ?: error("Missing result")
            if (result.bool("ok")) require(result.string("releaseId") == manifest.string("releaseId"))
            result.addProperty("requestId", id)
            return result
        }
        Thread.sleep(min(100, ((deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000)).coerceAtLeast(1))
    }
    error("No host confirmation for request $id; inspect results before retrying")
}

fun option(name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

fun selfTest() {
    val root = Files.createTempDirectory("deploy-hot-kts-test-")
    try {
        val release = Files.createDirectory(root.resolve("release"))
        val module = release.resolve("demo.jar")
        Files.writeString(module, "fixture")
        val fingerprint = "a".repeat(64)
        val manifest = mapOf(
            "formatVersion" to 1, "apiVersion" to 2, "releaseId" to "v2", "hosts" to mapOf("QuantumPlugin" to fingerprint),
            "modules" to listOf(mapOf("id" to "demo", "version" to "v2", "file" to "demo.jar",
                "sha256" to sha256(module), "size" to Files.size(module))), "plugins" to emptyList<Any>(),
        )
        Files.writeString(release.resolve("manifest.json"), json.toJson(manifest))
        val hot = Files.createDirectory(root.resolve("hot"))
        Files.createDirectory(hot.resolve("results"))
        val instance = UUID.randomUUID().toString()
        fun status(hash: String = fingerprint, updated: Long = System.currentTimeMillis()) = mapOf(
            "formatVersion" to 1, "apiVersion" to 2, "hostName" to "QuantumPlugin", "hostCodeSha256" to hash,
            "instanceId" to instance, "running" to true, "phase" to "ready", "restartRequired" to false,
            "updatedAt" to updated, "activeRelease" to "v1",
        )
        Files.writeString(hot.resolve("status.json"), json.toJson(status()))
        val outcome = AtomicReference<Result<JsonObject>>()
        val worker = Thread { outcome.set(runCatching { deploy(release, hot, 10.0, "QuantumPlugin") }) }.apply { start() }
        val requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var request: Path? = null
        while (request == null && System.nanoTime() < requestDeadline) {
            val directory = hot.resolve("requests")
            if (Files.isDirectory(directory)) request = Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") && !it.fileName.toString().startsWith('.') }
                    .findFirst().orElse(null)
            }
            if (request == null) Thread.sleep(10)
        }
        check(request != null) { "No deployment request arrived" }
        val requestValue = readObject(request!!)
        writeAtomic(hot.resolve("results").resolve(request!!.fileName), JsonObject().apply {
            addProperty("requestId", requestValue.string("requestId")); addProperty("instanceId", instance)
            add("result", json.toJsonTree(mapOf("ok" to true, "code" to "ACTIVE", "releaseId" to "v2")))
        })
        worker.join(12_000)
        check(!worker.isAlive && outcome.get().getOrThrow().string("code") == "ACTIVE")
        Files.writeString(hot.resolve("status.json"), json.toJson(status("b".repeat(64))))
        check(deploy(release, hot, 1.0, "QuantumPlugin").string("code") == "RESTART_REQUIRED")
        check(!Files.exists(hot.resolve("requests").resolve("new")))
        Files.writeString(hot.resolve("status.json"), json.toJson(status(updated = System.currentTimeMillis() - 60_000)))
        check(runCatching { deploy(release, hot, 1.0, "QuantumPlugin") }.isFailure)
        Files.writeString(module, "tampered")
        Files.writeString(hot.resolve("status.json"), json.toJson(status()))
        check(runCatching { deploy(release, hot, 1.0, "QuantumPlugin") }.isFailure)
        val bad = root.resolve("bad.zip")
        ZipOutputStream(Files.newOutputStream(bad)).use { zip ->
            zip.putNextEntry(ZipEntry("../escape")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        check(runCatching { unpack(bad) }.isFailure)
        println("PASS deploy-hot KTS: confirmation, restart boundary, stale/tamper/traversal rejection")
    } finally { removeTree(root) }
}

if (args.contains("--self-test")) {
    selfTest()
} else {
    val release = option("--release") ?: error("Usage: deploy-hot.main.kts --release FILE --hot-dir DIR [--host-name NAME] [--timeout SECONDS]")
    val hot = option("--hot-dir") ?: error("--hot-dir is required")
    val timeout = option("--timeout")?.toDoubleOrNull() ?: 60.0
    require(timeout > 0 && timeout <= 120) { "timeout must be between 0 and 120 seconds" }
    try {
        val result = deploy(Path.of(release), Path.of(hot), timeout, option("--host-name"))
        println(json.toJson(result))
        exitProcess(if (result.bool("ok") && !result.string("code").contains("RESTART_REQUIRED")) 0
            else if (result.string("code").contains("RESTART_REQUIRED")) 20 else 1)
    } catch (error: Throwable) {
        System.err.println(json.toJson(mapOf("ok" to false, "code" to "DEPLOYMENT_FAILED", "message" to (error.message ?: error.javaClass.simpleName))))
        exitProcess(1)
    }
}
