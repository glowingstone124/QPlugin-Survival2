import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import org.qo.action.OrinActionScriptTemplate

// Keep one audited deployment implementation. The automatic CI action evaluates the exact
// qplugin-deploy KTS with its own exposed GitHub context and local action directory.
val workspace = Path.of(context.paths?.workspaceDir ?: error("Expose paths context"))
val shared = workspace.resolve("actions/qplugin-deploy/main.kts").normalize()
require(shared.startsWith(workspace.resolve("actions").normalize()) &&
    Files.isRegularFile(shared, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(shared)) {
    "Install the qplugin-deploy action before qplugin-ci"
}

val result = BasicJvmScriptingHost().eval(
    FileScriptSource(shared.toFile()),
    createJvmCompilationConfigurationFromTemplate<OrinActionScriptTemplate>(),
    ScriptEvaluationConfiguration { constructorArgs(context, orin) },
)
result.reports.forEach { report ->
    if (report.message.isNotBlank()) orin.log("Shared QPlugin KTS ${report.severity}: ${report.message}")
}
if (result is ResultWithDiagnostics.Failure) {
    error(result.reports.joinToString("; ") { it.exception?.message ?: it.message })
}
val returnValue = result.valueOrNull()?.returnValue
if (returnValue is ResultValue.Error) throw returnValue.error
