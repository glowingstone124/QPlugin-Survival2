package vip.qoriginal.quantumplugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object QModWire {
    const val MAX_BYTES = 4096
    private val noncePattern = Regex("[A-Za-z0-9_-]{43}")
    fun string(json: JsonObject, key: String): String? = json.get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun decode(bytes: ByteArray): JsonObject? {
        if (bytes.size !in 1..MAX_BYTES) return null
        val json = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }.getOrNull() ?: return null
        val version = json.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        if (version?.toString() != "1" || string(json, "nonce")?.matches(noncePattern) != true) return null
        return when (string(json, "op")) {
            "hello" -> json.takeIf {
                string(it, "modVersion")?.let { version -> version.length in 1..64 && version.none(Char::isISOControl) } == true &&
                    it.get("autoLogin")?.let { value -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean } == true
            }
            "proof" -> json.takeIf { validChallenge(string(it, "challenge")) &&
                string(it, "ticket")?.matches(Regex("GP-[A-Za-z0-9_-]{43}")) == true }
            "decline" -> json
            else -> null
        }
    }

    fun validChallenge(value: String?) = value?.matches(Regex("GC-[A-Za-z0-9_-]{43}")) == true
}

/** Main-thread state machine: at most one challenge and redemption per connection nonce. */
internal class QModLoginExchange {
    enum class Stage { PROBE, LOADING, PROOF, VERIFYING, DONE }
    var stage = Stage.PROBE
        private set
    var challenge = ""
        private set
    var nodeId = -1
        private set

    fun start(): Boolean {
        if (stage != Stage.PROBE) return false
        stage = Stage.LOADING
        return true
    }

    fun challenge(value: String, node: Int): Boolean {
        if (stage != Stage.LOADING || !QModWire.validChallenge(value) || node < 0) return false
        challenge = value
        nodeId = node
        stage = Stage.PROOF
        return true
    }

    fun verify(value: String): Boolean {
        if (stage != Stage.PROOF || value != challenge) return false
        stage = Stage.VERIFYING
        return true
    }

    fun finish() { stage = Stage.DONE }
}
