import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

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
val archive = if (buildNode != null) {
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
