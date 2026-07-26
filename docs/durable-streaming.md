# 跨节点耐久事件流与 SSE

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 为什么不能只使用进程内 Hub

`RunObserver.hub` 很适合把当前 Worker 正在产生的 token、工具状态和 Guardrail 事件推给同进程订阅者，但它不是事实源：

- Worker 重启后，Hub 中内容消失；
- HTTP 请求可能被负载均衡到另一台主机；
- 慢客户端不应阻塞模型或工具 Fiber；
- 审批可能暂停数小时，不能让一个原始 HTTP Fiber 承担 Run 生命周期。

因此框架明确保留两条不同语义的流：

| 流 | 数据来源 | 适合内容 | 崩溃后恢复 |
|---|---|---|---|
| `RunEventStream` | 单进程滑动 Hub | token delta、瞬时进度、开发调试 | 否 |
| `DurableRunEventStream` | `AgentState/RunStore` | 状态转换、工具批次、审批、usage、完成/失败 | 是 |

不能把瞬时 Hub 称为耐久流，也不应把每个 token 都写进 PostgreSQL。需要跨节点逐 token 转发时，应增加有界的
Kafka/NATS/Redis Streams 等 relay Adapter，并继续以 PostgreSQL 事件序号作为恢复事实，而不是创建第二套 Run 状态。

## 2. 核心契约

`DurableRunEventStream.events(runId, afterSequence)` 使用 `ZStream.paginateChunkZIO` 分页读取事件：

1. 每页由 `RunStore.events(..., limit)` 在数据库侧限制条数；
2. 下游没有拉取时，不预取无界页面，形成自然背压；
3. 每一页都验证 runId 与 sequence 严格连续；缺口、乱序或串 Run 会 fail-closed；
4. 空页后若状态序号已经前进，会在该状态观察之后重读一次事件，消除两次查询之间发生提交的 TOCTOU 竞态；重读仍为空
   才判定状态/事件一致性被破坏；
5. 没有新事件且 Run 仍在执行时，用可中断的 `ZIO.sleep` 等待；
6. Run 完成、失败、取消、超预算，或进入待审批/暂停状态且游标追平后结束当前连接；
7. 客户端在审批后携带原 sequence 重新连接，即可继续读取新提交。

默认参数是 500ms 轮询、每页 256 条。较小连接池可以加大间隔；高事件量业务应先测量数据库 QPS，而不是盲目降低
轮询间隔。

```scala
val streamConfig = ZLayer.succeed(
  DurableRunEventStreamConfig(
    pollInterval = 1.second,
    batchSize = 128
  )
)

val durableStreamLayer = streamConfig >>> DurableRunEventStream.layer
```

也可直接使用 `DurableRunEventStream.default`。`AgentHttpApi.layer` 把 `DurableRunEventStream` 作为强制依赖，避免宿主无意中
退回只在本机有效的 Hub。

## 3. HTTP 协议

端点：

```text
GET /api/v1/runs/{runId}/events/stream
Accept: text/event-stream
Last-Event-ID: 17
```

每个 SSE 包含：

```text
id:18
event:ToolBatchCommitted
data:{"eventId":"...","runId":"...","sequence":18,"eventType":"ToolBatchCommitted","atEpochMilli":...}
```

- `id` 是数据库单调 sequence，不是随机 EventId；浏览器重连时可以直接回填 `Last-Event-ID`。
- `event` 是显式映射的 v1 公共事件名称，不依赖内部 Scala 类名。
- `data` 是脱敏、版本化的 `RunEventView` JSON；保留 EventId 供幂等消费，但不公开内部
  `PersistedAgentEvent`、工具参数/结果、消息历史或认证上下文。
- 15 秒 heartbeat 只用于维持代理连接，不推进 sequence，也不写数据库。
- 响应设置 `Cache-Control: no-cache, no-transform` 与 `X-Accel-Buffering: no`，减少代理缓存/缓冲造成的假实时。

首次读取不发送 `Last-Event-ID`；从头读取的内部游标是 `-1`。非法数字、小于 `-1` 或超过权威最后序号的游标会被明确
拒绝，避免客户端永远等待一个不存在的未来事件。

ZIO HTTP 官方提供 `Response.fromServerSentEvents`，框架使用该 API 生成正确的 `text/event-stream` 响应，而不是手工拼接
换行协议：<https://ziohttp.com/reference/response/>。事件源通过 ZStream 输出，遵循 ZIO Streams 的分块与背压语义：
<https://zio.dev/reference/stream/>。

## 4. 授权与错误边界

状态、一次性 JSON 事件和 SSE 都先执行 `RunAuthorization.read`：

- 默认要求 Run 中冻结的 tenantId/userId 与认证上下文匹配；
- `agent:runs:read:admin` 或已有的 `agent:commands:admin` 可以执行显式跨资源读取；
- 身份必须由 `AgentRequestContextResolver` 从已验签 JWT 或服务端 Session 解析，不能来自请求 JSON；
- 未授权请求在创建流之前返回 403，不会先泄露某个 Run 是否包含哪些事件。

流开始后若数据库暂时失败，HTTP 状态已经不能改写。框架会发送一个只含稳定 category 与安全消息的
`stream_error` 事件并结束；客户端稍后使用最后成功 sequence 重连。SQL、堆栈、Provider 正文和密钥不会进入该事件。

## 5. 测试证据与仍然诚实保留的边界

当前自动测试覆盖：

- batchSize 分页与连续游标；
- TestClock 驱动的“运行中无事件—稍后提交—继续发送”；
- 事件首次读取为空、随后状态已前进时确定性重读，避免正常并发提交被误判为缺口；
- sequence 缺口 fail-closed；
- 超前游标拒绝；
- `Last-Event-ID` 恢复与终态结束；
- 状态、JSON 事件和 SSE 的 tenant/user 统一授权。

仍需在真实负载均衡器和 PostgreSQL 集群上验证断连风暴、数万并发 EventSource、数据库切换、连接池耗尽与长时间 soak。
当前实现是跨节点一致的耐久轮询，不宣称已经具备外部消息总线的逐 token fan-out 吞吐。
