# Changelog

## 0.2.0

- 显式开启启动、前后台、Activity 页面和 Java/Kotlin 未处理异常采集；支持停止与重启采集。
- 增加独立 Navigation（含 Compose）和 Fragment 页面适配制品。
- crash 私有目录有界落盘，重启补报保留原 ID、时间、用户身份和版本，202 后删除。
- os_name 支持有证据的厂商系统识别，补充系统版本、Android 版本/API level、品牌和机型；识别失败回退 Android。
- 普通事件保持内存队列，协议 v1 和手动 track 接口保持不变。

## 0.1.0

- 初始 android SDK，支持 Event Analytics ingestion protocol v1。
- 手动埋点、不可变事件快照、内存批处理、有限重试、flush 与有界 close。
- 共享协议 fixtures、语言专属 CI 与独立发布流程。
