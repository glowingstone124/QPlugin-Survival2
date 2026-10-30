import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.qo.Config
import org.qo.cluster.JobStatus
import org.qo.pipeline.PipelineService

private val gson = Gson()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun writeRelease(artifact: Path, releaseId: String, fingerprint: String) {
    val module = "fixture-module".toByteArray(StandardCharsets.UTF_8)
    val manifest = mapOf(
        "formatVersion" to 1,
        "apiVersion" to 2,
        "releaseId" to releaseId,
        "hosts" to mapOf("QuantumPlugin" to fingerprint, "QuantumPlugin-Creative" to fingerprint),
        "modules" to listOf(mapOf(
            "id" to "fixture", "version" to releaseId, "file" to "fixture.jar",
            "sha256" to sha256(module), "size" to module.size,
        )),
        "plugins" to emptyList<Any>(),
    )
    ZipOutputStream(Files.newOutputStream(artifact)).use { zip ->
        for ((name, bytes) in listOf("fixture.jar" to module, "manifest.json" to gson.toJson(manifest).toByteArray())) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

private fun status(host: String, fingerprint: String, instanceId: String, active: String = "builtin"): JsonObject =
    gson.toJsonTree(mapOf(
        "formatVersion" to 1, "apiVersion" to 2, "hostName" to host,
        "hostCodeSha256" to fingerprint, "instanceId" to instanceId,
        "running" to true, "phase" to "ready", "restartRequired" to false,
        "updatedAt" to System.currentTimeMillis(), "activeRelease" to active,
    )).asJsonObject

fun main(args: Array<String>) {
    val base = Path.of(args[0]).toAbsolutePath()
    val source = Path.of(args[1]).toAbsolutePath()
    val releaseId = "a".repeat(40)
    val fingerprint = "b".repeat(64)
    val artifact = base.resolve("release.zip")
    Files.createDirectories(base)
    writeRelease(artifact, releaseId, fingerprint)
    Files.writeString(base.resolve("qplugin.env"), "QPLUGIN_REPOSITORY=https://example.org/test.git\nQPLUGIN_RELEASE_STORE=$base/releases\nQPLUGIN_DOWNLOADS=$base/downloads\nQPLUGIN_DEFAULT_REVISION=hot-update\nQPLUGIN_JAVA_HOME=/fixtures/full-jdk\n")
    val backends = mapOf("survival" to "QuantumPlugin", "creative" to "QuantumPlugin-Creative")
    val instances = backends.mapValues { UUID.randomUUID().toString() }
    val hotDirectories = backends.mapValues { (role, host) ->
        Files.createDirectories(base.resolve("$role/plugins/QuantumPlugin/hot")).also { hot ->
            Files.createDirectories(hot.resolve("results"))
            Files.writeString(hot.resolve("status.json"), gson.toJson(status(host, fingerprint, instances.getValue(role))))
        }
    }
    val running = AtomicBoolean(true)
    val hostThread = Thread {
        while (running.get()) {
            for ((role, host) in backends) {
                val hot = hotDirectories.getValue(role)
                val requests = hot.resolve("requests")
                if (!Files.isDirectory(requests)) continue
                Files.list(requests).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".json") }.forEach { requestFile ->
                        val handled = hot.resolve("handled").resolve(requestFile.fileName)
                        if (Files.exists(handled)) return@forEach
                        val request = gson.fromJson(Files.readString(requestFile), JsonObject::class.java)
                        Files.createDirectories(handled.parent)
                        Files.writeString(handled, "ok")
                        val current = status(host, fingerprint, instances.getValue(role), request.get("releaseId").asString)
                        Files.writeString(hot.resolve("status.json"), gson.toJson(current))
                        val response = mapOf(
                            "requestId" to request.get("requestId").asString,
                            "instanceId" to instances.getValue(role),
                            "result" to mapOf("ok" to true, "code" to "ACTIVE", "releaseId" to request.get("releaseId").asString),
                        )
                        Files.writeString(hot.resolve("results").resolve(requestFile.fileName), gson.toJson(response))
                        Files.writeString(hot.resolve("confirmed.txt"), host)
                    }
                }
            }
            Thread.sleep(10)
        }
    }.apply { isDaemon = true; start() }
    val deployDirectory = base.resolve("actions/qplugin-deploy")
    for (action in listOf("qplugin-build", "qplugin-deploy")) {
        val directory = Files.createDirectories(base.resolve("actions/$action"))
        for (file in listOf("main.kts", "entry.toml")) Files.copy(source.resolve("$action/$file"), directory.resolve(file), REPLACE_EXISTING)
        Files.writeString(directory.resolve("build.sh"), """
            #!/usr/bin/env bash
            set -euo pipefail
            test "${'$'}ORIN_CONTEXT_JAVA_HOME" = /fixtures/full-jdk
            printf '%s\n%s\n' '$releaseId' '$artifact' > "${'$'}ORIN_CONTEXT_RECEIPT"
        """.trimIndent())
    }
    val statusDirectory = Files.createDirectories(base.resolve("actions/qplugin-status"))
    for (file in listOf("main.kts", "entry.toml")) {
        Files.copy(source.resolve("qplugin-status/$file"), statusDirectory.resolve(file), REPLACE_EXISTING)
    }
    val statusBackends = backends.keys.associateWith { role ->
        Files.createDirectories(base.resolve("status-$role/plugins")).also { plugins ->
            Files.writeString(plugins.parent.resolve("server.properties"), "server-port=${if (role == "survival") 45_001 else 45_002}\n")
            Files.writeString(plugins.parent.resolve("bukkit.yml"), "settings:\n  update-folder: update\n")
        }.parent
    }
    Files.writeString(statusDirectory.resolve("targets.properties"),
        "survival.directory=${statusBackends.getValue("survival")}\ncreative.directory=${statusBackends.getValue("creative")}\n")
    fun targets(creative: Path = hotDirectories.getValue("creative")) {
        Files.writeString(deployDirectory.resolve("targets.properties"),
            "qplugin-deploy-survival.hotDir=${hotDirectories.getValue("survival")}\nqplugin-deploy-creative.hotDir=$creative\n")
    }
    targets()
    val names = listOf("qplugin-build", "qplugin-deploy-survival", "qplugin-deploy-creative", "qplugin-status", "unknown-target")
    val pipelines = names.map { name ->
        Config.Pipeline(name = name, triggers = listOf("manual", "cluster"), manualToken = "local-test",
            actions = listOf(when (name) {
                "qplugin-build", "qplugin-status" -> name
                else -> "qplugin-deploy"
            }))
    }
    val service = PipelineService(pipelines = pipelines, workspaceRoot = base)
    fun manual(name: String, expected: PipelineService.ManualTriggerStatus = PipelineService.ManualTriggerStatus.SUCCESS) {
        val result = service.triggerPipeline(name, PipelineService.ManualTriggerRequest("local-test", mapOf(
            "revision" to "hot-update", "hot_dir" to "/wrong-input", "host_name" to "wrong",
        )))
        check(result.status == expected) { "$name: ${result.status} expected $expected" }
        println("PASS $name manual: $expected")
    }
    try {
        manual("qplugin-build")
        manual("qplugin-deploy-survival")
        manual("qplugin-deploy-creative")
        manual("qplugin-status")
        check(Files.readString(base.resolve("qplugin-status.json")).contains("status-survival"))
        for ((role, host) in backends) check(Files.readString(hotDirectories.getValue(role).resolve("confirmed.txt")) == host)
        val survivalBefore = Files.readString(hotDirectories.getValue("survival").resolve("confirmed.txt"))
        targets(hotDirectories.getValue("survival"))
        manual("qplugin-deploy-creative", PipelineService.ManualTriggerStatus.FAILED)
        check(Files.readString(hotDirectories.getValue("survival").resolve("confirmed.txt")) == survivalBefore)
        targets()
        manual("unknown-target", PipelineService.ManualTriggerStatus.FAILED)
        val cluster = service.triggerClusterPipeline("qplugin-build", token = "local-test")
        check(cluster.status == PipelineService.ClusterTriggerStatus.ACCEPTED)
        val jobId = cluster.jobId!!
        val deadline = System.nanoTime() + 30_000_000_000L
        while (service.jobRegistry.getJob(jobId)!!.status !in listOf(JobStatus.SUCCESS, JobStatus.FAILED) && System.nanoTime() < deadline) Thread.sleep(100)
        val job = service.jobRegistry.getJob(jobId)!!
        check(job.status == JobStatus.SUCCESS)
        check(job.artifacts["qplugin-release"] == base.resolve("releases/$releaseId.zip"))
        println("PASS cluster build: artifact published")
        println("PASS KTS release validation, backend routing, confirmation and wrong-backend rejection")
    } finally {
        running.set(false)
        hostThread.join(1_000)
    }
}
