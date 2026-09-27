package top.aidanrao.analytics

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class CrashRecord(val context: JsonObject, val identity: JsonObject, val event: JsonObject) {
    companion object {
        fun capture(context: JsonObject, identity: JsonObject, name: String, page: String?, thread: Thread,
                    error: Throwable, time: Long = System.currentTimeMillis()): CrashRecord? {
            // No exception messages: they routinely contain URLs, credentials and user input.
            val frames = mutableListOf<String>()
            var cause: Throwable? = error
            var truncated = false
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            repeat(4) {
                val current = cause
                if (current != null && seen.add(current)) {
                    frames += current.javaClass.name.take(256)
                    val stack = current.stackTrace
                    if (stack.size > 32) truncated = true
                    stack.take(32).forEach {
                        val frame = it.toString()
                        if (frame.length > 256) truncated = true
                        frames += frame.take(256)
                    }
                    cause = current.cause
                }
            }
            val properties = linkedMapOf<String, Any?>("exception_type" to error.javaClass.name.take(256),
                "thread_name" to thread.name.take(128), "fatal" to true, "stack_trace" to frames.toList())
            if (truncated || cause != null) properties["stack_truncated"] = true
            if (page != null) properties["page_id"] = page.take(256)
            val event = mapOf("event_id" to UUID.randomUUID().toString(), "event_name" to name,
                "local_time_ms" to time, "properties" to properties)
            while (!Protocol.fitsEvent(context, identity, event) && frames.isNotEmpty()) {
                frames.removeAt(frames.lastIndex)
                properties["stack_trace"] = frames.toList()
                properties["stack_truncated"] = true
            }
            if (!Protocol.fitsEvent(context, identity, event)) return null
            return CrashRecord(Protocol.context(context), Protocol.obj(identity), Protocol.obj(event))
        }
    }
}

/** Private no-backup directory, scoped by endpoint and app ID by the owner. */
internal class CrashStore(private val directory: File) {
    private val maxAge = 7 * 24 * 60 * 60 * 1000L
    @Synchronized fun save(record: CrashRecord) {
        require(Protocol.fitsEvent(record.context, record.identity, record.event))
        if (!directory.isDirectory) check(directory.mkdirs() || directory.isDirectory)
        val target = file(record)
        val temporary = File(directory, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { out -> out.write(Protocol.json(recordMap(record)).toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(temporary.renameTo(target)) { "Cannot persist crash" }
            val records = directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
            records.take((records.size - 8).coerceAtLeast(0)).forEach { it.delete() }
        } finally { temporary.delete() }
    }
    @Synchronized fun read(now: Long = System.currentTimeMillis()): List<CrashRecord> {
        val result = mutableListOf<CrashRecord>()
        directory.listFiles().orEmpty().forEach { file ->
            try {
                if (file.extension != "json" || file.length() > 16_000) { file.delete(); return@forEach }
                val raw = Gson().fromJson(file.readText(), Map::class.java)
                val record = CrashRecord(Protocol.context(raw["context"]), Protocol.obj(raw["identity"]), Protocol.obj(raw["event"]))
                Protocol.validate(mapOf("context" to record.context, "identity" to record.identity,
                    "local_time" to now / 1000, "events" to listOf(record.event)))
                require(Protocol.fitsEvent(record.context, record.identity, record.event))
                val time = (record.event["local_time_ms"] as Number).toLong()
                require(now - time <= maxAge && time <= now + 60_000)
                require(file.name == file(record).name)
                result += record
            } catch (_: Exception) { file.delete() }
        }
        return result.sortedBy { (it.event["local_time_ms"] as Number).toLong() }.takeLast(8)
    }
    @Synchronized fun acknowledge(record: CrashRecord) { file(record).delete() }
    private fun file(record: CrashRecord): File {
        val id = record.event["event_id"] as String
        require(Regex("[A-Za-z0-9-]{1,128}").matches(id))
        return File(directory, "$id.json")
    }
    private fun recordMap(record: CrashRecord) = mapOf("context" to record.context, "identity" to record.identity, "event" to record.event)
}

internal class CrashHandler(private val record: (Thread, Throwable) -> Unit,
                            private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    private val recording = AtomicBoolean(false)
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (recording.compareAndSet(false, true)) {
                try { record(thread, error) } catch (_: Throwable) { /* crashing process: best effort only */ }
            }
        } finally {
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
}
