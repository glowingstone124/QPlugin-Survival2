import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.qo.Config
import org.qo.GithubPushEvent
import org.qo.pipeline.PipelineService

private val gson = Gson()

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun release(file: Path, id: String, fingerprint: String) {
    val module = "ci-fixture".toByteArray(StandardCharsets.UTF_8)
    val manifest = mapOf(
        "formatVersion" to 1, "apiVersion" to 2, "releaseId" to id,
        "hosts" to mapOf("QuantumPlugin" to fingerprint, "QuantumPlugin-Creative" to fingerprint),
        "modules" to listOf(mapOf("id" to "fixture", "version" to id, "file" to "fixture.jar",
            "sha256" to digest(module), "size" to module.size)),
        "plugins" to emptyList<Any>(),
    )
    ZipOutputStream(Files.newOutputStream(file)).use { zip ->
        for ((name, bytes) in listOf("fixture.jar" to module, "manifest.json" to gson.toJson(manifest).toByteArray())) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    }
}

private fun status(host: String, fingerprint: String, instance: String, active: String, updatedAt: Long = System.currentTimeMillis()) =
    gson.toJson(mapOf(
        "formatVersion" to 1, "apiVersion" to 2, "hostName" to host, "hostCodeSha256" to fingerprint,
        "instanceId" to instance, "running" to true, "phase" to "ready", "restartRequired" to false,
        "updatedAt" to updatedAt, "activeRelease" to active,
    ))

fun main(args: Array<String>) {
    val base = Files.createDirectories(Path.of(args[0]).toAbsolutePath())
    val source = Path.of(args[1]).toAbsolutePath()
    val sha = "b".repeat(40)
    val fingerprint = "c".repeat(64)
    val artifact = base.resolve("release.zip")
    release(artifact, sha, fingerprint)
    Files.writeString(base.resolve("qplugin.env"), "QPLUGIN_JAVA_HOME=/fixture/jdk\nQPLUGIN_DOWNLOADS=$base/downloads\nQPLUGIN_RELEASE_STORE=$base/releases\n")
    val ciAction = Files.createDirectories(base.resolve("actions/qplugin-ci"))
    val deployAction = Files.createDirectories(base.resolve("actions/qplugin-deploy"))
    Files.copy(source.resolve("qplugin-ci/entry.toml"), ciAction.resolve("entry.toml"), REPLACE_EXISTING)
    Files.copy(source.resolve("qplugin-deploy/main.kts"), ciAction.resolve("main.kts"), REPLACE_EXISTING)
    for (file in listOf("main.kts", "entry.toml")) {
        Files.copy(source.resolve("qplugin-deploy/$file"), deployAction.resolve(file), REPLACE_EXISTING)
    }
    val targets = listOf("creative" to "QuantumPlugin-Creative", "survival" to "QuantumPlugin")
    val instances = targets.associate { it.first to UUID.randomUUID().toString() }
    val hotDirectories = targets.associate { (role, host) ->
        role to Files.createDirectories(base.resolve(role)).also { hot ->
            Files.createDirectories(hot.resolve("results"))
            Files.writeString(hot.resolve("status.json"), status(host, fingerprint, instances.getValue(role), "old"))
        }
    }
    Files.writeString(ciAction.resolve("targets.properties"), targets.joinToString("\n") { (role, _) ->
        "qplugin-deploy-$role.hotDir=${hotDirectories.getValue(role)}"
    })
    Files.writeString(ciAction.resolve("build.sh"), """
        set -eu
        test "${'$'}ORIN_CONTEXT_JAVA_HOME" = /fixture/jdk
        printf '%s\n%s\n' '$sha' '$artifact' > "${'$'}ORIN_CONTEXT_RECEIPT"
        echo build >> '$base/calls'
    """.trimIndent())
    val running = AtomicBoolean(true)
    val host = Thread {
        while (running.get()) {
            for ((role, hostName) in targets) {
                val hot = hotDirectories.getValue(role)
                val requests = hot.resolve("requests")
                if (!Files.isDirectory(requests)) continue
                Files.list(requests).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".json") }.forEach { requestFile ->
                        val handled = hot.resolve("handled").resolve(requestFile.fileName)
                        if (Files.exists(handled)) return@forEach
                        val request = gson.fromJson(Files.readString(requestFile), JsonObject::class.java)
                        Files.createDirectories(handled.parent); Files.writeString(handled, "ok")
                        Files.writeString(hot.resolve("status.json"), status(hostName, fingerprint,
                            instances.getValue(role), request.get("releaseId").asString))
                        Files.writeString(hot.resolve("results").resolve(requestFile.fileName), gson.toJson(mapOf(
                            "requestId" to request.get("requestId").asString,
                            "instanceId" to instances.getValue(role),
                            "result" to mapOf("ok" to true, "code" to "ACTIVE", "releaseId" to request.get("releaseId").asString),
                        )))
                        Files.writeString(base.resolve("calls"), "$hostName\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    }
                }
            }
            Thread.sleep(10)
        }
    }.apply { isDaemon = true; start() }
    val service = PipelineService(listOf(Config.Pipeline("qplugin-ci", repository = "QPlugin-Survival2",
        triggers = listOf("github.push"), actions = listOf("qplugin-ci"))), workspaceRoot = base)
    fun push(ref: String = "refs/heads/main", full: String = "glowingstone124/QPlugin-Survival2",
             deleted: Boolean = false, after: String? = sha): Boolean {
        val payload = gson.toJson(mapOf(
            "repository" to mapOf("name" to "QPlugin-Survival2", "full_name" to full),
            "commits" to emptyList<Any>(), "sender" to mapOf("login" to "test"),
            "ref" to ref, "after" to after, "deleted" to deleted,
        ))
        return service.handleGithubPush(gson.fromJson(payload, GithubPushEvent::class.java)).single().succeeded
    }
    try {
        check(push())
        val firstCalls = Files.readAllLines(base.resolve("calls"))
        check(firstCalls == listOf("build", "QuantumPlugin-Creative", "QuantumPlugin"))
        check(Files.readString(base.resolve("qplugin-ci-last-success.json")).contains(sha))
        println("PASS exact push SHA, one KTS build/deploy flow, creative first and healthy receipts")
        val calls = Files.readString(base.resolve("calls"))
        check(push(ref = "refs/heads/hot-update")); check(push(deleted = true)); check(push(full = "other/QPlugin-Survival2"))
        check(Files.readString(base.resolve("calls")) == calls)
        println("PASS non-main, deleted branch and other repository pushes skipped without building")
        check(!push(after = null)); check(Files.readString(base.resolve("calls")) == calls)
        println("PASS missing SHA rejected before build")
        Files.writeString(hotDirectories.getValue("creative").resolve("status.json"),
            status("QuantumPlugin-Creative", fingerprint, instances.getValue("creative"), sha, System.currentTimeMillis() - 60_000))
        check(!push())
        val afterFailure = Files.readAllLines(base.resolve("calls"))
        check(afterFailure.count { it == "build" } == 2 &&
            afterFailure.count { it == "QuantumPlugin" } == firstCalls.count { it == "QuantumPlugin" })
        println("PASS creative preflight failure prevents survival deployment")
    } finally {
        running.set(false)
        host.join(1_000)
    }
}
