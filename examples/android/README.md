# Android AAR 消费示例

先在 ../../android 执行 `./gradlew :sdk:publishToMavenLocal`，再在此目录执行：

```sh
../../android/gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

本示例通过 Maven 坐标消费 AAR，包含 Kotlin Activity 和 Java 调用。默认连接模拟器宿主的 http://10.0.2.2:8787/v1/events，app=demo；仅示例允许明文 HTTP。

## 设备测试

启动配置了 app=demo、allow_no_origin=true 的本地 Worker，连接设备后：

```sh
adb reverse tcp:8787 tcp:8787
../../android/gradlew :app:connectedDebugAndroidTest
```

instrumentation 测试通过设备回环地址转发到宿主，确认实际 AAR 上报返回两个 accepted 事件。GitHub CI 在 API 26 x86_64 模拟器运行同一测试，使用 `test-server.py` HTTP 接受桩；这验证最低系统运行，不等同于真实 Worker → Queue → DO 集成。真实链路结果另见根目录 VERIFICATION.md。
