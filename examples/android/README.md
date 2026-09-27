# Android AAR 消费示例

首次接入请选 [Activity](../../android/quickstart/activity.md)、[Compose Navigation](../../android/quickstart/compose-navigation.md) 或 [Fragment](../../android/quickstart/fragment.md) 最小示例。通常只需声明其中一种制品。

本目录同时依赖三个制品是为了构建时验证发布产物和两种适配器接口，不是要求业务应用照抄依赖列表。

先在 ../../android 执行 `./gradlew publishToMavenLocal`，再在此目录执行：

```sh
../../android/gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

本示例通过 Maven 坐标消费 AAR，包含 Application 级可选自动采集、Kotlin Activity 和 Java 调用，并编译校验 Navigation/Fragment 适配器入口。默认消费 0.2.0。默认连接模拟器宿主的 http://10.0.2.2:8787/v1/events，app=demo；仅示例允许明文 HTTP。

## 设备测试

启动配置了 app=demo、allow_no_origin=true 的本地 Worker，连接设备后：

```sh
adb reverse tcp:8787 tcp:8787
../../android/gradlew :app:connectedDebugAndroidTest
```

instrumentation 测试通过设备回环地址转发到宿主，确认实际 AAR 上报返回两个 accepted 事件。GitHub CI 在 API 26 x86_64 模拟器运行同一测试，使用 `test-server.py` HTTP 接受桩；这验证最低系统运行，不等同于真实 Worker → Queue → DO 集成。真实链路结果另见根目录 VERIFICATION.md。

## ClassHopper 接入参考

按 [Compose Navigation 最小示例](../../android/quickstart/compose-navigation.md) 接入，只声明 `event-analytics-navigation` 即可。

在 AppApplication 中明确创建/初始化 SDK（避免首次业务事件才触发 lazy），已有 AnalyticsReporter 保留业务事件和 identity 配置。删除原有 Activity 数量计数 flush，由 SDK 生命周期处理。在 ClassHopperApp 的根 NavHost 使用 DisposableEffect 绑定 Navigation，page_id 使用 Destination 路由模板；不把 scanId 等实参拼入名称。activityPageMapper 应预先对 MainActivity 返回 null，防止 Compose 绑定前容器事件已发出；绑定后会继续抑制容器事件，扫描 Activity 可通过 activityPageMapper 明确映射或忽略。业务 iclass_* 事件保持不变；自动事件可用 eventPrefix="iclass_"。此说明不修改该应用仓库。

厂商系统验收需用实体设备核对系统设置中的名称/版本和上报 context；Robolectric 只验证识别规则。crash 真机验收需在调试专用构建触发未处理异常并重启，核对旧事件 ID/版本/时间，以及失败补报后的保留；不要用生产用户执行。
