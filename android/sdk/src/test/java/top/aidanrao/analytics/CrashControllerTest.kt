package top.aidanrao.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrashControllerTest {
    private val context = mapOf("app_id" to "crash-test", "app_version" to "old", "platform" to "android", "os_name" to "ColorOS")
    private fun idle(controller: CrashController) {
        val executor = CrashController::class.java.getDeclaredField("io").apply { isAccessible = true }.get(controller) as ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }
    @Test fun failedDeliverySurvivesRestartAndAcknowledgementDeletesOriginalRecord() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val server = MockWebServer().apply { start() }
        val endpoint = server.url("/events").toString()
        val namespace = MessageDigest.getInstance("SHA-256").digest("$endpoint\ncrash-test".toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(app.noBackupFilesDir, "event-analytics/crashes/$namespace")
        val store = CrashStore(directory)
        val record = CrashRecord.capture(context, mapOf("user_id" to "old-user"), "crash", "home", Thread.currentThread(), Error("secret"))!!
        store.save(record)
        val tracker = AutoTracker(app, AutoTrackingOptions(), { _, _ -> }, {})
        val engine = Engine(Config(endpoint, maxRetries = 0, flushIntervalMs = 60000), context + ("app_version" to "new"), mapOf("user_id" to "new-user"))
        var controller: CrashController? = null
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            controller = CrashController(app, endpoint, "crash-test", engine, tracker)
            idle(controller)
            engine.flush().get(5, TimeUnit.SECONDS)
            val failedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(1, store.read().size)
            controller.close()
            server.enqueue(MockResponse().setResponseCode(202))
            controller = CrashController(app, endpoint, "crash-test", engine, tracker)
            idle(controller)
            engine.flush().get(5, TimeUnit.SECONDS)
            val acceptedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            val first = JsonParser.parseString(failedRequest.body.readUtf8()).asJsonObject
            val second = JsonParser.parseString(acceptedRequest.body.readUtf8()).asJsonObject
            assertEquals(first["events"], second["events"])
            assertEquals("old", second.getAsJsonObject("context")["app_version"].asString)
            assertEquals("old-user", second.getAsJsonObject("identity")["user_id"].asString)
            // Queue flush and record acknowledgement are independent completion callbacks.
            repeat(100) { if (store.read().isNotEmpty()) Thread.sleep(5) }
            assertTrue(store.read().isEmpty())
        } finally {
            controller?.close(); tracker.stop(); engine.close().get(5, TimeUnit.SECONDS)
            server.shutdown(); directory.deleteRecursively()
        }
    }
    @Test fun handlerOnlyWritesToDiskAndChainsPreviousWithoutNetwork() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val server = MockWebServer().apply { start() }
        val engine = Engine(Config(server.url("/").toString()), context)
        val tracker = AutoTracker(app, AutoTrackingOptions(), { _, _ -> }, {})
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }
        var controller: CrashController? = null
        try {
            controller = CrashController(app, server.url("/").toString(), "crash-test", engine, tracker)
            idle(controller)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("secret"))
            assertTrue(chained)
            assertEquals(0, server.requestCount)
            val files = File(app.noBackupFilesDir, "event-analytics/crashes").walkTopDown().filter { it.extension == "json" }.toList()
            assertEquals(1, files.size)
            assertFalse(files.single().readText().contains("secret"))
            // Another SDK may install a later handler; shutdown must preserve it.
            val later = Thread.UncaughtExceptionHandler { _, _ -> }
            Thread.setDefaultUncaughtExceptionHandler(later)
            controller.close()
            assertSame(later, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            controller?.close(); Thread.setDefaultUncaughtExceptionHandler(original)
            tracker.stop(); engine.close().get(5, TimeUnit.SECONDS); server.shutdown()
            File(app.noBackupFilesDir, "event-analytics/crashes").deleteRecursively()
        }
    }
}
