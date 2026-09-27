# Compose Navigation：一条依赖，一处页面绑定

适用于 Compose Navigation，也适用于使用 AndroidX Navigation 的 Fragment 页面。ClassHopper 选择这一种。以下示例假设宿主已允许采集，并已有导航容器。

## 1. 只添加 Navigation 制品

```kotlin
implementation("top.aidanrao:event-analytics-navigation:0.2.0")
```

它会传递引入核心 SDK，不需要另外声明核心或 Fragment 适配器。仍然只创建一个 Analytics 实例；应用现有的 Compose/Navigation 依赖照常保留。

## 2. 在 Application 初始化一次

把下面的初始化合并到现有 Application；新建类时在 Manifest 的 `<application>` 设置 `android:name=".AnalyticsApp"`。替换 endpoint 和 app_id。

```kotlin
import android.app.Application
import top.aidanrao.analytics.ActivityPageMapper
import top.aidanrao.analytics.Analytics
import top.aidanrao.analytics.AutoTrackingOptions

class AnalyticsApp : Application() {
    lateinit var analytics: Analytics
        private set

    override fun onCreate() {
        super.onCreate()
        analytics = Analytics.Builder(this, "https://events.aidanrao.top/v1/events", "your-app")
            .autoTracking(AutoTrackingOptions(
                activityPageMapper = ActivityPageMapper { null }
            ))
            .build()
    }
}
```

这里由导航负责业务页面，预先关闭 Activity 页面来源，避免 Compose 绑定前容器已被上报。启动、前后台和 crash 仍然开启。若另有独立 Activity 页面，可在 mapper 中只为那些页面返回 PageInfo；导航宿主继续返回 null。

## 3. 在根 NavHost 旁绑定一次

下面是宿主侧的小函数，放进应用代码。在已有根 NavHost 旁调用 `TrackNavigation(analytics, activity, navController)`；analytics 使用 Application 中的实例，activity 从宿主 ComponentActivity 传入，navController 使用现有同一个实例。

```kotlin
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavHostController
import top.aidanrao.analytics.Analytics
import top.aidanrao.analytics.navigation.AnalyticsNavigation

@Composable
fun TrackNavigation(
    analytics: Analytics,
    activity: ComponentActivity,
    navController: NavHostController
) {
    DisposableEffect(analytics, activity, navController) {
        val binding = AnalyticsNavigation.bind(analytics, activity, navController)
        onDispose { binding.close() }
    }
}
```

各个页面无需调用 track。默认 page_id 使用路由模板，例如 `announcement/detail/{id}`，不包含真实参数；重组不重复计数。不要为埋点再创建一个 NavController，也不要给同一容器再绑定 Fragment 适配器。

**ClassHopper：**在 AppApplication 明确初始化现有 AnalyticsReporter 持有的 SDK，保留原 identity/业务事件；在 ClassHopperApp 的根导航处调用上述绑定。可在 AutoTrackingOptions 中加 `eventPrefix = "iclass_"`。扫描 Activity 默认被上述 mapper 忽略，想统计时再单独映射。这份示例不要求修改每个业务页面。

非 Compose 的 Navigation 应用：在宿主 onCreate 中调用 `AnalyticsNavigation.bind(analytics, this, navController)` 即可；绑定会随 Activity 销毁关闭。XML destination 默认使用 ID，建议传 mapper 映射成稳定名称。其他自定义和生命周期说明见 [适配器文档](../navigation/README.md)。
