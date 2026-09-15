# Event Analytics Android

Kotlin 实现、Java 可直接调用，最低 Android 8.0 / API 26，协议 v1。

```kotlin
implementation("top.aidanrao:event-analytics:0.1.0")
```

```kotlin
val analytics = Analytics.Builder(applicationContext,
    "https://events.aidanrao.top/v1/events", "your-app")
    .onError { error -> /* 不默认输出事件载荷 */ }
    .build()
analytics.setIdentity(mapOf("user_id" to "user-1"))
analytics.track("button_clicked", mapOf("button" to "save"))
analytics.flush { result ->
    // 回调可能在后台线程，也可能在无待发送事件时同步调用；更新 UI 请切回主线程。
}
analytics.setIdentity(emptyMap())
analytics.close()
```

```java
Analytics analytics = new Analytics.Builder(context,
    "https://events.aidanrao.top/v1/events", "your-app")
    .appVersion("1.0.0")
    .build();
analytics.track("button_clicked", Collections.singletonMap("button", "save"));
analytics.flush(result -> { /* result.getAccepted(), result.getFailed() */ });
analytics.close();
```

默认从 Application Context 读取包版本名，缺失时为 unknown；appVersion 可覆盖。默认 platform=android、os_name=Android，可用 context 覆盖。SDK 不保留 Activity，不要求协程，不设置后台服务或持久化任务。

Builder 提供 context、identity、batchSize、flushIntervalMs、maxQueueSize、timeoutMs、maxRetries、retryBaseMs、closeTimeoutMs、onError。默认参数与 JS 相同，见 [协议](../spec/protocol.md)。setContext 合并 patch，禁止改变 app_id；setIdentity 整体替换。

track 返回 ID，非法值或容量不足会同步抛出 IllegalArgumentException，已关闭时抛出 IllegalStateException。属性仅接受 JSON 原始值、Map<String, ?> 和 List，不直接序列化模型对象。线程安全，HTTP 和重试在专属后台执行器运行。

flush()/close() 返回 CompletableFuture<FlushResult>，也有 Java SAM 回调重载；不要在 Android 主线程调用 Future.get/join。错误回调可能来自调用线程或发送线程。close 默认等待上限 15 秒，立即返回 Future，幂等。

SDK Manifest 声明 INTERNET 权限；不更改宿主明文网络、备份或通知设置。公开上报不需要管理员令牌，服务端 app 必须允许无 Origin 请求。

## 构建和本地消费验证

需要 JDK 17+、Android SDK 34，通过 ANDROID_HOME 或 local.properties 配置 SDK。

```sh
./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease :sdk:publishToMavenLocal
cd ../examples/android
../../android/gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

示例从 Maven Local 读取真正的 AAR/POM，不使用 project dependency。升级版本时传 `-PsdkVersion=X.Y.Z`。设备端测试见 examples/android/README.md。

## 发布到 Maven Central

1. 在 Central Portal 验证 `top.aidanrao` namespace：按平台提供的校验值在 `aidanrao.top` 配置 DNS TXT。不能假设 GitHub 登录已验证此域名。[官方说明](https://central.sonatype.org/register/namespace/)
2. 生成 Central Portal User Token 与制品签名密钥，按平台要求公开签名公钥。
3. 在 GitHub `maven-central` Environment 配置 `MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`（User Token 对）、`SIGNING_IN_MEMORY_KEY`（ASCII-armored 私钥）和 `SIGNING_IN_MEMORY_KEY_PASSWORD`。
4. 更新 gradle.properties 的 VERSION_NAME 和 CHANGELOG，通过 CI 后创建 `android/vX.Y.Z` 标签。

使用 Gradle Maven Publish plugin 的 Central Portal 支持，上传签名 AAR、sources、文档、POM 和 Gradle 元数据；不使用已停用的 OSSRH 上传地址。详情见 [发布插件说明](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)。本地 Maven 验证不要求签名密钥，正式工作流明确检查凭据和密钥存在。

本仓库不代表 Central namespace 已验证或包已发布。
