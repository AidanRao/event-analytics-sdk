# Event Analytics ingestion protocol v1

## 请求和响应

`POST <explicit endpoint URL>`，`Content-Type: application/json`。公开请求无协议版本字段；v1 对应服务端 `/v1/events`。内部 Queue 的 `version`、`environment`、`received_at_ms` 不属于 SDK 请求。

```json
{
  "context": {"app_id":"demo","app_version":"1.0","platform":"web","os_name":"unknown"},
  "identity": {},
  "local_time": 1720000000,
  "events": [{"event_id":"unique-id","event_name":"button_clicked","local_time_ms":1720000000123,"properties":{}}]
}
```

- context 必须包含四个非空字段；`app_id` 匹配 `[A-Za-z0-9_-]{1,64}`。其余必填文本（包括事件 ID 和名称）至少有一个 ECMAScript 非空白字符，最多 256 个 UTF-16 code units，与服务端 Zod string 长度一致。
- identity、properties 必须为 JSON 对象，可为空。context 和 identity 扩展字段完整保留；请求顶层及 event 不允许未知字段。
- 时间必须是 0 至 9007199254740991 的整数。local_time 为每次批次发送的 Unix 秒，local_time_ms 为事件发生的 Unix 毫秒。重试只更新前者。
- JSON Schema draft 2020-12 的 maxLength 按 Unicode code points 计算；UTF-16 长度上限仍须运行附加校验。字节预算也不由 JSON Schema 表达。
- SDK 接口接受 JSON 原始值、数组/List、普通对象/Map；拒绝非有限数字、循环引用、非字符串 Map 键、undefined、BigInt、Date 和任意模型对象。为有界验证，SDK 拒绝超过 64 层的 JSON 嵌套；这是 SDK 附加限制。
- 为序列化长度可预测，SDK 对对象键排序并深拷贝，不修改调用方对象。

202 无 body、Content-Length 为 0，不解析 JSON。仅 202 被视为接受；200、重定向等不视为成功。失败时读取 HTTP 状态，以及可选的 `{"error":{"code":"B02-001","message":"...","details":{}}}`。HTML 或空错误正文也应正确处理。

## 大小预算

请求至少 1 条、最多 500 条事件，JSON UTF-8 最多 1,000,000 字节。默认按 20 条拆批；同时按字节拆批。

服务端单事件限制针对以下**标准化对象**，不是 events 中的事件本身：

```text
{context, identity, local_time, version:1, environment:"production", received_at_ms, event}
```

SDK 使用上述结构计算 UTF-8 字节数，将两个时间都代入 9007199254740991，须 ≤16000。这样为内部元数据及时间长度预留预算；个别服务端实际可接受的边界事件会被 SDK 保守拒绝。HTTP 拆包也用最大时间值预留宽度。

浏览器在页面隐藏时把每批上限收紧到 60000 字节，使用 fetch keepalive。多个实例或其他 keepalive 请求可能共享浏览器配额，不能保证页面退出送达。

## 队列、快照与重试

- track 生成 UUID 和发生时间，保存事件/身份/上下文快照，返回 event ID。非法输入或满队列同步抛出错误并报告；已关闭实例拒绝调用。
- appId 固定；setIdentity 整体替换，空对象清除身份；setContext 合并 patch，保留其他字段。仅新事件采用新值。
- 仅相邻、相同 context 和 identity 快照的事件合批。入队顺序不因重试或身份变化而重排；实例内至多一个 HTTP 请求在途。
- 默认容量 1000，包括发送中条目；每 10000ms 或累计 20 条触发排空。满队列拒绝新事件，保留已有队列。
- 网络错误、超时、429 和 5xx 最多额外重试 3 次。请求默认超时 10000ms；退避为 `min(60000, retryBaseMs * 2^attempt) * U[0.5,1)`，默认 retryBaseMs=1000。
- 重试整批原事件、原 ID、原事件时间，不根据 `confirmed_events` 移除部分事件。`possibly_partial=true` 以及丢失响应均可能意味着已接受过部分事件。
- 其余状态立即终止该批；重试耗尽也终止。终止批次移出队列并回调 reason、eventIds、可选 status/code，不自动记录载荷。回调异常不能破坏队列。
- flush 捕获调用时待完成事件并触发发送，等待这些事件确认或终止失败，返回 accepted/failed 数量；后加入事件不影响该次等待。并发 flush 可以统计同一批事件。
- close 幂等，停止接收与周期定时器，flush 后释放资源；默认 15000ms 截止。截止时取消请求/退避、终止剩余条目。期间的 flush 同样会完成。
- 汇总中的 accepted 只表示收到 202；终止失败不代表服务端一定未收到。无持久化、重启补发或 exactly-once 承诺。

## 配置边界

两端统一使用毫秒配置：batchSize 1–500，flushIntervalMs 1–2147483647，maxQueueSize 1–100000，timeoutMs/closeTimeoutMs 1–300000，maxRetries 0–10，retryBaseMs 1–60000。无效配置同步拒绝。

endpoint 必须为完整 HTTP(S) URL，无用户名、密码或 fragment。SDK 不拼接路径、不跟随重定向、不携带凭据。Android 示例仅为本地测试允许明文 HTTP；SDK 不修改宿主网络安全策略。

## 演进和 fixtures

`requests.json` 同时验证两端请求结构；`sdk-cases.json` 验证共享 UTF-8 大小策略。非法 JSON 运行时值不能编码进 JSON 文件，由语言专属测试补充。新增字段或修改约束必须更新这两组测试，并与服务端 Schema 和实际 HTTP 行为核对。
