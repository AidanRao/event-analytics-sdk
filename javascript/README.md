# @aidanrao/event-analytics

浏览器 ESM SDK，内置 TypeScript 类型，支持协议 v1。运行环境需 HTTPS 或 localhost，以及原生 fetch、AbortController、TextEncoder、crypto.randomUUID。

```sh
npm install @aidanrao/event-analytics
```

```ts
import { Analytics } from '@aidanrao/event-analytics';

const analytics = new Analytics({
  endpoint: 'https://events.aidanrao.top/v1/events',
  appId: 'your-app',
  appVersion: '1.0.0',
  onError: error => console.warn(error.reason, error.eventIds, error.code),
});
analytics.setIdentity({ user_id: 'signed-in-user' });
const id = analytics.track('button_clicked', { button: 'save' });
analytics.setIdentity({}); // 仅影响后续事件
analytics.setContext({ app_version: '1.0.1', channel: 'stable' });
const result = await analytics.flush(); // { accepted, failed }
await analytics.close();
```

`track` 同步验证并入队，返回 ID；非法数据和队列满时抛错。`properties` 可省略，默认为 `{}`。`setContext` 合并 patch，禁止改变 app_id。默认 platform=web；os_name 尝试基础识别，无法识别为 unknown，可通过 context 覆盖。appVersion 必填。

默认配置：batchSize=20、flushIntervalMs=10000、maxQueueSize=1000、timeoutMs=10000、maxRetries=3、retryBaseMs=1000、closeTimeoutMs=15000。全部语义及边界见 [protocol v1](../spec/protocol.md)。错误回调只报告终止失败或拒绝入队；临时重试不会反复回调。

队列仅在内存中。visibilitychange → hidden 时尝试小批次 keepalive；若页面被销毁，结果可能无法确认。请在业务需要等待发送时显式 await flush/close。不要在 Node.js 生产环境使用此浏览器 SDK。

## 开发与示例

```sh
npm ci
npm run typecheck
npm test
npm pack
cd ../examples/web
npm install --no-save --package-lock=false ../../javascript/aidanrao-event-analytics-0.1.0.tgz
npm run dev
```

示例消费 tarball，不直接链接源代码。修改版本后替换对应 tarball 文件名。

## 发布到 npm

1. 在 `package.json` 更新版本，刷新 package-lock，并维护 CHANGELOG。
2. 确认 npm 账号拥有 `@aidanrao` scope 的发布权限。第一次可在已登录且启用必要 2FA 的环境执行 `npm publish --access public` 创建包；首次发布是独立运维操作。
3. 在包的 Trusted publishing 设置关联 GitHub owner `AidanRao`、repo `event-analytics-sdk`、workflow `release-js.yml`、environment `npm`。启用发布权限。
4. 创建对应 `javascript/vX.Y.Z` 标签，工作流使用 Node 24、OIDC `id-token: write` 发布。不要把长期 npm Token 提交进仓库。

首次手动发布过的版本不可再次上传；后续标签应使用尚未发布的新版本。[npm trusted publishing](https://docs.npmjs.com/trusted-publishers/)。本仓库不代表 npm 包已经发布。
