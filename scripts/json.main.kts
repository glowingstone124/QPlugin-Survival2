import java.io.Reader
import java.math.BigDecimal

sealed class JsonElement {
    val isJsonObject: Boolean get() = this is JsonObject
    val isJsonArray: Boolean get() = this is JsonArray
    val isJsonPrimitive: Boolean get() = this is JsonPrimitive
    val isJsonNull: Boolean get() = this === JsonNull
    val asJsonObject: JsonObject get() = this as JsonObject
    val asJsonArray: JsonArray get() = this as JsonArray
    val asJsonPrimitive: JsonPrimitive get() = this as JsonPrimitive
    val asString: String get() = asJsonPrimitive.stringValue
    val asInt: Int get() = asJsonPrimitive.numberValue.toInt()
    val asLong: Long get() = asJsonPrimitive.numberValue.toLong()
    val asDouble: Double get() = asJsonPrimitive.numberValue.toDouble()
    val asBoolean: Boolean get() = asJsonPrimitive.booleanValue
}

object JsonNull : JsonElement()

class JsonPrimitive(val value: Any) : JsonElement() {
    val isString: Boolean get() = value is String
    val isNumber: Boolean get() = value is Number
    val isBoolean: Boolean get() = value is Boolean
    val stringValue: String get() = when (value) {
        is String -> value
        else -> value.toString()
    }
    val numberValue: Number get() = value as? Number ?: value.toString().toBigDecimal()
    val booleanValue: Boolean get() = value as? Boolean ?: value.toString().toBooleanStrict()
    override fun equals(other: Any?): Boolean = other is JsonPrimitive && value == other.value
    override fun hashCode(): Int = value.hashCode()
}

class JsonObject(
    private val values: LinkedHashMap<String, JsonElement> = linkedMapOf(),
) : JsonElement(), Iterable<Map.Entry<String, JsonElement>> {
    operator fun get(name: String): JsonElement? = values[name]
    fun add(name: String, value: JsonElement?) { values[name] = value ?: JsonNull }
    fun addProperty(name: String, value: String?) = add(name, value?.let(::JsonPrimitive))
    fun addProperty(name: String, value: Number?) = add(name, value?.let(::JsonPrimitive))
    fun addProperty(name: String, value: Boolean?) = add(name, value?.let(::JsonPrimitive))
    fun has(name: String): Boolean = values.containsKey(name)
    fun getAsJsonObject(name: String): JsonObject? = values[name] as? JsonObject
    fun getAsJsonArray(name: String): JsonArray? = values[name] as? JsonArray
    fun entrySet(): Set<Map.Entry<String, JsonElement>> = values.entries
    fun size(): Int = values.size
    override fun iterator(): Iterator<Map.Entry<String, JsonElement>> = values.entries.iterator()
    override fun equals(other: Any?): Boolean = other is JsonObject && values == other.values
    override fun hashCode(): Int = values.hashCode()
}

class JsonArray(
    private val values: MutableList<JsonElement> = arrayListOf(),
) : JsonElement(), Iterable<JsonElement> {
    fun add(value: JsonElement?) { values += value ?: JsonNull }
    fun add(value: String?) = add(value?.let(::JsonPrimitive))
    fun add(value: Number?) = add(value?.let(::JsonPrimitive))
    fun add(value: Boolean?) = add(value?.let(::JsonPrimitive))
    operator fun get(index: Int): JsonElement = values[index]
    fun size(): Int = values.size
    override fun iterator(): Iterator<JsonElement> = values.iterator()
    override fun equals(other: Any?): Boolean = other is JsonArray && values == other.values
    override fun hashCode(): Int = values.hashCode()
}

object JsonParser {
    fun parseReader(reader: Reader): JsonElement = parseString(reader.readText())
    fun parseString(text: String): JsonElement = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): JsonElement {
            val value = value()
            whitespace()
            require(index == text.length) { "Unexpected trailing JSON at $index" }
            return value
        }

        private fun value(): JsonElement {
            whitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> JsonPrimitive(string())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> number()
            }
        }

        private fun objectValue(): JsonObject {
            expect('{')
            val result = JsonObject()
            whitespace()
            if (take('}')) return result
            while (true) {
                whitespace()
                val key = string()
                whitespace(); expect(':')
                result.add(key, value())
                whitespace()
                if (take('}')) return result
                expect(',')
            }
        }

        private fun arrayValue(): JsonArray {
            expect('[')
            val result = JsonArray()
            whitespace()
            if (take(']')) return result
            while (true) {
                result.add(value())
                whitespace()
                if (take(']')) return result
                expect(',')
            }
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when (char) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < text.length) { "Invalid JSON escape" }
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "Invalid unicode escape" }
                                result.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Invalid JSON escape: $escaped")
                        }
                    }
                    else -> {
                        require(char.code >= 0x20) { "Control character in JSON string" }
                        result.append(char)
                    }
                }
            }
            error("Unterminated JSON string")
        }

        private fun number(): JsonElement {
            val start = index
            if (take('-')) Unit
            require(index < text.length && text[index].isDigit()) { "Invalid JSON number at $index" }
            if (text[index] == '0') index++ else while (index < text.length && text[index].isDigit()) index++
            if (take('.')) {
                require(index < text.length && text[index].isDigit())
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && text[index] in "eE") {
                index++
                if (index < text.length && text[index] in "+-") index++
                require(index < text.length && text[index].isDigit())
                while (index < text.length && text[index].isDigit()) index++
            }
            val token = text.substring(start, index)
            val decimal = BigDecimal(token)
            val value: Number = runCatching { decimal.longValueExact() }.getOrElse { decimal }
            return JsonPrimitive(value)
        }

        private fun <T : JsonElement> literal(word: String, value: T): T {
            require(text.startsWith(word, index)) { "Invalid JSON token at $index" }
            index += word.length
            return value
        }

        private fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun expect(char: Char) { require(take(char)) { "Expected '$char' at $index" } }
        private fun take(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
    }
}

class Gson {
    fun toJson(value: Any?): String = encode(toJsonTree(value))
    fun toJsonTree(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String, is Number, is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject().also { objectValue ->
            value.forEach { (key, item) -> objectValue.add(key.toString(), toJsonTree(item)) }
        }
        is Iterable<*> -> JsonArray().also { array -> value.forEach { array.add(toJsonTree(it)) } }
        is Array<*> -> JsonArray().also { array -> value.forEach { array.add(toJsonTree(it)) } }
        else -> error("Unsupported JSON value: ${value.javaClass.name}")
    }

    private fun encode(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonPrimitive -> when (val raw = value.value) {
            is String -> quote(raw)
            is Boolean, is Number -> raw.toString()
            else -> error("Unsupported JSON primitive")
        }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        is JsonObject -> value.joinToString(prefix = "{", postfix = "}") { (key, item) -> "${quote(key)}:${encode(item)}" }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

class GsonBuilder {
    fun disableHtmlEscaping(): GsonBuilder = this
    fun setPrettyPrinting(): GsonBuilder = this
    fun create(): Gson = Gson()
}
