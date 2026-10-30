import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.nio.file.attribute.PosixFilePermissions

val settings = Properties().apply {
    val file = Path.of(context.paths?.workspaceDir ?: error("Expose paths context")).resolve("qplugin.env")
    if (Files.isRegularFile(file)) Files.newBufferedReader(file).use { load(it) }
}
fun setting(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: settings.getProperty(name)?.takeIf { it.isNotBlank() }

val httpProxy = setting("QPLUGIN_HTTP_PROXY") ?: ""
val javaProxy = if (httpProxy.isBlank()) "" else URI(httpProxy).let { uri ->
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
        (uri.path.isNullOrEmpty() || uri.path == "/") && uri.query == null && uri.fragment == null) {
        "QPLUGIN_HTTP_PROXY must be an HTTP proxy URL without credentials/path/query"
    }
    val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
    "-Dhttp.proxyHost=${uri.host} -Dhttp.proxyPort=$port -Dhttps.proxyHost=${uri.host} -Dhttps.proxyPort=$port -Dhttp.nonProxyHosts=localhost|127.*|[::1]"
}

fun digest(file: Path): String {
    val value = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) value.update(buffer, 0, count)
        }
    }
    return value.digest().joinToString("") { "%02x".format(it) }
}

fun storeImmutable(source: Path, commit: String): Path {
    require(!Files.isSymbolicLink(source) && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "Build output is not a regular file" }
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
                Files.size(target) == Files.size(temporary) && digest(target) == digest(temporary)) {
                "Release commit already contains different bytes"
            }
        }
        return target
    } finally {
        Files.deleteIfExists(temporary)
    }
}

val receipt = Files.createTempFile("qplugin-build-", ".txt")
val output = Files.createTempFile("qplugin-build-", ".zip")
try {
    val build = orin.executeSh("build.sh", mapOf(
        "revision" to (context.inputs["revision"] ?: setting("QPLUGIN_DEFAULT_REVISION") ?: "main"),
        "repository" to (setting("QPLUGIN_REPOSITORY") ?: ""),
        "release_store" to (setting("QPLUGIN_RELEASE_STORE") ?: ""),
        "http_proxy" to httpProxy,
        "java_proxy" to javaProxy,
        "java_home" to (setting("QPLUGIN_JAVA_HOME") ?: ""),
        "receipt" to receipt.toString(),
        "output_archive" to output.toString(),
    ))
    val buildLog = Path.of(context.paths!!.workspaceDir).resolve("qplugin-build-last.log")
    Files.writeString(buildLog, build.stdout + "\n" + build.stderr)
    Files.setAttribute(buildLog, "posix:group", Files.getAttribute(Path.of(context.paths!!.workspaceDir), "posix:group"))
    Files.setPosixFilePermissions(buildLog, PosixFilePermissions.fromString("rw-r-----"))
    build.requireSuccess()
    val lines = Files.readAllLines(receipt)
    require(lines.size == 2 && lines[0].matches(Regex("[0-9a-f]{40,64}"))) { "Invalid build receipt" }
    val stored = storeImmutable(Path.of(lines[1]), lines[0])
    // Existing Orin assigns job IDs to cluster triggers; manual/push triggers use the persistent store.
    if (context.jobId != null) orin.publishArtifact("qplugin-release", stored.toString())
    else orin.log("Release saved in persistent store: $stored")
    orin.log("Published QPlugin commit ${lines[0]}")
} finally {
    Files.deleteIfExists(receipt)
    Files.deleteIfExists(output)
}
