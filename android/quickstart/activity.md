# Activity 页面：两步接入

适用于一个 Activity 对应一个页面的应用。会采集启动、前后台、Activity 页面和 Java/Kotlin 未处理异常，并补充系统信息。以下示例假设宿主已允许采集。

## 1. 添加一条依赖

```kotlin
implementation("top.aidanrao:event-analytics:0.2.0")
```

## 2. 在 Application 初始化一次

把初始化放进现有 Application，保存实例供业务事件使用。下面用独立类展示完整写法；已有 Application 时合并代码即可。

```kotlin
import android.app.Application
import top.aidanrao.analytics.Analytics

class AnalyticsApp : Application() {
    lateinit var analytics: Analytics
        private set

    override fun onCreate() {
        super.onCreate()
        analytics = Analytics.Builder(this, "https://events.aidanrao.top/v1/events", "your-app")
            .autoTracking()
            .build()
    }
}
```

将 endpoint 和 app_id 换成自己的配置；新建 Application 时在现有 Manifest 的 `<application>` 上设置 `android:name=".AnalyticsApp"`。每个 Activity 无需再调用页面上报，也不要在 Activity.onDestroy 中关闭这个进程共享实例。

默认 page_id 是 Activity 类名。需要跨混淆/重命名保持稳定时，把 `.autoTracking()` 换成以下配置；HomeActivity/SettingsActivity 对应你自己的页面类型：

```kotlin
import top.aidanrao.analytics.ActivityPageMapper
import top.aidanrao.analytics.AutoTrackingOptions
import top.aidanrao.analytics.PageInfo

.autoTracking(AutoTrackingOptions(
    activityPageMapper = ActivityPageMapper { activity ->
        when (activity) {
            is HomeActivity -> PageInfo("home")
            is SettingsActivity -> PageInfo("settings")
            else -> null
        }
    }
))
```

需要业务事件时继续调用同一个 `analytics.track(...)`。延迟授权、事件语义和 crash 边界见 [完整文档](../README.md)。
