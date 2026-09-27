package top.aidanrao.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoTrackerTest {
    @Before fun newProcess() {
        // Robolectric reuses SDK class statics between methods; each test models a new process.
        val started = AutoTracker::class.java.getDeclaredField("processStarted").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicBoolean
        started.set(false)
    }
    @Test fun firstForegroundAndReturnEmitDistinctVisitsButRecreationDoesNot() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tracker = AutoTracker(app, AutoTrackingOptions(), { name, props -> events += name to props }, {})
        tracker.foreground()
        tracker.page("home", null, "initial")
        tracker.configurationChanged()
        tracker.page("home", null, "initial")
        tracker.background()
        tracker.foreground()
        tracker.page("home", null, "foreground")
        assertEquals(listOf("app_start", "foreground", "page_view", "background", "foreground", "page_view"), events.map { it.first })
        tracker.stop()
        tracker.foreground()
        tracker.page("ignored", null, "navigation")
        assertEquals(6, events.size)
    }
    @Test fun automaticSinkExceptionsNeverEscape() {
        val tracker = AutoTracker(ApplicationProvider.getApplicationContext(), AutoTrackingOptions(), { _, _ -> error("full") }, { error("closed") })
        tracker.foreground(); tracker.page("home", null, "initial"); tracker.background(); tracker.stop()
    }
    @Test fun sameActivityPauseResumeDoesNotCreateAnotherPageVisit() {
        val events = mutableListOf<String>()
        val tracker = AutoTracker(ApplicationProvider.getApplicationContext(), AutoTrackingOptions(), { name, _ -> events += name }, {})
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        tracker.foreground(); tracker.onActivityResumed(activity)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        tracker.onActivityPaused(activity); tracker.onActivityResumed(activity)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, events.count { it == "page_view" })
        tracker.stop()
    }
    @Test fun stoppingAndRestartingCollectionDoesNotInventAnotherProcessStart() {
        val events = mutableListOf<String>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = AutoTracker(app, AutoTrackingOptions(), { name, _ -> events += name }, {})
        first.foreground(); first.stop()
        val second = AutoTracker(app, AutoTrackingOptions(), { name, _ -> events += name }, {})
        second.foreground(); second.stop()
        assertEquals(1, events.count { it == "app_start" })
    }
}
