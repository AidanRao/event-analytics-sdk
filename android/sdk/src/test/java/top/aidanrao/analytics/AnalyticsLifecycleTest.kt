package top.aidanrao.analytics

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnalyticsLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun process(name: String) {
        val thread = org.robolectric.shadows.ShadowActivityThread.currentActivityThread()
        val bound = org.robolectric.util.ReflectionHelpers.getField<Any>(thread, "mBoundApplication")
        org.robolectric.util.ReflectionHelpers.setField(bound, "processName", name)
        assertEquals(name, Application.getProcessName())
        shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setProcesses(
            listOf(ActivityManager.RunningAppProcessInfo(name, android.os.Process.myPid(), arrayOf(app.packageName))))
    }
    private fun sdk(options: AutoTrackingOptions? = null) = Analytics.Builder(app, "http://127.0.0.1:1/events", "test")
        .appVersion("1").apply { if (options != null) autoTracking(options) }.build()
    private val options = AutoTrackingOptions(lifecycleEvents = false, crashes = false)
    @Test fun defaultIsManualAndCollectionHasOnlyOneOwner() {
        process(app.packageName)
        val first = sdk()
        val second = sdk()
        try {
            assertFalse(first.isAutoTrackingEnabled())
            first.startAutoTracking(options)
            assertTrue(first.isAutoTrackingEnabled())
            second.startAutoTracking(options)
            assertFalse(second.isAutoTrackingEnabled())
            first.stopAutoTracking()
            second.startAutoTracking(options)
            assertTrue(second.isAutoTrackingEnabled())
        } finally { first.close().get(5, TimeUnit.SECONDS); second.close().get(5, TimeUnit.SECONDS) }
    }
    @Test fun secondaryProcessDoesNotInstallAutomaticTracking() {
        process(app.packageName + ":push")
        val sdk = sdk(options)
        try { assertFalse(sdk.isAutoTrackingEnabled()) } finally { sdk.close().get(5, TimeUnit.SECONDS) }
    }
    @Test fun manifestConfiguredApplicationProcessIsThePrimaryProcess() {
        val original = app.applicationInfo.processName
        app.applicationInfo.processName = app.packageName + ":main"
        process(app.applicationInfo.processName)
        val sdk = sdk(options)
        try { assertTrue(sdk.isAutoTrackingEnabled()) }
        finally { sdk.close().get(5, TimeUnit.SECONDS); app.applicationInfo.processName = original }
    }
}
