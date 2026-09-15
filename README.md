# Event Analytics SDK

浏览器 JavaScript 与 Android 的手动事件采集 SDK。协议放在 `spec/`，语言实现独立构建、版本与发布。

| 实现 | 包 | 初始版本 | 协议 |
| --- | --- | --- | --- |
| [JavaScript](javascript/README.md) | `@aidanrao/event-analytics` | 0.1.0 | v1 |
| [Android](android/README.md) | `top.aidanrao:event-analytics` | 0.1.0 | v1 |
| Go | 预留，尚未实现 | — | — |

SDK 只提供手动埋点，不自动采集页面、会话、用户或设备 ID。队列仅在内存中存活；进程或页面关闭可能丢失事件。上报端点必须显式配置。

## 结构

- `spec/`：协议说明、JSON Schema、两种实现共用的测试样例。
- `javascript/`：浏览器 ESM、TypeScript 类型、测试、独立版本和 CHANGELOG。
- `android/`：Kotlin SDK、Java 接口、Gradle Wrapper、测试、独立版本和 CHANGELOG。
- `examples/web/`、`examples/android/`：消费实际打包制品的示例。
- `go/`、`examples/go/`：未来实现说明，无占位模块或发布任务。
- `.github/workflows/`：JS/Android 各自的 CI 与发布工作流。

## 本地检查

```sh
cd javascript
npm ci
npm run typecheck
npm test
npm pack
cd ../android
./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease :sdk:publishToMavenLocal
```

Node 22+ 用于构建测试；JS SDK 仅支持具备 ESM、fetch、AbortController、TextEncoder 和 crypto.randomUUID 的现代浏览器（HTTPS 或 localhost）。Android 构建需要 JDK 17+、Android SDK 34，运行要求 API 26+。

两端读取同一组 `spec/fixtures`。JSON Schema 描述结构；UTF-16 字符限制、UTF-8 字节预算等附加校验见 [协议](spec/protocol.md)。服务端契约来源为 `event-analytics` 仓库的 `src/domain/events.ts` 与 `src/domain/batching.ts`，调整协议必须同步更新两端、Schema 和 fixtures。

## 独立版本与发布

JS 的版本源为 `javascript/package.json`，Android 的版本源为 `android/gradle.properties` 中的 `VERSION_NAME`。分别维护各语言 CHANGELOG，只为发生变化的语言升级版本。协议 v1 与语言包 SemVer 无绑定；不兼容协议需要新版本及明确迁移说明。

发布前更新对应版本及 CHANGELOG，通过 CI 后创建对应标签：

- `javascript/v0.1.0` → npm；GitHub Environment：`npm`。
- `android/v0.1.0` → Maven Central；GitHub Environment：`maven-central`。

发布步骤与账号配置见各语言 README。工作流检查标签与版本文件相符。代码提交本身不触发包发布。

## 服务端配置

采集路径为 `/v1/events`，浏览器 Origin 必须出现在该 app 的 origins 中；Android 不发送 Origin，要求 `allow_no_origin=true`。这些是采集白名单，不是真实客户端身份认证。SDK 不需要管理员 JWT，不发送 Cookie 或 Authorization。

成功的 202 仅表示请求已入队，明细与分析随后异步可见。服务端分析可能因重试重复计数，不保证 exactly-once。不要在自由属性中放入密码或其他凭据。

## 许可证

[MIT](LICENSE)。实际发布状态与本地验证记录见 [验证说明](VERIFICATION.md)。
