package top.aidanrao.analytics

import com.google.gson.GsonBuilder
import java.util.Collections
import java.util.IdentityHashMap

internal typealias JsonObject = Map<String, Any?>

internal object Protocol {
    const val MAX_TIME = 9007199254740991L
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    fun json(value: Any?): String {
        val raw = gson.toJson(value)
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (Character.isHighSurrogate(c) && i + 1 < raw.length && Character.isLowSurrogate(raw[i + 1])) {
                out.append(c).append(raw[i + 1]); i += 2
            } else {
                // Gson leaves isolated UTF-16 surrogates raw; UTF-8 encoding would silently replace them.
                if (Character.isSurrogate(c)) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
                i++
            }
        }
        return out.toString()
    }
    fun bytes(value: Any?): Int = json(value).toByteArray(Charsets.UTF_8).size
    fun copy(value: Any?, seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap()), depth: Int = 0): Any? {
        require(depth <= 64) { "JSON nesting exceeds 64 levels" }
        return when (value) {
            null, is String, is Boolean -> value
            is Byte, is Short, is Int, is Long, is Float, is Double -> {
                require((value as Number).toDouble().isFinite()) { "Expected a finite JSON number" }; value
            }
            is Map<*, *> -> {
                require(seen.add(value)) { "Circular JSON value" }
                try {
                    require(value.keys.all { it is String }) { "JSON object keys must be strings" }
                    value.entries.sortedBy { it.key as String }.associate { (it.key as String) to copy(it.value, seen, depth + 1) }
                } finally { seen.remove(value) }
            }
            is List<*> -> {
                require(seen.add(value)) { "Circular JSON value" }
                try { value.map { copy(it, seen, depth + 1) } } finally { seen.remove(value) }
            }
            else -> throw IllegalArgumentException("Expected JSON primitive, Map or List")
        }
    }
    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): JsonObject {
        require(value is Map<*, *>) { "Expected a JSON object" }
        return copy(value) as JsonObject
    }
    private fun text(value: Any?) {
        // Same whitespace set as ECMAScript /\S/, rather than Kotlin's broader isWhitespace.
        require(value is String && value.length in 1..256 && value.any { it !in "\u0009\u000A\u000B\u000C\u000D\u0020\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF" }) { "Expected nonblank text of at most 256 UTF-16 units" }
    }
    private fun time(value: Any?) {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() >= 0 && value.toDouble() <= MAX_TIME.toDouble() && value.toDouble() % 1 == 0.0) { "Expected a nonnegative safe integer timestamp" }
    }
    fun context(value: Any?): JsonObject {
        val v = obj(value)
        require(v["app_id"] is String && Regex("^[A-Za-z0-9_-]{1,64}$").matches(v["app_id"] as String)) { "Invalid app_id" }
        listOf("app_version", "platform", "os_name").forEach { text(v[it]) }
        return v
    }
    fun validate(value: Any?) {
        val v = obj(value)
        require(v.keys == setOf("context", "identity", "local_time", "events")) { "Missing or unknown field" }
        context(v["context"]); obj(v["identity"]); time(v["local_time"])
        val events = v["events"]
        require(events is List<*> && events.size in 1..500) { "Expected 1–500 events" }
        events.forEach {
            val e = obj(it)
            require(e.keys == setOf("event_id", "event_name", "local_time_ms", "properties")) { "Missing or unknown event field" }
            text(e["event_id"]); text(e["event_name"]); time(e["local_time_ms"]); obj(e["properties"])
        }
    }
    fun fitsEvent(context: JsonObject, identity: JsonObject, event: JsonObject): Boolean =
        bytes(mapOf("context" to context, "identity" to identity, "event" to event, "local_time" to MAX_TIME,
            "version" to 1, "environment" to "production", "received_at_ms" to MAX_TIME)) <= 16_000
}
