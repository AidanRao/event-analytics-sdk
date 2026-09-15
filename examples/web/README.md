# Web tarball 消费示例

先在 ../../javascript 执行 npm ci 和 npm pack，再在此目录运行：

```sh
npm install --no-save --package-lock=false ../../javascript/aidanrao-event-analytics-0.1.0.tgz
npm run dev
```

打开 http://127.0.0.1:5173，配置完整采集 URL 和 app ID，初始化后手动记录、切换身份与 flush。

本地服务 app `demo` 必须允许 `http://127.0.0.1:5178` Origin。示例默认 URL 为 http://127.0.0.1:8787/v1/events，不会自动请求生产环境。
