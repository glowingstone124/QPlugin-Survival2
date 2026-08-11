package vip.qoriginal.quantumplugin.chambers

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import vip.qoriginal.quantumplugin.chambers.data.ChamberRunResult
import vip.qoriginal.quantumplugin.registration.MinecraftRegistrationTest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class ChamberProgress(
    val sessionId: String,
    val username: String,
    val chamberIds: List<String>,
    val completedChambers: Int,
    val state: ChamberRunState,
    val failureReason: ChamberRunResult.FinishReason?,
    val updatedAtMillis: Long,
)

data class ChamberTerminalProgressScan(
    val results: List<ChamberRunResult>,
    val invalidFiles: List<String>,
)

class ChamberProgressStore(
    dataFolder: Path,
) {
    private val progressDirectory =
        dataFolder.toAbsolutePath().normalize().resolve("progress")

    init {
        Files.createDirectories(progressDirectory)
    }

    @Synchronized
    fun loadOrCreate(
        session: MinecraftRegistrationTest.Session,
        selectedChamberIds: () -> List<String>,
    ): ChamberProgress {
        val file = progressFile(session.sessionId)
        if (Files.exists(file)) return load(file, session)

        val chamberIds = selectedChamberIds().toList()
        validateChamberIds(chamberIds)
        return ChamberProgress(
            sessionId = session.sessionId,
            username = session.username,
            chamberIds = chamberIds,
            completedChambers = 0,
            state = ChamberRunState.READY,
            failureReason = null,
            updatedAtMillis = System.currentTimeMillis(),
        ).also(::save)
    }

    @Synchronized
    fun save(progress: ChamberProgress): ChamberProgress {
        validateSessionId(progress.sessionId)
        validateChamberIds(progress.chamberIds)
        require(progress.username.isNotBlank()) { "progress username is blank" }
        require(progress.completedChambers in 0..progress.chamberIds.size) {
            "progress completed count is outside its chamber sequence"
        }
        ChamberRunStateMachine.validate(progress)
        val updated = progress.copy(updatedAtMillis = System.currentTimeMillis())
        val json = JsonObject().apply {
            addProperty("version", FORMAT_VERSION)
            addProperty("sessionId", updated.sessionId)
            addProperty("username", updated.username)
            add("chambers", JsonArray().apply {
                updated.chamberIds.forEach(::add)
            })
            addProperty("completedChambers", updated.completedChambers)
            addProperty("state", updated.state.name)
            updated.failureReason?.let {
                addProperty("failureReason", it.name)
            }
            addProperty("updatedAtMillis", updated.updatedAtMillis)
        }
        atomicWrite(progressFile(updated.sessionId), json.toString())
        return updated
    }

    @Synchronized
    fun delete(sessionId: String) {
        try {
            Files.deleteIfExists(progressFile(sessionId))
        } catch (exception: IOException) {
            throw IllegalStateException(
                "unable to delete chamber progress for $sessionId",
                exception,
            )
        }
    }

    @Synchronized
    fun scanTerminalResults(): ChamberTerminalProgressScan {
        val files = try {
            Files.list(progressDirectory).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".json") }
                    .sorted(compareBy { it.fileName.toString() })
                    .toList()
            }
        } catch (exception: IOException) {
            throw IllegalStateException(
                "unable to inspect persisted chamber progress",
                exception,
            )
        }
        val results = mutableListOf<ChamberRunResult>()
        val invalidFiles = mutableListOf<String>()
        files.forEach { file ->
            try {
                val identity = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8),
                ).asJsonObject
                val session = MinecraftRegistrationTest.Session(
                    identity.requiredString("sessionId"),
                    identity.requiredString("username"),
                )
                require(file == progressFile(session.sessionId)) {
                    "progress filename does not match its session id"
                }
                val progress = load(file, session)
                val reason = ChamberRunStateMachine.restore(progress)
                    .terminalReason()
                    ?: return@forEach
                results.add(
                    ChamberRunResult(
                        registrationSession = session,
                        reason = reason,
                        completedChambers = progress.completedChambers,
                        totalChambers = progress.chamberIds.size,
                    ),
                )
            } catch (exception: RuntimeException) {
                invalidFiles.add(
                    "${file.fileName}: ${exception.message ?: exception.javaClass.simpleName}",
                )
            } catch (exception: IOException) {
                invalidFiles.add(
                    "${file.fileName}: ${exception.message ?: "unable to read file"}",
                )
            }
        }
        return ChamberTerminalProgressScan(
            results = results.toList(),
            invalidFiles = invalidFiles.toList(),
        )
    }

    private fun load(
        file: Path,
        session: MinecraftRegistrationTest.Session,
    ): ChamberProgress {
        val json = try {
            JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .asJsonObject
        } catch (exception: RuntimeException) {
            throw IllegalStateException(
                "invalid chamber progress for ${session.sessionId}",
                exception,
            )
        } catch (exception: IOException) {
            throw IllegalStateException(
                "unable to read chamber progress for ${session.sessionId}",
                exception,
            )
        }
        val version = json.requiredInt("version")
        require(version in SUPPORTED_FORMAT_VERSIONS) {
            "unsupported chamber progress version"
        }
        val sessionId = json.requiredString("sessionId")
        val username = json.requiredString("username")
        require(sessionId == session.sessionId) { "progress session id mismatch" }
        require(username.equals(session.username, ignoreCase = true)) {
            "progress username mismatch"
        }
        val chamberIds = json.getAsJsonArray("chambers")
            ?.map { element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    "progress contains an invalid chamber id"
                }
                element.asString
            }
            ?: throw IllegalArgumentException("progress is missing chambers")
        validateChamberIds(chamberIds)
        val completedChambers = json.requiredInt("completedChambers")
        val legacyTerminalReason = if (version == 1) {
            json.optionalFinishReason("terminalReason")
        } else {
            null
        }
        val state = if (version == 1) {
            when {
                legacyTerminalReason == ChamberRunResult.FinishReason.PASSED ||
                    completedChambers == chamberIds.size -> ChamberRunState.PASSED
                legacyTerminalReason == ChamberRunResult.FinishReason.TIMED_OUT ||
                    legacyTerminalReason ==
                    ChamberRunResult.FinishReason.CANCELLED -> ChamberRunState.FAILED
                else -> ChamberRunState.PAUSED
            }
        } else {
            json.requiredRunState("state")
        }
        val progress = ChamberProgress(
            sessionId = sessionId,
            username = username,
            chamberIds = chamberIds,
            completedChambers = completedChambers,
            state = state,
            failureReason = if (version == 1) {
                legacyTerminalReason?.takeIf {
                    it == ChamberRunResult.FinishReason.TIMED_OUT ||
                        it == ChamberRunResult.FinishReason.CANCELLED
                }
            } else {
                json.optionalFinishReason("failureReason")
            },
            updatedAtMillis = json.requiredLong("updatedAtMillis"),
        )
        ChamberRunStateMachine.validate(progress)
        return progress
    }

    private fun progressFile(sessionId: String): Path {
        validateSessionId(sessionId)
        return progressDirectory.resolve("$sessionId.json")
    }

    private fun validateSessionId(sessionId: String) {
        try {
            UUID.fromString(sessionId)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("invalid registration session id")
        }
    }

    private fun validateChamberIds(chamberIds: List<String>) {
        require(chamberIds.isNotEmpty() && chamberIds.size <= MAX_CHAMBERS) {
            "progress contains an invalid number of chambers"
        }
        require(
            chamberIds.all { it.matches(CHAMBER_ID) } &&
                chamberIds.distinct().size == chamberIds.size,
        ) {
            "progress contains an unsafe or duplicate chamber id"
        }
    }

    private fun atomicWrite(destination: Path, content: String) {
        val temporary = destination.resolveSibling("${destination.fileName}.tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (exception: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (exception: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw IllegalStateException(
                "unable to save chamber progress for ${destination.fileName}",
                exception,
            )
        }
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = get(key)
        require(
            value != null &&
                value.isJsonPrimitive &&
                value.asJsonPrimitive.isString,
        ) {
            "progress is missing string $key"
        }
        return value.asString
    }

    private fun JsonObject.requiredInt(key: String): Int {
        val value = get(key)
        require(value != null && value.isJsonPrimitive) {
            "progress is missing integer $key"
        }
        return runCatching(value::getAsInt).getOrElse {
            throw IllegalArgumentException("progress contains invalid integer $key")
        }
    }

    private fun JsonObject.requiredLong(key: String): Long {
        val value = get(key)
        require(value != null && value.isJsonPrimitive) {
            "progress is missing number $key"
        }
        return runCatching(value::getAsLong).getOrElse {
            throw IllegalArgumentException("progress contains invalid number $key")
        }
    }

    private fun JsonObject.optionalFinishReason(
        key: String,
    ): ChamberRunResult.FinishReason? {
        val value = get(key) ?: return null
        require(
            value.isJsonPrimitive && value.asJsonPrimitive.isString,
        ) {
            "progress contains invalid finish reason"
        }
        return runCatching {
            ChamberRunResult.FinishReason.valueOf(value.asString)
        }.getOrElse {
            throw IllegalArgumentException("progress contains unknown finish reason")
        }
    }

    private fun JsonObject.requiredRunState(key: String): ChamberRunState {
        val value = requiredString(key)
        return runCatching {
            ChamberRunState.valueOf(value)
        }.getOrElse {
            throw IllegalArgumentException("progress contains unknown run state")
        }
    }

    companion object {
        private const val FORMAT_VERSION = 2
        private val SUPPORTED_FORMAT_VERSIONS = setOf(1, FORMAT_VERSION)
        private const val MAX_CHAMBERS = 128
        private val CHAMBER_ID = Regex("[A-Za-z0-9_.-]+")
    }
}
