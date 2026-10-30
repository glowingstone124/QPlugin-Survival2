import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import com.google.gson.JsonParser

val settings = Properties().apply {
    val file = Path.of(context.paths?.workspaceDir ?: error("Expose paths context")).resolve("qplugin.env")
    if (Files.isRegularFile(file)) Files.newBufferedReader(file).use { load(it) }
}
fun setting(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: settings.getProperty(name)?.takeIf { it.isNotBlank() }

val pipelineName = context.pipeline?.name ?: error("Expose pipeline context for qplugin-deploy")
val hostName = when (pipelineName) {
    "qplugin-deploy-survival" -> "QuantumPlugin"
    "qplugin-deploy-creative" -> "QuantumPlugin-Creative"
    else -> error("Use pipeline qplugin-deploy-survival or qplugin-deploy-creative")
}
val actionDirectory = Path.of(context.paths?.actionDir ?: error("Expose paths context"))
val targets = Properties().apply {
    Files.newBufferedReader(actionDirectory.resolve("targets.properties")).use { load(it) }
}
val hotDirectory = Path.of(targets.getProperty("$pipelineName.hotDir") ?: error("Configure $pipelineName.hotDir")).normalize()
require(hotDirectory.isAbsolute) { "Backend hotDir must be absolute" }
val installHost = context.inputs["install_host"]?.let {
    require(it == "true" || it == "false") { "install_host must be true or false" }
    it == "true"
} ?: false
require(!installHost || context.trigger in listOf("manual", "cluster")) {
    "Full plugin installation requires an explicit manual/cluster trigger"
}
require(installHost || Files.isRegularFile(hotDirectory.resolve("status.json"))) {
    "Install and start the hot-module host before deploying to $hotDirectory"
}
orin.log("QPlugin target: $pipelineName host=$hostName hotDir=$hotDirectory")

val buildNode = setting("QPLUGIN_BUILD_NODE")
val downloadDirectory = Path.of(setting("QPLUGIN_DOWNLOADS") ?: error("Set QPLUGIN_DOWNLOADS"))
require(downloadDirectory.isAbsolute) { "QPLUGIN_DOWNLOADS must be absolute" }
Files.createDirectories(downloadDirectory)
val revision = context.inputs["revision"] ?: setting("QPLUGIN_DEFAULT_REVISION") ?: "main"
val readyArchive = context.inputs["release_commit"]?.let { commit ->
    require(commit.matches(Regex("[0-9a-f]{40,64}"))) { "release_commit must be a full Git SHA" }
    val store = Path.of(setting("QPLUGIN_RELEASE_STORE") ?: error("Set QPLUGIN_RELEASE_STORE"))
    require(store.isAbsolute) { "QPLUGIN_RELEASE_STORE must be absolute" }
    val file = store.resolve("$commit.zip")
    require(Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Build this release before deploying it" }
    ZipFile(file.toFile()).use { zip ->
        val entry = zip.getEntry("manifest.json") ?: error("Release has no manifest")
        require(entry.size in 1..65536) { "Invalid release manifest size" }
        val manifest = zip.getInputStream(entry).use { JsonParser.parseString(String(it.readNBytes(65537), Charsets.UTF_8)).asJsonObject }
        require(manifest.get("releaseId").asString == commit) { "Stored release differs from the requested commit" }
    }
    file
}
val archive = if (readyArchive != null) {
    orin.log("Reusing the confirmed immutable release: $readyArchive")
    readyArchive
} else if (buildNode != null) {
    val job = orin.remote(buildNode).trigger("qplugin-build", mapOf("revision" to revision))
    job.await(1800).requireSuccess()
    job.downloadArtifact("qplugin-release", downloadDirectory.resolve("${job.jobId}.zip"))
} else {
    // The single-worker runner must not wait for a remote job queued on this same instance.
    val receipt = Files.createTempFile("qplugin-build-", ".txt")
    try {
        orin.executeSh("build.sh", mapOf(
            "revision" to revision, "receipt" to receipt.toString(),
            "repository" to (setting("QPLUGIN_REPOSITORY") ?: ""),
            "release_store" to (setting("QPLUGIN_RELEASE_STORE") ?: ""),
            "http_proxy" to (setting("QPLUGIN_HTTP_PROXY") ?: ""),
            "java_home" to (setting("QPLUGIN_JAVA_HOME") ?: ""),
        )).requireSuccess()
        val lines = Files.readAllLines(receipt)
        require(lines.size == 2 && lines[0].matches(Regex("[0-9a-f]{40,64}"))) { "Invalid build receipt" }
        if (context.jobId != null) orin.publishArtifact("qplugin-release", lines[1])
        else orin.log("Release saved in persistent store: ${lines[1]}")
        Path.of(lines[1])
    } finally {
        Files.deleteIfExists(receipt)
    }
}
orin.executeSh("deploy.sh", mapOf(
    "archive" to archive.toString(), "hot_dir" to hotDirectory.toString(), "host_name" to hostName,
    "install_host" to installHost.toString(),
)).requireSuccess()
