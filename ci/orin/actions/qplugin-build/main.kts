import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

val settings = Properties().apply {
    val file = Path.of(context.paths?.workspaceDir ?: error("Expose paths context")).resolve("qplugin.env")
    if (Files.isRegularFile(file)) Files.newBufferedReader(file).use { load(it) }
}
fun setting(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: settings.getProperty(name)?.takeIf { it.isNotBlank() }

val receipt = Files.createTempFile("qplugin-build-", ".txt")
try {
    orin.executeSh("build.sh", mapOf(
        "revision" to (context.inputs["revision"] ?: setting("QPLUGIN_DEFAULT_REVISION") ?: "main"),
        "repository" to (setting("QPLUGIN_REPOSITORY") ?: ""),
        "release_store" to (setting("QPLUGIN_RELEASE_STORE") ?: ""),
        "receipt" to receipt.toString(),
    )).requireSuccess()
    val lines = Files.readAllLines(receipt)
    require(lines.size == 2 && lines[0].matches(Regex("[0-9a-f]{40,64}"))) { "Invalid build receipt" }
    // Existing Orin assigns job IDs to cluster triggers; manual/push triggers use the persistent store.
    if (context.jobId != null) orin.publishArtifact("qplugin-release", lines[1])
    else orin.log("Release saved in persistent store: ${lines[1]}")
    orin.log("Published QPlugin commit ${lines[0]}")
} finally {
    Files.deleteIfExists(receipt)
}
