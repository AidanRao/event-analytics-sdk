package top.aidanrao.analytics.navigation

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.robolectric.Shadows.shadowOf
import top.aidanrao.analytics.Analytics
import top.aidanrao.analytics.AutoTrackingOptions
import java.util.concurrent.TimeUnit

abstract class AdapterTestBase {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.body.readUtf8()
                return MockResponse().setResponseCode(202)
            }
        }
        start()
    }
    protected lateinit var analytics: Analytics
    protected fun initialize() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Robolectric otherwise leaves Application.getProcessName() unset.
        val activityManager = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        shadowOf(activityManager).setProcesses(listOf(android.app.ActivityManager.RunningAppProcessInfo(
            app.packageName, android.os.Process.myPid(), arrayOf(app.packageName))))
        analytics = Analytics.Builder(app, server.url("/v1/events").toString(), "adapter-tests")
            .appVersion("1").flushIntervalMs(60000).maxRetries(0)
            .autoTracking(AutoTrackingOptions(crashes = false)).build()
        tracker("foreground")
    }
    protected fun tracker(method: String) {
        val field = Analytics::class.java.getDeclaredField("autoTracker").apply { isAccessible = true }
        val tracker = checkNotNull(field.get(analytics))
        tracker.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(tracker)
    }
    protected fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    protected fun pages(): List<String> {
        analytics.flush().get(5, TimeUnit.SECONDS)
        return requests.flatMap { body ->
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("events").mapNotNull { value ->
                val event = value.asJsonObject
                if (event["event_name"].asString == "page_view") event.getAsJsonObject("properties")["page_id"].asString else null
            }
        }
    }
    @After fun cleanupAnalytics() {
        if (::analytics.isInitialized) analytics.close().get(5, TimeUnit.SECONDS)
        server.shutdown()
    }
}
