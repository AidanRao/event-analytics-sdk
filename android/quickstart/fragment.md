# Fragment 页面：一条依赖，一处页面映射

适用于未使用 AndroidX Navigation 的 Fragment 应用。已使用 Navigation 的项目选择 [Navigation 示例](compose-navigation.md)。以下假设宿主已允许采集。

## 1. 只添加 Fragment 制品

```kotlin
implementation("top.aidanrao:event-analytics-fragment:0.2.0")
```

核心 SDK 会被传递引入，不需要另外声明；全应用仍使用一个 Analytics 实例。

## 2. 在 Application 初始化一次

把初始化合并到现有 Application；新建类时在 Manifest 的 `<application>` 设置 `android:name=".AnalyticsApp"`。替换 endpoint 和 app_id。

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

Fragment 负责页面统计，Activity 容器预先排除，启动、前后台和 crash 仍然开启。混合应用可在 mapper 中只为独立 Activity 页面返回 PageInfo。

## 3. 在宿主 Activity 映射一次

在现有 FragmentActivity（或 AppCompatActivity）的 onCreate 中、super.onCreate 之后绑定。HomeFragment/SettingsFragment 换成你的业务页面类型，容器 Fragment 返回 null。

```kotlin
import top.aidanrao.analytics.PageInfo
import top.aidanrao.analytics.fragment.AnalyticsFragments
import top.aidanrao.analytics.fragment.FragmentPageBinding

// Activity 成员
private lateinit var pageTracking: FragmentPageBinding

// 放进现有 onCreate；使用同一个 Application 级 SDK
val analytics = (application as AnalyticsApp).analytics
pageTracking = AnalyticsFragments.bind(analytics, this) { fragment ->
    when (fragment) {
        is HomeFragment -> PageInfo("home")
        is SettingsFragment -> PageInfo("settings")
        else -> null
    }
}
```

各个 Fragment 无需增加埋点。绑定随 Activity 销毁自动关闭；若提前移除页面容器，调用 `pageTracking.close()`。

如果页面切换使用 show/hide，在事务提交完成后刷新一次可见性，例如：

```kotlin
supportFragmentManager.beginTransaction()
    .hide(previousFragment)
    .show(nextFragment)
    .runOnCommit { pageTracking.refreshVisibility() }
    .commit()
```

示例适用于未加入 back stack 的 show/hide 事务；其他事务在其完成回调中刷新即可。ViewPager2、嵌套 Fragment 和多窗格选择规则见 [适配器文档](../fragment/README.md)。
