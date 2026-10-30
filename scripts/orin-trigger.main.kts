#!/usr/bin/env kotlin
@file:Import("json.main.kts")

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

val json = Gson()
val pipelines = setOf("qplugin-build", "qplugin-deploy-creative", "qplugin-deploy-survival", "qplugin-status")

fun option(name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

fun request(base: String, path: String, token: String, payload: JsonElement? = null): JsonObject {
    val builder = HttpRequest.newBuilder(URI(base.trimEnd('/') + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json")
    val httpRequest = if (payload == null) builder.GET().build()
    else builder.POST(HttpRequest.BodyPublishers.ofString(json.toJson(payload), StandardCharsets.UTF_8)).build()
    val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        .send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    require(response.statusCode() in 200..299) {
        "Orin HTTP ${response.statusCode()}: ${response.body().take(2_048)}"
    }
    val parsed = JsonParser.parseString(response.body())
    require(parsed.isJsonObject) { "Orin returned non-object JSON" }
    return parsed.asJsonObject
}

fun report(value: JsonElement) = println(json.toJson(value))

fun watch(base: String, token: String, jobId: String, timeoutSeconds: Double): Int {
    val deadline = System.nanoTime() + (timeoutSeconds * 1_000_000_000L).toLong()
    var previous: String? = null
    while (System.nanoTime() < deadline) {
        val encoded = URLEncoder.encode(jobId, StandardCharsets.UTF_8).replace("+", "%20")
        val status = request(base, "/remote/jobs/$encoded/status", token)
        val current = status.get("status")?.asString
        if (current != previous) { report(status); previous = current }
        if (current == "SUCCESS") return 0
        if (current == "FAILED") return 1
        Thread.sleep(2_000L.coerceAtMost(((deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000).coerceAtLeast(1)))
    }
    report(JsonObject().apply {
        addProperty("status", "UNCONFIRMED")
        addProperty("jobId", jobId)
        addProperty("message", "Client wait expired; query this job and backend status before retrying.")
    })
    return 4
}

fun selfTest() {
    val token = "private-test-token"
    val statusCalls = AtomicInteger()
    val executor = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
    fun reply(exchange: HttpExchange, value: Any) {
        val body = json.toJson(value).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.createContext("/remote/trigger/qplugin-build") { exchange ->
        check(exchange.requestHeaders.getFirst("Authorization") == "Bearer $token")
        val payload = JsonParser.parseString(exchange.requestBody.bufferedReader().use { it.readText() }).asJsonObject
        check(payload.getAsJsonObject("inputs")?.get("revision")?.asString == "a".repeat(40))
        reply(exchange, mapOf("jobId" to "job-123", "status" to "QUEUED"))
    }
    server.createContext("/remote/jobs/job-123/status") { exchange ->
        check(exchange.requestHeaders.getFirst("Authorization") == "Bearer $token")
        statusCalls.incrementAndGet()
        reply(exchange, mapOf("status" to "SUCCESS"))
    }
    server.createContext("/remote/jobs/job-failed/status") { exchange ->
        check(exchange.requestHeaders.getFirst("Authorization") == "Bearer $token")
        reply(exchange, mapOf("status" to "FAILED"))
    }
    server.start()
    try {
        val base = "http://127.0.0.1:${server.address.port}"
        val payload = JsonObject().apply {
            add("inputs", JsonObject().apply { addProperty("revision", "a".repeat(40)) })
        }
        val accepted = request(base, "/remote/trigger/qplugin-build", token, payload)
        check(accepted.get("jobId")?.asString == "job-123")
        check(watch(base, token, "job-123", 5.0) == 0 && statusCalls.get() == 1)
        check(watch(base, token, "job-failed", 5.0) == 1)
        check(watch(base, token, "unqueried-job", 0.0) == 4)
        println("PASS orin-trigger KTS: authenticated payload, success/failure, timeout without resubmission")
    } finally {
        server.stop(0)
        executor.shutdownNow()
    }
}

if (args.contains("--self-test")) {
    selfTest()
    exitProcess(0)
}

if (args.contains("--help") || args.isEmpty()) {
    println("""
        Usage:
          kotlin scripts/orin-trigger.main.kts --pipeline PIPELINE [--revision REV | --release-commit SHA] [--install-host]
          kotlin scripts/orin-trigger.main.kts --status JOB_ID
        Options: --config FILE (default /opt/server/orin/qplugin-control.json), --timeout SECONDS
    """.trimIndent())
    exitProcess(0)
}

try {
    val configPath = Path.of(option("--config") ?: "/opt/server/orin/qplugin-control.json")
    val pipeline = option("--pipeline")
    val statusJob = option("--status")
    require((pipeline == null) xor (statusJob == null)) { "Choose exactly one of --pipeline or --status" }
    if (pipeline != null) require(pipeline in pipelines) { "Unknown pipeline: $pipeline" }
    val revision = option("--revision")
    val releaseCommit = option("--release-commit")
    val installHost = args.contains("--install-host")
    val timeout = option("--timeout")?.toDoubleOrNull() ?: 2_700.0
    require(timeout > 0) { "timeout must be positive" }
    require(releaseCommit == null || releaseCommit.matches(Regex("[0-9a-f]{40,64}"))) {
        "release-commit must be a full Git SHA"
    }
    require(revision == null || releaseCommit == null) { "Choose revision or release-commit" }
    require(!(installHost || releaseCommit != null) || pipeline in setOf("qplugin-deploy-creative", "qplugin-deploy-survival")) {
        "Host installation and stored releases are only valid for a deployment pipeline"
    }
    val control = JsonParser.parseString(Files.readString(configPath)).asJsonObject
    val base = control.get("url")?.takeUnless { it.isJsonNull }?.asString
        ?: "http://127.0.0.1:${control.get("port")?.asInt ?: error("Missing control port")}"
    val tokens = control.getAsJsonObject("tokens") ?: error("Missing pipeline tokens")
    val statusToken = control.get("clusterToken")?.takeUnless { it.isJsonNull }?.asString
        ?: tokens.get("qplugin-build")?.asString ?: error("Missing status token")
    if (statusJob != null) {
        val encoded = URLEncoder.encode(statusJob, StandardCharsets.UTF_8).replace("+", "%20")
        val result = request(base, "/remote/jobs/$encoded/status", statusToken)
        report(result)
        exitProcess(when (result.get("status")?.asString) {
            "SUCCESS" -> 0
            "QUEUED", "RUNNING" -> 2
            else -> 1
        })
    }
    val selectedPipeline = pipeline ?: error("Missing --pipeline")
    val inputs = JsonObject().apply {
        revision?.let { addProperty("revision", it) }
        releaseCommit?.let { addProperty("release_commit", it) }
        if (installHost) addProperty("install_host", "true")
    }
    val payload = JsonObject().apply { add("inputs", inputs) }
    val token = tokens.get(selectedPipeline)?.asString ?: error("Missing token for $selectedPipeline")
    val accepted = request(base, "/remote/trigger/$selectedPipeline", token, payload)
    report(accepted)
    val jobId = accepted.get("jobId")?.asString ?: error("Orin response has no jobId")
    exitProcess(watch(base, statusToken, jobId, timeout))
} catch (error: Throwable) {
    System.err.println("Orin request failed: ${error.message ?: error.javaClass.simpleName}")
    exitProcess(1)
}
