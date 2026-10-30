import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
val actionDirectory = Path.of(context.paths?.actionDir ?: error("Expose paths context"))
val targetConfig = Properties().apply {
    Files.newBufferedReader(actionDirectory.resolve("targets.properties")).use { load(it) }
}
val targets = linkedMapOf(
    "survival" to Path.of(targetConfig.getProperty("survival.directory") ?: error("Missing survival.directory")),
    "creative" to Path.of(targetConfig.getProperty("creative.directory") ?: error("Missing creative.directory")),
)

fun varInt(value: Int): ByteArray {
    var current = value
    val output = ByteArrayOutputStream(5)
    do {
        var byte = current and 0x7f
        current = current ushr 7
        if (current != 0) byte = byte or 0x80
        output.write(byte)
    } while (current != 0)
    return output.toByteArray()
}

fun readFully(socket: Socket, size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = socket.getInputStream().read(result, offset, size - offset)
        if (read < 0) error("Status connection closed")
        offset += read
    }
    return result
}

fun readVarInt(socket: Socket): Int {
    var value = 0
    for (shift in 0 until 35 step 7) {
        val byte = readFully(socket, 1)[0].toInt() and 0xff
        value = value or ((byte and 0x7f) shl shift)
        if (byte and 0x80 == 0) return value
    }
    error("Invalid status packet")
}

fun ping(port: Int): JsonObject = runCatching {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        socket.soTimeout = 2_000
        val host = "localhost".toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream().apply {
            write(0)
            write(varInt(-1))
            write(varInt(host.size))
            write(host)
            write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
            write(1)
        }.toByteArray()
        socket.getOutputStream().apply {
            write(varInt(body.size))
            write(body)
            write(byteArrayOf(1, 0))
            flush()
        }
        val packetLength = readVarInt(socket)
        require(packetLength in 1..1_048_576) { "Invalid status length" }
        readVarInt(socket)
        val jsonLength = readVarInt(socket)
        require(jsonLength in 1..packetLength) { "Invalid status JSON length" }
        val reply = JsonParser.parseString(String(readFully(socket, jsonLength), StandardCharsets.UTF_8)).asJsonObject
        JsonObject().apply {
            val players = reply.getAsJsonObject("players")
            val version = reply.getAsJsonObject("version")
            if (players?.has("online") == true) add("online", players.get("online"))
            if (players?.has("max") == true) add("maxPlayers", players.get("max"))
            if (version?.has("name") == true) add("version", version.get("name"))
        }
    }
}.getOrElse { error -> JsonObject().apply { addProperty("error", error.message ?: error.javaClass.simpleName) } }

fun properties(file: Path): Map<String, String> = Files.readAllLines(file).mapNotNull { line ->
    line.takeIf { it.isNotBlank() && !it.startsWith('#') && '=' in it }?.let {
        it.substringBefore('=') to it.substringAfter('=')
    }
}.toMap()

fun runCommand(arguments: List<String>, timeoutSeconds: Long): Pair<Int, String> {
    val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
    val reader = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        return -1 to "command timed out"
    }
    return process.exitValue() to reader.get(5, TimeUnit.SECONDS)
}

val result = JsonObject()
val targetJson = JsonObject()
val processJson = JsonArray()
val containerJson = JsonArray()
val mcsDirectories = linkedSetOf<String>()

for ((role, directory) in targets) {
    val server = properties(directory.resolve("server.properties"))
    var updateFolder = "update"
    for (line in Files.readAllLines(directory.resolve("bukkit.yml"))) {
        if (line.trim().startsWith("update-folder:")) updateFolder = line.substringAfter(':').trim().trim('\'', '"')
    }
    val item = JsonObject().apply {
        addProperty("directory", directory.toString())
        addProperty("updateFolder", updateFolder)
        addProperty("configuredPort", server["server-port"]?.toIntOrNull() ?: 25_565)
        add("jars", JsonArray().also { jars ->
            Files.list(directory.resolve("plugins")).use { files ->
                files.filter { "QuantumPlugin" in it.fileName.toString() && it.fileName.toString().endsWith(".jar") }
                    .sorted().forEach { jars.add(it.fileName.toString()) }
            }
        })
    }
    runCatching {
        val hot = JsonParser.parseString(Files.readString(directory.resolve("plugins/QuantumPlugin/hot/status.json"))).asJsonObject
        val selected = JsonObject()
        for (key in listOf("apiVersion", "modules", "hostName", "hostCodeSha256", "running", "phase",
            "activeRelease", "previousRelease", "restartRequired", "updatedAt", "instanceId")) {
            if (hot.has(key)) selected.add(key, hot.get(key))
        }
        item.add("hot", selected)
    }
    targetJson.add(role, item)
}

val procRoot = Path.of("/proc")
if (Files.isDirectory(procRoot)) {
    Files.list(procRoot).use { processes ->
        processes.filter { it.fileName.toString().all(Char::isDigit) }.forEach { proc -> runCatching {
            val executable = Path.of(Files.readSymbolicLink(proc.resolve("exe")).toString()).fileName.toString()
            val cwd = proc.resolve("cwd").toRealPath()
            val command = Files.readAllBytes(proc.resolve("cmdline")).toString(StandardCharsets.UTF_8)
                .split('\u0000').filter(String::isNotBlank)
            if (executable == "java" && cwd in targets.values + Path.of("/opt/server/orin")) {
                val item = JsonObject().apply {
                    addProperty("pid", proc.fileName.toString().toLong())
                    addProperty("cwd", cwd.toString())
                    addProperty("uid", Files.getAttribute(proc, "unix:uid").toString().toLong())
                    val jarIndex = command.indexOf("-jar")
                    if (jarIndex >= 0 && jarIndex + 1 < command.size) addProperty("jar", command[jarIndex + 1])
                    for (option in listOf("--port", "-port", "-p")) {
                        val index = command.indexOf(option)
                        if (index >= 0 && index + 1 < command.size) command[index + 1].toIntOrNull()?.let { addProperty("port", it) }
                    }
                }
                processJson.add(item)
            }
            if (executable.startsWith("node") && (cwd.toString().contains("mcs", ignoreCase = true) ||
                    Files.isDirectory(cwd.resolve("data/InstanceConfig")))) mcsDirectories += cwd.toString()
        } }
    }
}

val dockerAvailable = runCommand(listOf("sh", "-c", "command -v docker"), 5).first == 0
if (dockerAvailable) {
    val listing = runCommand(listOf("docker", "ps", "-aq"), 10)
    if (listing.first == 0 && listing.second.isNotBlank()) {
        val ids = listing.second.split(Regex("\\s+")).filter(String::isNotBlank)
        val inspected = runCommand(listOf("docker", "inspect") + ids, 15)
        if (inspected.first == 0) {
            for (entry in JsonParser.parseString(inspected.second).asJsonArray) {
                val container = entry.asJsonObject
                val mounts = container.getAsJsonArray("Mounts") ?: JsonArray()
                val roles = targets.filterValues { target ->
                    mounts.any { it.asJsonObject.get("Source")?.asString == target.toString() }
                }.keys
                if (roles.isEmpty()) continue
                containerJson.add(JsonObject().apply {
                    addProperty("id", container.get("Id").asString.take(12))
                    add("name", container.get("Name"))
                    add("roles", gson.toJsonTree(roles))
                    add("running", container.getAsJsonObject("State")?.get("Running"))
                    add("pid", container.getAsJsonObject("State")?.get("Pid"))
                    add("image", container.getAsJsonObject("Config")?.get("Image"))
                    add("ports", container.getAsJsonObject("NetworkSettings")?.get("Ports"))
                    add("mounts", mounts)
                    add("restartPolicy", container.getAsJsonObject("HostConfig")?.get("RestartPolicy"))
                    add("autoRemove", container.getAsJsonObject("HostConfig")?.get("AutoRemove"))
                })
            }
        }
    }
}

for ((role, directory) in targets) {
    val target = targetJson.getAsJsonObject(role)
    var port = target.get("configuredPort").asInt
    processJson.firstOrNull { it.asJsonObject.get("cwd")?.asString == directory.toString() }?.asJsonObject?.let {
        if (it.has("port")) port = it.get("port").asInt
    }
    containerJson.firstOrNull { it.asJsonObject.getAsJsonArray("roles")?.any { value -> value.asString == role } == true }
        ?.asJsonObject?.getAsJsonObject("ports")?.getAsJsonArray("25565/tcp")?.firstOrNull()?.asJsonObject
        ?.get("HostPort")?.asString?.toIntOrNull()?.let { port = it }
    target.addProperty("statusPort", port)
    target.add("serverStatus", ping(port))
}

result.add("targets", targetJson)
result.add("processes", processJson)
result.add("containers", containerJson)
result.add("mcsDirectories", gson.toJsonTree(mcsDirectories))

val root = Path.of(context.paths?.workspaceDir ?: error("Expose paths context"))
val temporary = Files.createTempFile(root, ".qplugin-status-", ".json")
try {
    Files.writeString(temporary, gson.toJson(result) + "\n", StandardCharsets.UTF_8)
    Files.setAttribute(temporary, "posix:group", Files.getAttribute(root, "posix:group"))
    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r-----"))
    Files.move(temporary, root.resolve("qplugin-status.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
} finally {
    Files.deleteIfExists(temporary)
}
orin.log(gson.toJson(result))
