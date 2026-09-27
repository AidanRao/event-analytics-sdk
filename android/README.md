# Event Analytics Android

Kotlin 实现、Java 可直接调用，最低 Android 8.0 / API 26，协议 v1，版本 0.2.0。

```kotlin
implementation("top.aidanrao:event-analytics:0.2.0")
// 根据页面框架选择，不需要 Compose 依赖即可使用 Navigation 适配器。
implementation("top.aidanrao:event-analytics-navigation:0.2.0")
// 非 Navigation 的 Fragment 页面可选择：
implementation("top.aidanrao:event-analytics-fragment:0.2.0")
```

## 初始化与手动事件

在 Application 中创建一个实例并保留到进程结束。仅当宿主决定允许采集时初始化；SDK 不弹出授权 UI、不生成用户或设备 ID。

```kotlin
val analytics = Analytics.Builder(application, "https://events.aidanrao.top/v1/events", "your-app")
    .identity(mapOf("user_id" to existingUserId))
    .autoTracking(AutoTrackingOptions(
        eventPrefix = "", // 例如 ClassHopper 可使用 "iclass_"
        activityPageMapper = ActivityPageMapper { activity ->
            PageInfo(activity.javaClass.name) // 推荐映射成稳定业务名称；返回 null 忽略
        }
    ))
    .onError { error -> /* 不打印载荷；回调线程不固定 */ }
    .build()
analytics.track("button_clicked", mapOf("button" to "save"))
analytics.setIdentity(emptyMap())
analytics.flush { result -> /* 更新 UI 请切回主线程 */ }
```

不调用 `autoTracking()` 时，只提供手动埋点和系统上下文。自动采集可单独配置 lifecycleEvents/pageViews/crashes；默认三个开关都是 true，但必须先显式开启自动采集。

```java
Analytics analytics = new Analytics.Builder(application, endpoint, appId)
    .autoTracking(new AutoTrackingOptions())
    .build();
analytics.track("button_clicked", Collections.singletonMap("button", "save"));
```

`startAutoTracking(options)` 可在允许采集后开启，`stopAutoTracking()` 停止自动采集；启停在主线程执行，从其他线程调用时会派发到主线程。停止不取消已有发送、不删除已落盘 crash，也不禁止手动 track；需要停止全部上报时调用 close，未初始化前不采集。关闭后不可重新开启；重新开启自动采集后需要重新绑定页面适配器。进程内只允许一个实例持有自动监听；其他实例仍可手动上报。

## 启动、前后台与页面

- `app_start`：主进程第一次在开启生命周期采集的情况下进入可见前台。后台 Service 拉起不计入；同进程启停采集不会重复计数。这不是冷/温/热启动性能指标。
- `foreground` / `background`：基于 ProcessLifecycleOwner 的可见性，分屏/PiP 可见仍算前台；Activity 跳转和配置重建不算前后台切换。background 携带单调时钟计算的 duration_ms，并尝试异步 flush。强杀不保证产生 background。
- `page_view`：Activity resume 或适配器选中的业务页面进入 RESUMED。临时失焦、重组不重复计数；A→B→A 和后台返回各记录新访问；配置重建抑制同页重报。
- 生命周期和页面事件包含 process_session_id；page_view 还包含 page_id、page_view_id、reason，以及可用时的 page_name/previous_page_id。

迟于 Activity.resume 才初始化时，前台状态会同步，但原生 Activity 页面需调用 `pageView("home")` 告知当前页，或等待下次页面生命周期。Navigation/Fragment 绑定会主动读取当前页面。普通事件队列仍只在内存中，退后台 flush 不保证进程结束前送达。

### Compose / Navigation

在根导航容器绑定一次，避免在每个 Composable 内上报。建议 Activity.onCreate 中绑定；Compose/延迟绑定时，在 activityPageMapper 中预先对 MainActivity 等导航宿主返回 null，以防绑定前 Activity 已发出容器页面事件（已发出的事件不能撤回）。监听导航目的地和当前 back-stack entry 的生命周期，不监听 Compose 重组。

```kotlin
DisposableEffect(analytics, activity, navController) {
    val binding = AnalyticsNavigation.bind(analytics, activity, navController) { destination ->
        destination.route?.let { PageInfo(it) }
    }
    onDispose { binding.close() }
}
```

`activity` 需要实现 LifecycleOwner。默认使用 route 模板（例如 announcement/detail/{id}），不会读取导航参数。XML navigation 默认使用 destination ID，建议自行映射稳定名称。返回 null 排除页面。绑定自动抑制所属 Activity 页面，避免容器重复上报。每个容器只绑定一个适配器。详情见 [Navigation 适配器](navigation/README.md)。

### Fragment / 自定义页面 / WebView

Fragment 页面通过 [Fragment 适配器](fragment/README.md) 显式映射业务 Fragment；容器返回 null。show/hide 后调用 refreshVisibility；多窗格用 primaryNavigationFragment 明确选择统计页。不能对同一容器同时绑定 Navigation 和 Fragment。

自定义容器可调用 `analytics.pageView("page_id", "标题")`，并持有 `analytics.suppressActivityPages(activity)` 返回的 Closeable 抑制容器页面，退出时 close。此入口只在自动页面采集已开启且前台时生效。WebView 内部路由由网页 SDK 或宿主桥接，不自动采集网页 URL/表单内容。

## 系统与设备信息

手动和自动事件默认包含：platform=android、os_name、可获得时的 os_version、android_version、android_api_level、device_manufacturer、device_brand、device_model、os_detection_source。

os_name 使用具体系统，例如 ColorOS；os_version 对应该系统，绝不把 Android 版本当成 ColorOS 版本。探测无证据或冲突时回退 Android，此时 os_version 为 Android 基础版本；检测到厂商系统但版本编码未知时省略 os_version。

当前规则覆盖有明确属性证据的 ColorOS、realme UI、OxygenOS、HyperOS/MIUI、OriginOS/Funtouch OS、One UI、EMUI、Magic UI/MagicOS、Flyme。它们是厂商约定的尽力识别，不代表全部机型已实测；OnePlus 等设备只有共享 Oplus 属性时宁可回退，不能按品牌猜系统。EMUI 兼容属性也可能保留在其他 Huawei 固件中，不承诺 HarmonyOS 识别；纯 HarmonyOS NEXT 不属于这个 Android SDK 的运行范围。

探测在后台进行，每进程缓存一次，仅查询固定系统属性白名单，无隐藏 API 反射/提权/额外权限。属性子进程单次等待最多 60ms，累计探测预算 750ms，输出有上限。首次发送等待该任务完成；初始化期间排队事件补充同一次探测结果，身份和显式 context 快照不变。探测前发生的 crash 使用当时的 Android fallback 上下文。metadata 补充后超出事件字节预算会通过 onError 报告失败。

`.context(mapOf("os_name" to "CustomOS", "os_version" to "1"))` 可覆盖；setContext 更换 os_name 而未提供 os_version 时清除旧版本，来源标记 host_override。历史 Android 数据不会反推厂商系统。所有字段约定见 [自动采集协议](../spec/android-auto-tracking.md)。

## Java/Kotlin crash

仅捕获到达默认 UncaughtExceptionHandler 的未处理异常；不覆盖已捕获异常、所有协程异常、Native crash、ANR、系统杀进程和强制停止。发生异常时只尝试同步保存有界记录，随后调用原处理器，绝不等待网络。磁盘满、OOM 或系统立即终止时可能无法保存。

记录在应用私有 noBackupFilesDir，按 endpoint + app_id 隔离；最多 8 条，每条满足 16000 字节标准化预算，保存 7 天。异常消息默认不采集，堆栈最多 4 层 cause、每层 32 帧并按字节预算裁剪；保留当时的 page_id、身份、应用/系统上下文、事件 ID 与时间。宿主身份/context 中自定义敏感字段仍由宿主管理。

下次开启 crash 采集时及之后进入前台时尝试补报；只在 202 后删除，失败保留至过期/容量淘汰。该语义可能重复送达，稳定 event_id 不代表服务端 exactly-once。普通 flush/close 的统计只包括调用时已入队事件，不等待尚未从磁盘读取的 crash。禁用 crash 不读取历史记录。卸载/清理应用数据会删除记录。

关闭时只恢复仍由本 SDK 持有的处理器，不覆盖其他 SDK 后装的处理器；其他 SDK 应保留处理链。Release 堆栈可能混淆，宿主应保留该版本 mapping.txt 以便还原。

## 既有接口和构建

Builder 提供 appVersion、context、identity、batchSize、flushIntervalMs、maxQueueSize、timeoutMs、maxRetries、retryBaseMs、closeTimeoutMs、onError。默认版本读取包版本名，缺失时 unknown。手动 track 的非法输入/容量不足同步抛 IllegalArgumentException，关闭后抛 IllegalStateException；自动采集回调隔离这些失败。属性仅接受 JSON 原始值、Map、List。

flush()/close() 返回 CompletableFuture<FlushResult>，也有 Java 回调重载。不要在 Android 主线程 get/join；close 默认截止 15 秒且幂等。SDK 只声明 INTERNET 权限，不增加后台服务或持久任务，不修改明文网络、备份或通知设置。服务端 app 需要允许无 Origin 请求。

需要 JDK 17+、Android SDK 34：

```sh
./gradlew testDebugUnitTest lint assembleRelease publishToMavenLocal
cd ../examples/android
../../android/gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

示例消费三个实际 Maven AAR/POM，默认版本 0.2.0，可用 -PsdkVersion=X.Y.Z 覆盖。发布和版本对三个 Android 制品统一管理，JS 独立。设备端验收见 examples/android/README.md。

## 发布到 Maven Central

1. 在 Central Portal 验证 `top.aidanrao` namespace：按平台提供的校验值在 `aidanrao.top` 配置 DNS TXT。不能假设 GitHub 登录已验证此域名。[官方说明](https://central.sonatype.org/register/namespace/)
2. 生成 Central Portal User Token 与制品签名密钥，按平台要求公开签名公钥。
3. 在 GitHub `maven-central` Environment 配置 `MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`（User Token 对）、`SIGNING_IN_MEMORY_KEY`（ASCII-armored 私钥）和 `SIGNING_IN_MEMORY_KEY_PASSWORD`。
4. 更新 gradle.properties 的 VERSION_NAME 和 CHANGELOG，通过 CI 后创建 `android/vX.Y.Z` 标签。

使用 Gradle Maven Publish plugin 的 Central Portal 支持，上传签名 AAR、sources、文档、POM 和 Gradle 元数据；不使用已停用的 OSSRH 上传地址。详情见 [发布插件说明](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)。本地 Maven 验证不要求签名密钥，正式工作流明确检查凭据和密钥存在。

本仓库不代表 Central namespace 已验证或包已发布。
