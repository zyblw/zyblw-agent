# 可靠写工具、Outbox、Inbox 与补偿指南

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 框架保证什么

本模块把一次真实写工具拆成两个事务边界：

1. **本地 PostgreSQL 提交**：业务 mutation、producer 业务幂等记录、outbox 事件和可选补偿计划在同一个 JDBC
   transaction 内提交或共同回滚。
2. **事务外投递**：`OutboxPublisher` 在短 claim 事务结束后调用 Kafka、NATS、SQS 或受控 webhook transport。

第一段对同一 `scope + operationName + idempotencyKey` 提供本地确定性复用；第二段只能保证 at-least-once。
远端已经确认、进程却在 `markPublished` 前崩溃时，同一 `eventId` 会再次发送。这是跨系统通信的真实边界，不应被文档
包装成 exactly-once。

如果下游也使用 PostgreSQL，`PostgresTransactionalInbox` 可把 `(consumerName, messageId)`、下游业务 mutation 和可重放
结果放入同一个 transaction。这样重复投递不会重复提交下游数据库状态，但仍不等于整个互联网调用链具备分布式
exactly-once。

## 2. 为什么 OutboxStore 没有 insert

`OutboxStore` 只暴露 claim、heartbeat 和状态推进，没有公开 `insert`。这是有意的能力约束：如果业务先提交文章状态，
再调用一个通用 `insert` 写事件，两个动作之间仍存在进程崩溃窗口。事件只能由 `PostgresTransactionalWriteExecutor` 使用与
业务 repository 相同的 `DataSource` 和同一条 `Connection` 创建。

`run_id` 在业务幂等、outbox 和补偿表中是审计关联而非外键。原因是可靠投递事实不应随 Agent Run 删除而级联消失。
生产环境要分别定义 Run、业务操作和消息事实的保留/归档策略。

## 3. 实现可靠写工具

业务项目实现 `PostgresBusinessMutation[I, O]`。下面只展示协议骨架；业务 SQL 必须继续校验 tenant、资源归属和数据库
唯一约束。

```scala
final case class PublishInput(articleId: String, expectedVersion: Long) derives JsonCodec
final case class PublishOutput(articleId: String, published: Boolean) derives JsonCodec

val publishArticle = new PostgresBusinessMutation[PublishInput, PublishOutput]:
  def operationName: String = "publish-article-v1"

  def idempotencyKey(
      input: PublishInput,
      context: ToolExecutionContext
  ): Either[AgentError, BusinessIdempotencyKey] =
    BusinessIdempotencyKey.fromString(s"${input.articleId}:${input.expectedVersion}")
      .left.map(AgentError.InvalidConfiguration.apply)

  def mutate(
      connection: java.sql.Connection,
      input: PublishInput,
      context: ToolExecutionContext
  ): Either[AgentError, PublishOutput] =
    // 使用这条 connection 更新业务表；不得在这里调用 HTTP、模型或消息总线。
    // WHERE 必须包含 tenant/owner/version 条件，并检查 executeUpdate 返回值。
    Right(PublishOutput(input.articleId, published = true))

  override def outbox(
      output: PublishOutput,
      context: ToolExecutionContext
  ): Either[AgentError, Chunk[OutboxEventDraft]] =
    Right(Chunk(OutboxEventDraft(
      destination = "content-events",
      eventType = "article.published.v1",
      aggregateType = "article",
      aggregateId = output.articleId,
      partitionKey = output.articleId,
      payload = zio.json.ast.Json.Obj("articleId" -> zio.json.ast.Json.Str(output.articleId))
    )))
```

随后只能通过 `PostgresReliableWriteTool.make` 创建工具。该工厂会验证工具名与 `operationName` 相同，并强制设置
`SideEffect.TransactionalOutboxWrite`；不能把普通 `Tool.json` 的枚举手工改名来冒充可靠事务写。

`I` 的 JSON 编码用于计算请求 SHA-256。同一幂等键若收到不同输入会返回 `BusinessIdempotencyConflict`，不会以后写覆盖
前写。`O` 会持久化用于重放，所以它必须体积受限、可稳定解码、对模型安全，不应包含密钥或多余个人信息。

## 4. 设计业务幂等键

一个好键描述“业务上只允许发生一次的意图”，而不是某次 HTTP 请求：

- 草稿发布可用 `articleId + expectedVersion`；
- 订单扣款可用服务端生成的 payment intent ID；
- 不要只用 `runId`，因为同一业务意图可能跨 Run 恢复或重试；
- 不要接受模型自由生成的随机键；
- 数据库业务表仍需唯一约束或版本 CAS，幂等表不是业务完整性规则的替代品。

默认作用域优先使用 `tenantId`，其次使用 `userId`；没有两者的全局写默认拒绝。只有明确的系统级后台任务才应审查后
启用 `allowGlobalScope`。

## 5. 启动 Outbox 发布者

业务实现窄 `OutboxTransport`，并始终把 `event.eventId` 作为下游 messageId：

```scala
val owner = SideEffectWorkerId.fromString("agent-worker-01")
  .fold(message => throw IllegalArgumentException(message), identity)

val publisher = OutboxPublisher(
  store = new PostgresOutboxStore(dataSource),
  transport = kafkaTransport,
  owner = owner,
  config = OutboxPublisherConfig()
)

ZIO.scoped(publisher.run.forkScoped *> ZIO.never)
```

网络发送不持有 JDBC connection。`claim` 使用 `FOR UPDATE SKIP LOCKED`，每次重新领取递增 generation 并生成新 token；
heartbeat、Published、重新排队和 DeadLetter 都必须匹配完整 lease。失去租约的旧 Fiber 会停止陈旧状态提交。

`destination` 是宿主白名单中的逻辑 route，不应直接接受模型传入任意 URL。`last_failure` 只保存脱敏错误类别，完整堆栈、
响应正文和凭据应进入有访问控制的 trace 系统。

## 6. 下游 Inbox

下游 handler 必须使用框架传入的同一条 `Connection` 修改自己的业务表：

```scala
val consumer = InboxConsumerName.fromString("search-index-v1")
  .fold(text => throw IllegalArgumentException(text), identity)

inbox.consume[ConsumerResult](consumer, message) {
  (connection, received) =>
    // 用 connection 修改本地投影，并返回可重放的小结果。
    Right(ConsumerResult(received.messageId.asString))
}
```

先查询 inbox、提交查询事务、再另开业务 transaction 是错误用法。相同 `(consumerName, messageId)` 但消息正文不同会触发
`InboxMessageConflict`，表明上游违反了“稳定 ID 对应稳定事实”的契约。

## 7. 补偿 SPI

补偿不是数据库 rollback，也不是模型再次自由推理。原 mutation 只会同事务写入 `Registered` 计划；成功并不自动触发
补偿。业务失败策略或人工运维必须显式调用 `activate`，原目标已经达成则调用 `cancel`。

`CompensationHandler` 应满足：

- 名称稳定且在 `CompensationRegistry` 显式注册；
- payload 是最小快照或稳定资源引用；
- 窄权限、可审计、可幂等重试；
- 先读取当前业务事实，已达到补偿后状态时直接成功；
- 不让模型动态选择 SQL、URL 或 handler 名称。

补偿 worker 与 outbox 一样使用 lease、heartbeat、generation fencing 和 DeadLetter。补偿本身仍可能访问第三方系统，故其
执行语义也保持 at-least-once；第三方 API 必须支持业务幂等键，或由人工核对不确定结果。

## 8. 运维与测试清单

- 监控 Pending 最老年龄、DeadLetter 数量、claim/发布延迟、尝试次数和租约丢失率；
- 对业务操作、outbox、inbox 和补偿分别设置归档策略，避免无界增长；
- 验证远端确认后 SIGKILL、慢网络、断流、数据库连接池耗尽和 lease 到期抢占；
- 验证同一幂等键并发提交、同键不同正文冲突、业务 SQL 后失败整体回滚；
- 发布 event schema 时采用版本化事件类型，并在演进前做 consumer 契约测试；
- 自动补偿上线前必须用业务故障样例验证“重复执行不会扩大损害”。

当前 PostgreSQL 集成测试覆盖同事务提交/回滚、发布确认崩溃窗口、旧 generation 拒绝、Inbox 去重，以及补偿显式激活。
跨主机 SIGKILL、网络分区、数据库切换和长时间 soak 仍属于后续发布门禁。
