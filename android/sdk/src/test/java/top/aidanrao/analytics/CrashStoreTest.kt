package top.aidanrao.analytics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CrashStoreTest {
    private val context = mapOf("app_id" to "demo", "app_version" to "old", "platform" to "android", "os_name" to "ColorOS")
    @Test fun persistsOriginalSnapshotAndBoundsLargeStackWithoutMessages() {
        val dir = Files.createTempDirectory("crash-test").toFile()
        try {
            val store = CrashStore(dir)
            val crash = CrashRecord.capture(context, mapOf("user_id" to "old-user"), "crash", "settings", Thread.currentThread(), IllegalStateException("secret-token"), 1234L)
            assertNotNull(crash)
            store.save(crash!!)
            val restored = store.read(1235L).single()
            assertEquals(crash.event["event_id"], restored.event["event_id"])
            assertEquals(1234L, (restored.event["local_time_ms"] as Number).toLong())
            assertEquals("old-user", restored.identity["user_id"])
            assertFalse(Protocol.json(restored).contains("secret-token"))
            assertTrue(Protocol.fitsEvent(restored.context, restored.identity, restored.event))
            store.acknowledge(restored)
            assertTrue(store.read(1235L).isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun prunesExpiredAndCorruptRecordsAndCapsCount() {
        val dir = Files.createTempDirectory("crash-test").toFile()
        try {
            val store = CrashStore(dir)
            repeat(12) { store.save(CrashRecord.capture(context, emptyMap(), "crash", null, Thread.currentThread(), Error(), 1000L + it)!!) }
            assertEquals(8, store.read(1012L).size)
            dir.resolve("broken.json").writeText("invalid")
            assertEquals(8, store.read(1012L).size)
            assertTrue(store.read(8 * 24 * 60 * 60 * 1000L).isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun alwaysChainsPreviousHandlerEvenWhenRecorderFails() {
        var chained = 0
        val cause = RuntimeException("original")
        val handler = CrashHandler({ _, _ -> throw IllegalStateException("storage failed") }, Thread.UncaughtExceptionHandler { _, error -> assertSame(cause, error); chained++ })
        handler.uncaughtException(Thread.currentThread(), cause)
        assertEquals(1, chained)
    }
    @Test fun largeStackHasExplicitTruncationAndFitsBudget() {
        val error = RuntimeException("not-collected").apply {
            stackTrace = Array(1000) { StackTraceElement("LongClass".repeat(40), "call", "Source.kt", it) }
        }
        val record = CrashRecord.capture(context, emptyMap(), "crash", null, Thread.currentThread(), error)!!
        val props = record.event["properties"] as Map<*, *>
        assertEquals(true, props["stack_truncated"])
        assertTrue((props["stack_trace"] as List<*>).size <= 33)
        assertTrue(Protocol.fitsEvent(record.context, record.identity, record.event))
    }
}
