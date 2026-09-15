# 验证记录

日期：2026-09-15。JavaScript 0.1.0 已发布到 npmjs.com；Android 尚未发布到 Maven Central，也未推送 Git。

## 已完成

| 检查 | 结果 |
| --- | --- |
| JavaScript TypeScript 类型检查、ESM 与声明构建 | 通过 |
| JavaScript Vitest 4.1.11 | 36 项通过，含共享协议 fixtures |
| npm tarball 构建与 Web 示例实际安装 | 通过，制品包含 ESM、类型、README、CHANGELOG 和 MIT |
| npm 依赖升级后的安装审计 | 0 vulnerabilities |
| Android 单元测试 | 13 项通过，含共享 fixtures、MockWebServer 重试/超时/字节预算与 UTF-16 序列化 |
| Android AAR、sources、文档与本地 Maven 发布 | 通过 |
| Android 示例 Kotlin/Java 编译 | 通过，消费 Maven Local 的 AAR/POM |
| API 36.1 instrumentation | 1 项通过，实际 AAR 上报两条事件到本地 Worker |
| 浏览器 CORS 上报 | 通过，页面返回 accepted=1、failed=0 |
| 浏览器真实导航触发隐藏发送 | 观察到 visibility=hidden、keepalive=true、请求 305 字节 |
| Worker → Queue → DO 明细查询 | 读到浏览器事件及 Android 两条事件，身份分别为 user-A、java-user、空对象 |
| 四个 GitHub Actions YAML | 本地 YAML 解析通过；尚未在线执行 |

本地 Worker 使用独立 8789 端口（8787 已有服务），通过启动参数配置 app=demo、Web Origin=http://127.0.0.1:5173、allow_no_origin=true；没有改动服务端源码或部署配置。Android 测试端口通过 adb reverse 映射至 8789。

## 最终收尾与边界

- Android 末轮收尾把周期任务改成 scheduleWithFixedDelay，避免缓存进程恢复时集中补跑，并把 MIT 文本放入 AAR 的 META-INF/top.aidanrao.analytics.LICENSE。该修改之后执行编译、lint 和本地 Maven 打包；按用户要求不再追加模拟测试。
- 回调异常测试改为先排队再 flush，以消除网络线程与计数快照之间的测试竞争；此测试调整未额外重跑。
- 当前机器只有 API 36.1 模拟器，API 26 本地运行未验证。CI 已配置 API 26 x86_64 测试，但线上 CI 尚未执行。
- API 26 CI 使用 HTTP 接受桩；它不代替真实 Worker 集成。实际 Worker 链路已在浏览器与 API 36.1 验证。
- 隐藏发送已观察到真实 keepalive 请求，但其入库结果的额外专项复查未完成，不据此承诺页面退出可靠送达。
- 最后的额外 npm ci 干净安装和隐藏事件专项明细复查被自动审批拒绝：审批服务返回用量限制。已有依赖安装、类型检查、36 项测试和 tarball 消费验证已成功；锁文件版本与 package.json 一致。
- npm 首次人工授权发布已验证。GitHub Actions OIDC 尚未配置或在线验证；Maven Central namespace、签名与 Android 远程上传未验证。各语言 README 提供配置步骤。

## npm 首次发布

- 包：[@aidanrao/event-analytics](https://www.npmjs.com/package/@aidanrao/event-analytics)，版本 `0.1.0`，public，`latest=0.1.0`。
- 使用 npm 账号 `aidanrao`，完成网页发布授权；`npm publish --access public --registry=https://registry.npmjs.org` 返回成功。
- 发布前重新执行 TypeScript 类型检查、构建和打包清单检查，共 8 个文件；按用户要求未追加模拟测试。
- npm 版本详情与 tarball 下载均返回 HTTP 200；下载制品为 7572 字节，SHA-512 与发布前一致。
- npm 安装索引返回 HTTP 200，确认版本列表包含 `0.1.0` 且 latest 标签一致。发布刚完成时普通元数据路径短暂返回 404。
- 下一步在 npm 包设置中配置 Trusted Publisher：GitHub 用户 `AidanRao`、仓库 `event-analytics-sdk`、工作流 `release-js.yml`、Environment `npm`，允许 `npm publish`。后续使用新的版本号发布，不能重复上传 `0.1.0`。

## 可重现命令

```sh
cd javascript
npm ci
npm run typecheck
npm test
npm pack
cd ../android
./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease :sdk:publishToMavenLocal
cd ../examples/android
../../android/gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

设备与 Web 示例的运行步骤分别见 examples/android/README.md、examples/web/README.md。SDK 只保留内存队列，不保证 exactly-once 或页面/进程退出后的送达。
