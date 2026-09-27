package top.aidanrao.analytics.example

import android.app.Application
import top.aidanrao.analytics.Analytics
import top.aidanrao.analytics.AutoTrackingOptions
import top.aidanrao.analytics.ActivityPageMapper
import top.aidanrao.analytics.PageInfo

class ExampleApplication : Application() {
    lateinit var analytics: Analytics
        private set
    override fun onCreate() {
        super.onCreate()
        // A real application enables collection only after its own consent decision.
        analytics = Analytics.Builder(this, "http://10.0.2.2:8787/v1/events", "demo")
            .autoTracking(AutoTrackingOptions(activityPageMapper = ActivityPageMapper { PageInfo("example_home") }))
            .build()
    }
}
