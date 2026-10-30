import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.qo.Config
import org.qo.pipeline.PipelineService
import org.qo.cluster.JobStatus

fun main(args: Array<String>) {
    val base = Path.of(args[0]).toAbsolutePath()
    val source = Path.of(args[1]).toAbsolutePath()
    val artifact = base.resolve("release.zip")
    Files.createDirectories(base)
    Files.writeString(base.resolve("qplugin.env"), "QPLUGIN_REPOSITORY=https://example.org/test.git\nQPLUGIN_RELEASE_STORE=$base/releases\nQPLUGIN_DOWNLOADS=$base/downloads\nQPLUGIN_DEFAULT_REVISION=hot-update\nQPLUGIN_JAVA_HOME=/fixtures/full-jdk\n")
    Files.writeString(artifact, "isolated-build-stub")
    val backends = mapOf("survival" to "QuantumPlugin", "creative" to "QuantumPlugin-Creative")
    val hotDirectories = backends.mapValues { (role, host) ->
        Files.createDirectories(base.resolve("$role/plugins/QuantumPlugin/hot")).also {
            Files.writeString(it.resolve("status.json"), "{\"hostName\":\"$host\"}")
        }
    }
    val deployDirectory = base.resolve("actions/qplugin-deploy")
    for (action in listOf("qplugin-build", "qplugin-deploy")) {
        val directory = Files.createDirectories(base.resolve("actions/$action"))
        for (file in listOf("main.kts", "entry.toml")) Files.copy(source.resolve("$action/$file"), directory.resolve(file), REPLACE_EXISTING)
        Files.writeString(directory.resolve("build.sh"), """
            #!/usr/bin/env bash
            set -euo pipefail
            test "${'$'}ORIN_CONTEXT_JAVA_HOME" = /fixtures/full-jdk
            printf '%s\n%s\n' '${"a".repeat(40)}' '$artifact' > "${'$'}ORIN_CONTEXT_RECEIPT"
        """.trimIndent())
    }
    fun targets(creative: Path = hotDirectories.getValue("creative")) {
        Files.writeString(deployDirectory.resolve("targets.properties"),
            "qplugin-deploy-survival.hotDir=${hotDirectories.getValue("survival")}\nqplugin-deploy-creative.hotDir=$creative\n")
    }
    targets()
    Files.copy(source.resolve("qplugin-deploy/deploy.sh"), deployDirectory.resolve("deploy.sh"), REPLACE_EXISTING)
    Files.writeString(deployDirectory.resolve("deploy-hot.py"), """
        import argparse,json,pathlib
        p=argparse.ArgumentParser()
        for name in ['release','hot-dir','host-name','timeout']:p.add_argument('--'+name,required=True)
        a=p.parse_args();root=pathlib.Path(a.hot_dir)
        assert pathlib.Path(a.release).is_file()
        assert json.loads((root/'status.json').read_text())['hostName']==a.host_name,'Wrong backend'
        with (root/'confirmed.txt').open('a') as out:out.write(a.host_name+'\n')
    """.trimIndent())
    val names = listOf("qplugin-build", "qplugin-deploy-survival", "qplugin-deploy-creative", "unknown-target")
    val pipelines = names.map { name -> Config.Pipeline(name=name, triggers=listOf("manual", "cluster"), manualToken="local-test", actions=listOf(if(name=="qplugin-build") name else "qplugin-deploy")) }
    val service = PipelineService(pipelines=pipelines, workspaceRoot=base)
    fun manual(name: String, expected: PipelineService.ManualTriggerStatus = PipelineService.ManualTriggerStatus.SUCCESS) {
        val result = service.triggerPipeline(name, PipelineService.ManualTriggerRequest("local-test", mapOf("revision" to "hot-update", "hot_dir" to "/wrong-input", "host_name" to "wrong")))
        check(result.status == expected) { "$name: ${result.status} expected $expected" }
        println("PASS $name manual: $expected")
    }
    manual("qplugin-build")
    manual("qplugin-deploy-survival")
    manual("qplugin-deploy-creative")
    for ((role, host) in backends) check(Files.readString(hotDirectories.getValue(role).resolve("confirmed.txt")).trim() == host)
    val survivalBefore = Files.readString(hotDirectories.getValue("survival").resolve("confirmed.txt"))
    targets(hotDirectories.getValue("survival"))
    manual("qplugin-deploy-creative", PipelineService.ManualTriggerStatus.FAILED)
    check(Files.readString(hotDirectories.getValue("survival").resolve("confirmed.txt")) == survivalBefore)
    targets()
    manual("unknown-target", PipelineService.ManualTriggerStatus.FAILED)
    val cluster = service.triggerClusterPipeline("qplugin-build", token="local-test")
    check(cluster.status == PipelineService.ClusterTriggerStatus.ACCEPTED)
    val jobId = cluster.jobId!!
    val deadline = System.nanoTime() + 30_000_000_000L
    while (service.jobRegistry.getJob(jobId)!!.status !in listOf(JobStatus.SUCCESS, JobStatus.FAILED) && System.nanoTime() < deadline) Thread.sleep(100)
    val job = service.jobRegistry.getJob(jobId)!!
    check(job.status == JobStatus.SUCCESS)
    check(job.artifacts["qplugin-release"] == artifact)
    println("PASS cluster build: artifact published")
    println("PASS separate backend routing, ignored input overrides and wrong-backend rejection")
}
