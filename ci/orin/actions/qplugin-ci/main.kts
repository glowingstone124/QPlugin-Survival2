import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.zip.ZipFile

val workspace = Path.of(context.paths?.workspaceDir ?: error("Expose paths context"))
val settings = Properties().apply {
    val file = workspace.resolve("qplugin.env")
    if (Files.isRegularFile(file)) Files.newBufferedReader(file).use { load(it) }
}
fun setting(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: settings.getProperty(name)?.takeIf { it.isNotBlank() }

fun deployPush() {
    val github = context.github
    if (context.trigger == "github.push" && (github == null || github.fullName != "glowingstone124/QPlugin-Survival2"
            || github.ref != "refs/heads/main" || github.deleted)) {
        orin.log("QPlugin CI skipped: only pushes to glowingstone124/QPlugin-Survival2 main deploy")
        return
    }
    val revision = if (context.trigger == "github.push") github!!.after
        else context.inputs["revision"]
    require(revision != null && revision.matches(Regex("[0-9a-f]{40,64}")) && revision.any { it != '0' }) {
        "QPlugin CI requires an exact commit SHA (upgrade Orin if github.after is missing)"
    }
    val targets = Properties().apply {
        Files.newBufferedReader(Path.of(context.paths!!.actionDir).resolve("targets.properties")).use { load(it) }
    }
    val receipt = Files.createTempFile("qplugin-ci-", ".txt")
    val log = StringBuilder()
    val results = linkedMapOf<String, String>()
    fun readableReport(file: Path) {
        Files.setAttribute(file, "posix:group", Files.getAttribute(workspace, "posix:group"))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"))
    }
    fun command(script: String, extra: Map<String, String>) {
        val result = orin.executeSh(script, extra)
        log.append("\n=== ").append(script).append(' ').append(extra["host_name"] ?: revision).append(" ===\n")
            .append(result.stdout).append('\n').append(result.stderr)
        Files.writeString(workspace.resolve("qplugin-ci-last.log"), log.toString())
        readableReport(workspace.resolve("qplugin-ci-last.log"))
        result.requireSuccess()
    }
    fun status(hot: Path) = runCatching {
        JsonParser.parseString(Files.readString(hot.resolve("status.json"))).asJsonObject
    }.getOrNull()
    try {
        command("build.sh", mapOf("revision" to revision, "receipt" to receipt.toString(),
            "repository" to (setting("QPLUGIN_REPOSITORY") ?: ""),
            "release_store" to (setting("QPLUGIN_RELEASE_STORE") ?: ""),
            "http_proxy" to (setting("QPLUGIN_HTTP_PROXY") ?: ""),
            "java_home" to (setting("QPLUGIN_JAVA_HOME") ?: "")))
        val lines = Files.readAllLines(receipt)
        require(lines.size == 2 && lines[0] == revision) { "Build receipt differs from pushed commit" }
        val archive = Path.of(lines[1])
        val manifest = ZipFile(archive.toFile()).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("Release has no manifest")
            require(entry.size in 1..65536) { "Invalid manifest size" }
            zip.getInputStream(entry).use { JsonParser.parseString(String(it.readNBytes(65537), Charsets.UTF_8)).asJsonObject }
        }
        require(manifest.get("releaseId").asString == revision) { "Release differs from pushed commit" }
        if (context.jobId != null) orin.publishArtifact("qplugin-release", archive.toString())
        // Deploy the quieter backend first. A failure prevents the second backend from changing.
        for ((role, host) in listOf("creative" to "QuantumPlugin-Creative", "survival" to "QuantumPlugin")) {
            val hot = Path.of(targets.getProperty("qplugin-deploy-$role.hotDir") ?: error("Missing $role target")).normalize()
            require(hot.isAbsolute) { "Backend path must be absolute" }
            val before = status(hot)
            require(before == null || before.get("hostName")?.asString == host) { "Wrong backend for $role" }
            val fingerprint = manifest.getAsJsonObject("hosts").get(host).asString
            val installHost = before?.get("hostCodeSha256")?.asString != fingerprint
            orin.log("QPlugin CI $revision -> $role, mode=${if (installHost) "graceful host update" else "hot modules"}")
            command("deploy.sh", mapOf("archive" to archive.toString(), "hot_dir" to hot.toString(),
                "host_name" to host, "install_host" to installHost.toString()))
            val after = status(hot) ?: error("Missing backend status after deploy")
            require(after.get("running")?.asBoolean == true && after.get("phase")?.asString == "ready"
                && after.get("hostName")?.asString == host && after.get("hostCodeSha256")?.asString == fingerprint
                && after.get("activeRelease")?.asString == revision && after.get("restartRequired")?.asBoolean == false
                && kotlin.math.abs(System.currentTimeMillis() - after.get("updatedAt").asLong) < 30000) {
                "Backend did not confirm a healthy $revision: $role"
            }
            results[role] = if (installHost) "host-updated" else "hot-updated"
        }
        val summary = GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "commit" to revision, "status" to "SUCCESS", "targets" to results, "completedAt" to System.currentTimeMillis()))
        val temporary = Files.createTempFile(workspace, ".qplugin-ci-", ".json")
        try {
            Files.writeString(temporary, summary)
            readableReport(temporary)
            Files.move(temporary, workspace.resolve("qplugin-ci-last-success.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        orin.log("QPlugin CI SUCCESS $revision: creative and survival confirmed")
    } finally { Files.deleteIfExists(receipt) }
}
deployPush()
