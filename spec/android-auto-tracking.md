# Android 自动采集扩展（SDK 0.2.0，协议 v1）

此扩展不改变 ingestion envelope。事件仍使用 event_id/event_name/local_time_ms/properties，环境和设备字段置于 context。JavaScript 不因本扩展启用自动采集。Android 三个制品独立于 JS 发布，但共享 Android 版本。

## 系统上下文

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| platform | string | 默认 android |
| os_name | string | ColorOS 等具体系统名称；未知/证据冲突时 Android |
| os_version | string，可省略 | 与 os_name 对应的版本；厂商版本未知则不提供 |
| android_version | string | Android Build.VERSION.RELEASE |
| android_api_level | integer | Android API level |
| device_manufacturer / device_brand / device_model | string | Build 制造商、品牌和型号；不是设备唯一 ID |
| os_detection_source | string | vendor_property / fallback / host_override |

系统属性是厂商约定，可能缺失、复用或变化，不能只凭品牌猜 ROM。Android fallback 的 os_version 为基础 Android 版本，不表示已确认原生 ROM。厂商名称采用稳定拼写，版本保留可验证的厂商字符串（One UI 数字编码转换为主/次版本）。其他语言可以继续使用原 os_name 语义。

元数据在后台初始化，初始排队事件会补入结果，但逐事件 identity、app_version 和显式覆盖字段保持原快照。初始化前的 crash 保留当时 fallback。context 显式 os_name 覆盖优先，必须同时提供对应 os_version 才保留版本；setContext 更换 os_name 未提供版本时清除旧 os_version，来源标记 host_override。无历史数据迁移。

## 事件

名称可用 eventPrefix 添加固定前缀，例如 iclass_app_start。以下名称是无前缀形式。

| 名称 | 触发 | properties |
| --- | --- | --- |
| app_start | 开启生命周期采集后本进程第一次进入可见前台 | process_session_id |
| foreground | 进入可见前台，含第一次 | process_session_id |
| background | 进程生命周期确认所有页面不可见 | process_session_id、duration_ms（本段前台时长） |
| page_view | 当前业务页面进入 RESUMED/宿主显式声明 | process_session_id、page_id、page_view_id、reason；可选 page_name、previous_page_id |
| crash | 到达默认 JVM 未处理异常处理器 | exception_type、thread_name、fatal=true、stack_trace；可选 page_id、stack_truncated |

process_session_id 为进程内生成的临时 UUID，不跨启动持久化，不是用户/设备标识。page_view_id 每次访问生成。previous_page_id 是最近一次被统计页面，不表示物理返回栈。

页面 reason 默认 initial/navigation/fragment/foreground；自定义页面可提供不超过 64 字符的原因。系统配置重建不产生同页访问；A→B→A 和后台返回产生新访问。导航使用 route 模板，禁止自动提取路由实参。Fragment 只统计映射出的可见叶页面；多窗格选择规则见 Android 文档。同一容器不要混用适配器。

## crash 交付

默认不采集异常 message；堆栈限制为 4 层 cause、每层 32 帧，各字符串有界，并再次按标准化事件 16000 字节预算裁剪。如果仅 context/identity 已使最小记录超限，则无法保存该 crash。保存失败不能替换原异常或阻止原处理器。

最多保留 8 个记录，7 天 TTL，应用私有 noBackupFilesDir，按 endpoint + app_id 隔离。原 context、identity、event_id、local_time_ms 落盘后不因重启而更新。重启启用 crash 和后续进入前台触发补报；只在收到 202 后删除，失败保留，过期/容量淘汰及损坏记录会被清理。系统时钟明显回拨导致时间超过当前 60 秒的记录也会清理。

普通内存事件仍沿用有限重试后移出队列的行为。crash 磁盘记录独立保留，因此服务器可能收到相同 event_id 多次；服务端不保证去重，不能宣称 exactly-once。关闭 SDK 不承诺排空尚未从磁盘加载的记录，也不删除它们。禁用 crash 后不读取历史记录。

不覆盖 ANR、Native crash、系统杀进程和强制停止；没有后台服务、WorkManager 任务或持续后台重试。
