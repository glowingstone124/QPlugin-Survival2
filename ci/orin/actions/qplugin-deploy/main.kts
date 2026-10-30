import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.RandomAccessFile
import java.net.URI
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
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.min

val compactJson: Gson = GsonBuilder().disableHtmlEscaping().create()
val prettyJson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
val safeNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
val digestPattern = Regex("[0-9a-f]{64}")
val supportedApiVersions = setOf(1, 2)
val maximumArchiveBytes = 512L * 1024 * 1024
val fixedTargets = mapOf(
    "QuantumPlugin" to Path.of("/opt/server/qo2"),
    "QuantumPlugin-Creative" to Path.of("/opt/server/qo_creative"),
)

fun safeName(value: String?): String {
    require(value != null && safeNamePattern.matches(value) && ".." !in value) {
        "Invalid release/module/file name"
    }
    return value
}

fun JsonObject.requiredString(name: String): String {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing string: $name" }
    return value.asString
}

fun JsonObject.requiredInt(name: String): Int {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Missing integer: $name" }
    return value.asInt
}

fun JsonObject.requiredLong(name: String): Long {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Missing integer: $name" }
    return value.asLong
}

fun JsonObject.boolean(name: String, default: Boolean = false): Boolean =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default

fun readJsonObject(file: Path, limit: Long = 65_536): JsonObject {
    require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        "Invalid JSON file: ${file.fileName}"
    }
    val size = Files.size(file)
    require(size in 1..limit) { "Invalid JSON file: ${file.fileName}" }
    val parsed = Files.newBufferedReader(file, StandardCharsets.UTF_8).use(JsonParser::parseReader)
    require(parsed.isJsonObject) { "Expected JSON object: ${file.fileName}" }
    return parsed.asJsonObject
}

fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

data class CentralEntry(val name: String, val size: Long, val symbolicLink: Boolean)

fun RandomAccessFile.readUnsignedShortLe(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
fun RandomAccessFile.readUnsignedIntLe(): Long =
    readUnsignedByte().toLong() or (readUnsignedByte().toLong() shl 8) or
        (readUnsignedByte().toLong() shl 16) or (readUnsignedByte().toLong() shl 24)

fun inspectCentralDirectory(archive: Path): List<CentralEntry> {
    require(!Files.isSymbolicLink(archive) && Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
        "Release archive must be a regular file"
    }
    RandomAccessFile(archive.toFile(), "r").use { file ->
        val length = file.length()
        require(length >= 22) { "Invalid ZIP archive" }
        val searchStart = (length - 65_557).coerceAtLeast(0)
        var eocd = -1L
        var position = length - 22
        while (position >= searchStart) {
            file.seek(position)
            if (file.readUnsignedIntLe() == 0x06054b50L) {
                eocd = position
                break
            }
            position--
        }
        require(eocd >= 0) { "Invalid ZIP end record" }
        file.seek(eocd + 8)
        val entriesOnDisk = file.readUnsignedShortLe()
        val entries = file.readUnsignedShortLe()
        val centralSize = file.readUnsignedIntLe()
        val centralOffset = file.readUnsignedIntLe()
        require(entries == entriesOnDisk && entries <= 33 && entries != 0xffff && centralOffset != 0xffffffffL) {
            "Release archive exceeds limits or uses unsupported multi-disk/ZIP64 layout"
        }
        require(centralOffset + centralSize <= eocd) { "Invalid ZIP central directory bounds" }
        val result = ArrayList<CentralEntry>(entries)
        file.seek(centralOffset)
        repeat(entries) {
            require(file.readUnsignedIntLe() == 0x02014b50L) { "Invalid ZIP central entry" }
            file.skipBytes(4)
            val flags = file.readUnsignedShortLe()
            file.skipBytes(6)
            file.readUnsignedIntLe()
            file.readUnsignedIntLe()
            val uncompressedSize = file.readUnsignedIntLe()
            val nameLength = file.readUnsignedShortLe()
            val extraLength = file.readUnsignedShortLe()
            val commentLength = file.readUnsignedShortLe()
            file.skipBytes(4)
            val externalAttributes = file.readUnsignedIntLe()
            file.skipBytes(4)
            require(nameLength in 1..256 && uncompressedSize <= maximumArchiveBytes) { "Invalid ZIP entry" }
            val rawName = ByteArray(nameLength)
            file.readFully(rawName)
            require(rawName.all { it.toInt() in 0..127 }) { "Release entry names must be ASCII" }
            val name = String(rawName, StandardCharsets.US_ASCII)
            val unixMode = (externalAttributes ushr 16).toInt()
            result += CentralEntry(name, uncompressedSize, unixMode and 0xf000 == 0xa000)
            require(extraLength + commentLength <= 65_535) { "Invalid ZIP entry metadata" }
            file.skipBytes(extraLength + commentLength)
            require(flags and 1 == 0) { "Encrypted ZIP entries are not supported" }
        }
        require(result.sumOf { it.size } <= maximumArchiveBytes) { "Release archive exceeds limits" }
        return result
    }
}

fun unpackArchive(archive: Path): Path {
    val metadata = inspectCentralDirectory(archive)
    val names = linkedSetOf<String>()
    for (entry in metadata) {
        safeName(entry.name)
        require(!entry.symbolicLink && !entry.name.endsWith('/')) { "Directory or symlink archive entry" }
        require(names.add(entry.name)) { "Duplicate archive entry" }
    }
    val directory = Files.createTempDirectory("qhot-unpack-")
    try {
        val expected = metadata.associateBy { it.name }
        ZipInputStream(Files.newInputStream(archive)).use { input ->
            var count = 0
            while (true) {
                val entry = input.nextEntry ?: break
                val info = expected[entry.name] ?: error("ZIP entry differs from its central directory")
                require(!entry.isDirectory) { "Directory archive entry" }
                val destination = directory.resolve(entry.name)
                Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteArray(65_536)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= info.size && copied <= maximumArchiveBytes) { "ZIP entry exceeds declared size" }
                        output.write(buffer, 0, read)
                    }
                    require(copied == info.size) { "ZIP entry size mismatch" }
                }
                input.closeEntry()
                count++
            }
            require(count == metadata.size) { "ZIP entry count mismatch" }
        }
        return directory
    } catch (error: Throwable) {
        deleteTree(directory)
        throw error
    }
}

fun validateRelease(directory: Path): JsonObject {
    require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        "Release directory is invalid or a symlink"
    }
    val manifest = readJsonObject(directory.resolve("manifest.json"))
    require(manifest.requiredInt("formatVersion") == 1 && manifest.requiredInt("apiVersion") in supportedApiVersions) {
        "Unsupported release format/API"
    }
    safeName(manifest.requiredString("releaseId"))
    val hosts = manifest.getAsJsonObject("hosts") ?: error("Missing host requirements")
    require(hosts.size() in 1..16) { "Missing host requirements" }
    for ((host, digest) in hosts.entrySet()) {
        safeName(host)
        require(digest.isJsonPrimitive && digest.asJsonPrimitive.isString && digestPattern.matches(digest.asString)) {
            "Invalid host code fingerprint"
        }
    }
    val modules = manifest.getAsJsonArray("modules") ?: error("Invalid artifact count")
    val plugins = manifest.getAsJsonArray("plugins") ?: error("Invalid artifact count")
    require(modules.size() in 1..16 && plugins.size() <= 16) { "Invalid artifact count" }
    val names = linkedSetOf<String>()
    val moduleIds = linkedSetOf<String>()
    val artifacts = modules.map { it.asJsonObject to true } + plugins.map { it.asJsonObject to false }
    for ((artifact, module) in artifacts) {
        val id = safeName(artifact.requiredString("id"))
        safeName(artifact.requiredString("version"))
        val name = safeName(artifact.requiredString("file"))
        require(names.add(name) && (!module || moduleIds.add(id))) { "Duplicate artifact" }
        val digest = artifact.requiredString("sha256")
        val size = artifact.requiredLong("size")
        val file = directory.resolve(name)
        require(digestPattern.matches(digest) && size in 1..268_435_456L &&
            !Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(file) == size && sha256(file) == digest) {
            "Artifact digest/size mismatch: $name"
        }
    }
    val actual = Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }
    require(actual == names + "manifest.json") { "Release contains unexpected files" }
    return manifest
}

fun stageRelease(source: Path, root: Path, manifest: JsonObject): Path {
    val releases = root.resolve("releases")
    Files.createDirectories(releases)
    require(!Files.isSymbolicLink(releases)) { "Release root must not be a symlink" }
    val target = releases.resolve(manifest.requiredString("releaseId"))
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        require(validateRelease(target) == manifest) { "Release ID is immutable; choose a different ID" }
        return target
    }
    val temporary = Files.createTempDirectory(releases, ".incoming-")
    try {
        Files.list(source).use { files -> files.forEach { Files.copy(it, temporary.resolve(it.fileName), StandardCopyOption.COPY_ATTRIBUTES) } }
        require(validateRelease(temporary) == manifest) { "Source release changed while copying" }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            require(validateRelease(target) == manifest) { "Release ID became occupied by different bytes" }
        }
        return target
    } finally {
        deleteTree(temporary)
    }
}

fun atomicJson(file: Path, value: JsonObject) {
    Files.createDirectories(file.parent)
    require(!Files.isSymbolicLink(file.parent)) { "Management directory must not be a symlink" }
    // Host watchers only consume final *.json request/receipt names.
    val temporary = Files.createTempFile(file.parent, ".request-", ".tmp")
    try {
        Files.writeString(temporary, compactJson.toJson(value), StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

fun deployHot(source: Path, root: Path, timeoutSeconds: Double, expectedHost: String): JsonObject {
    require(timeoutSeconds > 0 && timeoutSeconds <= 120) { "timeout must be between 0 and 120 seconds" }
    val status = readJsonObject(root.resolve("status.json"))
    val host = safeName(expectedHost)
    require(status.requiredString("hostName") == host) {
        "Wrong backend: expected $host, found ${status.get("hostName")?.asString}"
    }
    require(status.requiredInt("formatVersion") == 1 && status.requiredInt("apiVersion") in supportedApiVersions &&
        status.boolean("running") && status.requiredString("phase") == "ready" && !status.boolean("restartRequired") &&
        kotlin.math.abs(System.currentTimeMillis() - status.requiredLong("updatedAt")) <= 30_000) {
        "Host is stopped, stale, busy or requires restart"
    }
    val unpacked = if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) null else unpackArchive(source)
    val directory = unpacked ?: source
    val manifest: JsonObject
    try {
        manifest = validateRelease(directory)
        val hostDigest = manifest.getAsJsonObject("hosts")?.get(host)?.asString
        if (manifest.requiredInt("apiVersion") != status.requiredInt("apiVersion") ||
            hostDigest != status.requiredString("hostCodeSha256")) {
            return JsonObject().apply {
                addProperty("ok", false)
                addProperty("code", "RESTART_REQUIRED")
                addProperty("releaseId", manifest.requiredString("releaseId"))
                addProperty("message", "Host code/dependencies changed; deploy the full plugin JAR during a backend restart")
            }
        }
        stageRelease(directory, root, manifest)
    } finally {
        if (unpacked != null) deleteTree(unpacked)
    }
    val requests = root.resolve("requests")
    val results = root.resolve("results")
    Files.createDirectories(requests)
    require(!Files.isSymbolicLink(requests) && !Files.isSymbolicLink(results)) {
        "Management directories must not be symlinks"
    }
    val requestId = UUID.randomUUID().toString()
    val request = JsonObject().apply {
        addProperty("requestId", requestId)
        addProperty("instanceId", status.requiredString("instanceId"))
        addProperty("releaseId", manifest.requiredString("releaseId"))
        if (status.has("activeRelease") && !status.get("activeRelease").isJsonNull) add("expectedActive", status.get("activeRelease"))
        else add("expectedActive", null)
        addProperty("createdAt", System.currentTimeMillis())
    }
    atomicJson(requests.resolve("$requestId.json"), request)
    val deadline = System.nanoTime() + (timeoutSeconds * 1_000_000_000L).toLong()
    while (System.nanoTime() < deadline) {
        val receipt = results.resolve("$requestId.json")
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            val response = readJsonObject(receipt)
            require(response.requiredString("requestId") == requestId &&
                response.requiredString("instanceId") == status.requiredString("instanceId")) {
                "Deployment receipt does not match request/server process"
            }
            val result = response.getAsJsonObject("result") ?: error("Deployment receipt has no result")
            if (result.boolean("ok")) {
                require(result.requiredString("releaseId") == manifest.requiredString("releaseId")) {
                    "Host confirmed a different release"
                }
            }
            result.addProperty("requestId", requestId)
            return result
        }
        val remainingMillis = (deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000
        Thread.sleep(min(100, remainingMillis).coerceAtLeast(1))
    }
    error("No host confirmation for request $requestId; inspect results before retrying")
}

data class CommandOutput(val code: Int, val output: String)

fun runCommand(arguments: List<String>, timeoutSeconds: Long = 40): CommandOutput {
    val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
    val reader = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        error("Command timed out: ${arguments.firstOrNull()}")
    }
    return CommandOutput(process.exitValue(), reader.get(5, TimeUnit.SECONDS))
}

fun docker(vararg arguments: String): String {
    val result = runCommand(listOf("docker", *arguments), 40)
    require(result.code == 0) { "Docker operation failed: ${result.output.trim()}" }
    return result.output
}

fun findContainer(directory: Path): JsonObject {
    val ids = docker("ps", "-aq").split(Regex("\\s+")).filter(String::isNotBlank)
    val matches = if (ids.isEmpty()) emptyList() else {
        JsonParser.parseString(docker(*arrayOf("inspect", *ids.toTypedArray()))).asJsonArray.map { it.asJsonObject }.filter { container ->
            container.getAsJsonArray("Mounts")?.any { it.asJsonObject.get("Source")?.asString == directory.toString() } == true
        }
    }
    require(matches.size == 1) { "Expected exactly one container mounting the configured backend" }
    return matches.single()
}

fun javaPids(containerId: String): List<Long> =
    docker("top", containerId, "-eo", "pid,comm").lineSequence().drop(1).mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        fields.takeIf { it.size == 2 && it[1] == "java" }?.get(0)?.toLongOrNull()
    }.toList()

fun processStartToken(pid: Long): String {
    val fields = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(')').trim().split(Regex("\\s+"))
    return fields.getOrElse(19) { error("Invalid /proc stat for $pid") }
}

fun processAlive(pid: Long, token: String): Boolean = runCatching {
    val fields = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(')').trim().split(Regex("\\s+"))
    fields.getOrNull(19) == token && fields.firstOrNull() != "Z"
}.getOrDefault(false)

fun javaProcess(containerId: String): Pair<Long, String> {
    val pids = javaPids(containerId)
    require(pids.size == 1) { "Expected exactly one Java process in the backend container" }
    return pids.single() to processStartToken(pids.single())
}

fun resumeBackend(directory: Path, original: JsonObject): JsonObject {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
    while (System.nanoTime() < deadline) {
        val current = runCatching { findContainer(directory) }.getOrNull()
        if (current == null) {
            Thread.sleep(1_000)
            continue
        }
        if (current.requiredString("Id") != original.requiredString("Id")) {
            require(current.get("Name") == original.get("Name") &&
                current.getAsJsonObject("Config")?.get("Image") == original.getAsJsonObject("Config")?.get("Image") &&
                current.getAsJsonObject("HostConfig")?.get("PortBindings") == original.getAsJsonObject("HostConfig")?.get("PortBindings")) {
                "Replacement backend container has unexpected identity/configuration"
            }
        }
        if (current.getAsJsonObject("State")?.get("Running")?.asBoolean == true) {
            if (javaPids(current.requiredString("Id")).isNotEmpty()) return current
        } else if (original.getAsJsonObject("HostConfig")?.get("AutoRemove")?.asBoolean != true) {
            docker("start", current.requiredString("Id"))
            return current
        }
        Thread.sleep(1_000)
    }
    error("Backend supervisor did not restore Java within 45 seconds; no force restart was sent")
}

fun pluginJar(directory: Path): Path {
    val matches = Files.list(directory).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".jar") && !Files.isSymbolicLink(it) }.filter { jar ->
            runCatching {
                ZipFile(jar.toFile()).use { zip ->
                    val entry = zip.getEntry("plugin.yml") ?: return@use false
                    require(entry.size in 1..65_536) { "Oversized plugin.yml" }
                    val metadata = zip.getInputStream(entry).use { String(it.readNBytes(65_537), StandardCharsets.UTF_8) }
                    Regex("(?m)^name:\\s*['\"]?QuantumPlugin['\"]?\\s*$").containsMatchIn(metadata)
                }
            }.getOrDefault(false)
        }.toList()
    }
    require(matches.size == 1) { "Expected exactly one existing QuantumPlugin JAR in this backend" }
    return matches.single()
}

fun updateDirectory(directory: Path): Path {
    var name = "update"
    for (line in Files.readAllLines(directory.resolve("bukkit.yml"))) {
        if (line.trim().startsWith("update-folder:")) name = line.substringAfter(':').trim().trim('\'', '"')
    }
    require(name.isNotBlank()) { "Paper update folder is disabled" }
    val plugins = directory.resolve("plugins").toRealPath()
    val candidate = plugins.resolve(name).normalize().toAbsolutePath()
    require(candidate != plugins && candidate.startsWith(plugins)) { "Update folder must be inside the configured plugins directory" }
    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
        require(!Files.isSymbolicLink(candidate) && candidate.toRealPath().startsWith(plugins)) {
            "Update folder must not escape through a symlink"
        }
    }
    return candidate
}

fun copyTree(source: Path, target: Path) {
    Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.createDirectories(target.resolve(source.relativize(dir)))
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.copy(file, target.resolve(source.relativize(file)), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
            return FileVisitResult.CONTINUE
        }
    })
}

fun installHost(archive: Path, hot: Path, host: String): JsonObject {
    val directory = fixedTargets[host]?.toRealPath() ?: error("Unknown backend host")
    val expectedHot = directory.resolve("plugins/QuantumPlugin/hot").normalize()
    require(!Files.isSymbolicLink(hot) && hot.toRealPath() == expectedHot.toRealPath()) {
        "Backend path does not match its host identity"
    }
    val release = unpackArchive(archive)
    try {
        val manifest = validateRelease(release)
        val artifact = manifest.getAsJsonArray("plugins").map { it.asJsonObject }
            .singleOrNull { it.requiredString("id") == host } ?: error("Release has no full plugin for this host")
        val source = release.resolve(artifact.requiredString("file"))
        val current = pluginJar(directory.resolve("plugins"))
        if (sha256(current) == artifact.requiredString("sha256")) {
            return JsonObject().apply { addProperty("host", host); addProperty("code", "HOST_ALREADY_INSTALLED") }
        }
        var container = findContainer(directory)
        require(container.getAsJsonObject("State")?.get("Running")?.asBoolean == true) {
            "Backend must be running before an explicit upgrade"
        }
        val (pid, token) = javaProcess(container.requiredString("Id"))
        val updates = updateDirectory(directory)
        if (Files.exists(updates, LinkOption.NOFOLLOW_LINKS)) {
            val otherPending = Files.list(updates).use { stream ->
                stream.anyMatch { it.fileName.toString().endsWith(".jar") && it.fileName != current.fileName }
            }
            require(!otherPending) { "Other pending plugin updates exist; review them before restarting" }
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val backup = directory.resolve("backups/qplugin/${manifest.requiredString("releaseId")}-$stamp")
        Files.createDirectories(backup.parent)
        Files.createDirectory(backup)
        Files.copy(current, backup.resolve(current.fileName), StandardCopyOption.COPY_ATTRIBUTES)
        val data = directory.resolve("plugins/QuantumPlugin")
        if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) copyTree(data, backup.resolve("QuantumPlugin"))
        Files.createDirectories(updates)
        require(!Files.isSymbolicLink(updates) && updates.toRealPath().startsWith(directory.resolve("plugins").toRealPath())) {
            "Update folder changed during installation"
        }
        val destination = updates.resolve(current.fileName)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(destination)) { "Pending plugin update must not be a symlink" }
            Files.copy(destination, backup.resolve("pending-${current.fileName}"), StandardCopyOption.COPY_ATTRIBUTES)
        }
        val temporary = Files.createTempFile(updates, ".qplugin-host-", ".jar")
        try {
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use(input::copyTo)
            }
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"))
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        val before = JsonObject().apply {
            addProperty("host", host)
            addProperty("releaseId", manifest.requiredString("releaseId"))
            addProperty("container", container.requiredString("Id").take(12))
            addProperty("oldJar", current.toString())
            addProperty("newSha256", artifact.requiredString("sha256"))
            addProperty("backup", backup.toString())
            addProperty("staged", destination.toString())
        }
        Files.writeString(backup.resolve("receipt.json"), prettyJson.toJson(before), StandardCharsets.UTF_8)
        orin.log("QPlugin host staged: ${compactJson.toJson(before)}")
        require(processAlive(pid, token)) { "Backend process changed before shutdown; staged update was preserved" }
        val process = ProcessHandle.of(pid).orElseThrow { IllegalStateException("Backend process disappeared before shutdown") }
        require(process.destroy()) { "Could not send SIGTERM to the inspected backend process" }
        val shutdownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
        while (processAlive(pid, token) && System.nanoTime() < shutdownDeadline) Thread.sleep(1_000)
        require(!processAlive(pid, token)) { "Graceful shutdown exceeded 150 seconds; no SIGKILL was sent" }
        container = resumeBackend(directory, container)
        before.addProperty("resumedContainer", container.requiredString("Id").take(12))
        val startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        while (System.nanoTime() < startupDeadline) {
            val status = runCatching { readJsonObject(hot.resolve("status.json")) }.getOrNull()
            val expectedDigest = manifest.getAsJsonObject("hosts").get(host).asString
            if (status?.boolean("running") == true && status.requiredString("phase") == "ready" &&
                status.requiredString("hostName") == host && status.requiredString("hostCodeSha256") == expectedDigest &&
                sha256(current) == artifact.requiredString("sha256")) {
                Thread.sleep(3_000)
                val finalStatus = readJsonObject(hot.resolve("status.json"))
                if (finalStatus.boolean("running")) {
                    before.addProperty("phase", "ready")
                    before.addProperty("instanceId", finalStatus.requiredString("instanceId"))
                    Files.writeString(backup.resolve("receipt.json"), prettyJson.toJson(before), StandardCharsets.UTF_8)
                    return before
                }
            }
            Thread.sleep(2_000)
        }
        error("Backend did not load the expected host after restart; backup is at $backup")
    } finally {
        deleteTree(release)
    }
}

val actionWorkspace = Path.of(context.paths?.workspaceDir ?: error("Expose paths context"))
val actionDirectory = Path.of(context.paths?.actionDir ?: error("Expose paths context"))
val actionSettings = Properties().apply {
    val file = actionWorkspace.resolve("qplugin.env")
    if (Files.isRegularFile(file)) Files.newBufferedReader(file).use { load(it) }
}
fun setting(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: actionSettings.getProperty(name)?.takeIf { it.isNotBlank() }

fun buildContext(revision: String, receipt: Path, output: Path): Map<String, String> {
    val proxy = setting("QPLUGIN_HTTP_PROXY") ?: ""
    val javaProxy = if (proxy.isBlank()) "" else URI(proxy).let { uri ->
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/") && uri.query == null && uri.fragment == null) {
            "QPLUGIN_HTTP_PROXY must be an HTTP proxy URL without credentials/path/query"
        }
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        "-Dhttp.proxyHost=${uri.host} -Dhttp.proxyPort=$port -Dhttps.proxyHost=${uri.host} -Dhttps.proxyPort=$port -Dhttp.nonProxyHosts=localhost|127.*|[::1]"
    }
    return mapOf(
        "revision" to revision,
        "receipt" to receipt.toString(),
        "output_archive" to output.toString(),
        "repository" to (setting("QPLUGIN_REPOSITORY") ?: ""),
        "release_store" to (setting("QPLUGIN_RELEASE_STORE") ?: ""),
        "http_proxy" to proxy,
        "java_proxy" to javaProxy,
        "java_home" to (setting("QPLUGIN_JAVA_HOME") ?: ""),
    )
}

fun storeBuiltRelease(source: Path, commit: String): Path {
    require(!Files.isSymbolicLink(source) && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
        "Build output is not a regular file"
    }
    val store = Path.of(setting("QPLUGIN_RELEASE_STORE") ?: error("Set QPLUGIN_RELEASE_STORE"))
    require(store.isAbsolute) { "QPLUGIN_RELEASE_STORE must be absolute" }
    Files.createDirectories(store)
    require(!Files.isSymbolicLink(store)) { "Release store must not be a symlink" }
    val target = store.resolve("$commit.zip")
    val temporary = Files.createTempFile(store, ".qplugin-", ".tmp")
    try {
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"))
        try {
            Files.createLink(target, temporary)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            require(!Files.isSymbolicLink(target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(target) == Files.size(temporary) && sha256(target) == sha256(temporary)) {
                "Release commit already contains different bytes"
            }
        }
        return target
    } finally {
        Files.deleteIfExists(temporary)
    }
}

fun readStoredArchive(commit: String): Path {
    require(commit.matches(Regex("[0-9a-f]{40,64}"))) { "release_commit must be a full Git SHA" }
    val store = Path.of(setting("QPLUGIN_RELEASE_STORE") ?: error("Set QPLUGIN_RELEASE_STORE"))
    require(store.isAbsolute) { "QPLUGIN_RELEASE_STORE must be absolute" }
    val file = store.resolve("$commit.zip")
    require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
        "Build this release before deploying it"
    }
    val unpacked = unpackArchive(file)
    try {
        require(validateRelease(unpacked).requiredString("releaseId") == commit) {
            "Stored release differs from the requested commit"
        }
    } finally {
        deleteTree(unpacked)
    }
    return file
}

fun buildRelease(revision: String, log: StringBuilder? = null): Path {
    val buildNode = setting("QPLUGIN_BUILD_NODE")
    val downloadDirectory = Path.of(setting("QPLUGIN_DOWNLOADS") ?: error("Set QPLUGIN_DOWNLOADS"))
    require(downloadDirectory.isAbsolute) { "QPLUGIN_DOWNLOADS must be absolute" }
    Files.createDirectories(downloadDirectory)
    if (buildNode != null) {
        val job = orin.remote(buildNode).trigger("qplugin-build", mapOf("revision" to revision))
        job.await(1_800).requireSuccess()
        return job.downloadArtifact("qplugin-release", downloadDirectory.resolve("${job.jobId}.zip"))
    }
    val receipt = Files.createTempFile("qplugin-build-", ".txt")
    val output = Files.createTempFile("qplugin-build-", ".zip")
    try {
        val result = orin.executeSh("build.sh", buildContext(revision, receipt, output))
        if (log != null) log.append("\n=== build $revision ===\n").append(result.stdout).append('\n').append(result.stderr)
        result.requireSuccess()
        val lines = Files.readAllLines(receipt)
        require(lines.size == 2 && lines[0].matches(Regex("[0-9a-f]{40,64}"))) { "Invalid build receipt" }
        val stored = storeBuiltRelease(Path.of(lines[1]), lines[0])
        if (context.jobId != null) orin.publishArtifact("qplugin-release", stored.toString())
        else orin.log("Release saved in persistent store: $stored")
        return stored
    } finally {
        Files.deleteIfExists(receipt)
        Files.deleteIfExists(output)
    }
}

fun runBackend(archive: Path, hot: Path, host: String, install: Boolean): JsonObject {
    if (install) orin.log("QPlugin host result: ${compactJson.toJson(installHost(archive, hot, host))}")
    val result = deployHot(archive, hot, 60.0, host)
    orin.log("QPlugin module result: ${compactJson.toJson(result)}")
    require(result.boolean("ok") && !result.requiredString("code").contains("RESTART_REQUIRED")) {
        result.get("message")?.asString ?: "QPlugin deployment failed"
    }
    return result
}

fun deployPipeline() {
    val pipelineName = context.pipeline?.name ?: error("Expose pipeline context for qplugin-deploy")
    val hostName = when (pipelineName) {
        "qplugin-deploy-survival" -> "QuantumPlugin"
        "qplugin-deploy-creative" -> "QuantumPlugin-Creative"
        else -> error("Use pipeline qplugin-deploy-survival or qplugin-deploy-creative")
    }
    val targets = Properties().apply { Files.newBufferedReader(actionDirectory.resolve("targets.properties")).use { load(it) } }
    val hotDirectory = Path.of(targets.getProperty("$pipelineName.hotDir") ?: error("Configure $pipelineName.hotDir")).normalize()
    require(hotDirectory.isAbsolute) { "Backend hotDir must be absolute" }
    val install = context.inputs["install_host"]?.let {
        require(it == "true" || it == "false") { "install_host must be true or false" }
        it == "true"
    } ?: false
    require(!install || context.trigger in listOf("manual", "cluster")) {
        "Full plugin installation requires an explicit manual/cluster trigger"
    }
    require(install || Files.isRegularFile(hotDirectory.resolve("status.json"))) {
        "Install and start the hot-module host before deploying to $hotDirectory"
    }
    val revision = context.inputs["revision"] ?: setting("QPLUGIN_DEFAULT_REVISION") ?: "main"
    val archive = context.inputs["release_commit"]?.let(::readStoredArchive) ?: buildRelease(revision)
    orin.log("QPlugin target: $pipelineName host=$hostName hotDir=$hotDirectory")
    runBackend(archive, hotDirectory, hostName, install)
}

fun readableReport(file: Path) {
    Files.setAttribute(file, "posix:group", Files.getAttribute(actionWorkspace, "posix:group"))
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"))
}

fun automaticCi() {
    val github = context.github
    if (context.trigger == "github.push" && (github == null || github.fullName != "glowingstone124/QPlugin-Survival2" ||
            github.ref != "refs/heads/main" || github.deleted)) {
        orin.log("QPlugin CI skipped: only pushes to glowingstone124/QPlugin-Survival2 main deploy")
        return
    }
    val revision = if (context.trigger == "github.push") github!!.after else context.inputs["revision"]
    require(revision != null && revision.matches(Regex("[0-9a-f]{40,64}")) && revision.any { it != '0' }) {
        "QPlugin CI requires an exact commit SHA"
    }
    val targets = Properties().apply { Files.newBufferedReader(actionDirectory.resolve("targets.properties")).use { load(it) } }
    val log = StringBuilder()
    val archive = buildRelease(revision, log)
    val unpacked = unpackArchive(archive)
    val manifest = try { validateRelease(unpacked) } finally { deleteTree(unpacked) }
    require(manifest.requiredString("releaseId") == revision) { "Release differs from pushed commit" }
    val results = linkedMapOf<String, String>()
    try {
        for ((role, host) in listOf("creative" to "QuantumPlugin-Creative", "survival" to "QuantumPlugin")) {
            val hot = Path.of(targets.getProperty("qplugin-deploy-$role.hotDir") ?: error("Missing $role target")).normalize()
            require(hot.isAbsolute) { "Backend path must be absolute" }
            val before = runCatching { readJsonObject(hot.resolve("status.json")) }.getOrNull()
            require(before == null || before.requiredString("hostName") == host) { "Wrong backend for $role" }
            val fingerprint = manifest.getAsJsonObject("hosts").get(host).asString
            val install = before?.get("hostCodeSha256")?.asString != fingerprint
            orin.log("QPlugin CI $revision -> $role, mode=${if (install) "graceful host update" else "hot modules"}")
            val deployed = runBackend(archive, hot, host, install)
            log.append("\n=== deploy $host ===\n").append(compactJson.toJson(deployed)).append('\n')
            Files.writeString(actionWorkspace.resolve("qplugin-ci-last.log"), log.toString())
            readableReport(actionWorkspace.resolve("qplugin-ci-last.log"))
            val after = readJsonObject(hot.resolve("status.json"))
            require(after.boolean("running") && after.requiredString("phase") == "ready" &&
                after.requiredString("hostName") == host && after.requiredString("hostCodeSha256") == fingerprint &&
                after.requiredString("activeRelease") == revision && !after.boolean("restartRequired") &&
                kotlin.math.abs(System.currentTimeMillis() - after.requiredLong("updatedAt")) < 30_000) {
                "Backend did not confirm a healthy $revision: $role"
            }
            results[role] = if (install) "host-updated" else "hot-updated"
        }
        val summary = JsonObject().apply {
            addProperty("commit", revision)
            addProperty("status", "SUCCESS")
            add("targets", compactJson.toJsonTree(results))
            addProperty("completedAt", System.currentTimeMillis())
        }
        val temporary = Files.createTempFile(actionWorkspace, ".qplugin-ci-", ".json")
        try {
            Files.writeString(temporary, prettyJson.toJson(summary))
            readableReport(temporary)
            Files.move(temporary, actionWorkspace.resolve("qplugin-ci-last-success.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        orin.log("QPlugin CI SUCCESS $revision: creative and survival confirmed")
    } catch (error: Throwable) {
        Files.writeString(actionWorkspace.resolve("qplugin-ci-last.log"), log.toString())
        readableReport(actionWorkspace.resolve("qplugin-ci-last.log"))
        throw error
    }
}

when (context.pipeline?.name) {
    "qplugin-ci" -> automaticCi()
    "qplugin-deploy-survival", "qplugin-deploy-creative" -> deployPipeline()
    else -> error("Unsupported QPlugin pipeline: ${context.pipeline?.name}")
}
