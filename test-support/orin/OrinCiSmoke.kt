import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.qo.Config
import org.qo.GithubPushEvent
import org.qo.pipeline.PipelineService

fun main(args: Array<String>) {
    val base = Files.createDirectories(Path.of(args[0]).toAbsolutePath())
    val source = Path.of(args[1]).toAbsolutePath()
    val sha = "b".repeat(40)
    val fingerprint = "c".repeat(64)
    val artifact = base.resolve("release.zip")
    ZipOutputStream(Files.newOutputStream(artifact)).use {
        it.putNextEntry(ZipEntry("manifest.json"))
        it.write("""{"releaseId":"$sha","hosts":{"QuantumPlugin":"$fingerprint","QuantumPlugin-Creative":"$fingerprint"}}""".toByteArray())
        it.closeEntry()
    }
    Files.writeString(base.resolve("qplugin.env"), "QPLUGIN_JAVA_HOME=/fixture/jdk\n")
    val action = Files.createDirectories(base.resolve("actions/qplugin-ci"))
    for (file in listOf("main.kts", "entry.toml")) Files.copy(source.resolve(file), action.resolve(file), REPLACE_EXISTING)
    val targets = listOf("creative" to "QuantumPlugin-Creative", "survival" to "QuantumPlugin")
    for ((role, host) in targets) {
        val hot = Files.createDirectories(base.resolve(role))
        Files.writeString(hot.resolve("status.json"), """{"hostName":"$host","hostCodeSha256":"${if (role == "creative") fingerprint else "old"}"}""")
    }
    Files.writeString(action.resolve("targets.properties"), targets.joinToString("\n") { (role, _) -> "qplugin-deploy-$role.hotDir=${base.resolve(role)}" })
    Files.writeString(action.resolve("build.sh"), """
        set -eu
        test "${'$'}ORIN_CONTEXT_JAVA_HOME" = /fixture/jdk
        printf '%s\n%s\n' '$sha' '$artifact' > "${'$'}ORIN_CONTEXT_RECEIPT"
        echo build >> '$base/calls'
    """.trimIndent())
    // The real KTS executor runs this fixture; no production backend or Docker operation is used.
    Files.writeString(action.resolve("deploy.sh"), """
        set -eu
        echo "${'$'}ORIN_CONTEXT_HOST_NAME:${'$'}ORIN_CONTEXT_INSTALL_HOST" >> '$base/calls'
        test ! -f '$base/fail'
        printf '{"running":true,"phase":"ready","hostName":"%s","hostCodeSha256":"$fingerprint","activeRelease":"$sha","restartRequired":false,"updatedAt":%s}' "${'$'}ORIN_CONTEXT_HOST_NAME" "${'$'}(date +%s)000" > "${'$'}ORIN_CONTEXT_HOT_DIR/status.json"
    """.trimIndent())
    val service = PipelineService(listOf(Config.Pipeline("qplugin-ci", repository="QPlugin-Survival2", triggers=listOf("github.push"), actions=listOf("qplugin-ci"))), workspaceRoot=base)
    fun push(ref: String="refs/heads/main", full: String="glowingstone124/QPlugin-Survival2", deleted: Boolean=false, after: String?=sha): Boolean {
        val payload = Gson().toJson(mapOf("repository" to mapOf("name" to "QPlugin-Survival2", "full_name" to full), "commits" to emptyList<Any>(), "sender" to mapOf("login" to "test"), "ref" to ref, "after" to after, "deleted" to deleted))
        return service.handleGithubPush(Gson().fromJson(payload, GithubPushEvent::class.java)).single().succeeded
    }
    check(push())
    check(Files.readAllLines(base.resolve("calls")) == listOf("build", "QuantumPlugin-Creative:false", "QuantumPlugin:true"))
    check(Files.readString(base.resolve("qplugin-ci-last-success.json")).contains(sha))
    println("PASS exact push SHA, one build, creative first, hot/host selection and healthy receipts")
    val calls = Files.readString(base.resolve("calls"))
    check(push(ref="refs/heads/hot-update")); check(push(deleted=true)); check(push(full="other/QPlugin-Survival2"))
    check(Files.readString(base.resolve("calls")) == calls)
    println("PASS non-main, deleted branch and other repository pushes skipped without building")
    check(!push(after=null)); check(Files.readString(base.resolve("calls")) == calls)
    println("PASS missing SHA rejected before build")
    Files.delete(base.resolve("calls")); Files.writeString(base.resolve("fail"), "failure injection")
    check(!push())
    check(Files.readAllLines(base.resolve("calls")) == listOf("build", "QuantumPlugin-Creative:false"))
    println("PASS creative failure prevents survival deployment")
}
