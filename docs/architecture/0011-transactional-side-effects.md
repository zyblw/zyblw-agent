# ADR 0011：真实写工具采用业务幂等记录 + Transactional Outbox/Inbox + 显式补偿

- 状态：Accepted
- 日期：2026-07-14

## 背景

工具执行账本可以判断 Agent 某次 tool call 是否已开始，却无法让业务数据库提交与第三方 HTTP/Kafka 确认组成一个
原子 transaction。若 Runtime 在业务写成功后、发送消息前崩溃，会丢事件；若远端确认后、本地记录成功前崩溃，恢复时
会重复发送。

框架数据库以 `V001__zyblw_agent.sql` 作为首次正式发布基线，直接描述当前完整结构。

## 决策

1. producer 使用 `(scope_key, operation_name, idempotency_key)` 唯一约束仲裁业务意图，并保存请求 hash 和可重放结果。
2. `PostgresBusinessMutation.mutate` 必须使用框架传入的 JDBC Connection；业务 mutation、幂等结果、outbox 和
   `Registered` 补偿计划共同 commit/rollback。
3. `OutboxStore` 不提供 insert，阻止业务在事务提交后再补写消息。
4. 独立 `OutboxPublisher` 使用短事务 `SKIP LOCKED` claim、有限 lease、heartbeat、token/generation fencing，在事务外
   调用 transport。
5. consumer 可用 `PostgresTransactionalInbox` 将 `(consumer_name, message_id)` 去重、业务 mutation 和结果置于同一
   transaction。
6. 补偿只保存确定性计划，必须显式 activate；handler 由应用注册、可幂等重试，模型无权动态生成。
7. 文档始终声明跨第三方网络是 at-least-once，不声称天然 exactly-once。

## 为什么符合 ZIO

- Publisher/Compensation worker 使用 Fiber 和 `raceFirst` 把业务调用与 heartbeat 组成结构化生命周期；失去 lease 会中断
  旧调用路径。
- JDBC 通过 `ZIO.attemptBlocking` 进入 blocking executor，并用 `Scope` 确定归还宿主连接池连接。
- Store、Transport、Registry 和 Handler 都是窄 SPI，可经 `ZLayer` 替换，并由 TestClock/Stub 做确定性测试。
- 类型化 `AgentError` 区分业务幂等冲突、消息冲突、租约丢失、永久错误和可重试存储错误。

## 后果

收益：业务状态不会与待发布事实分离；恢复拥有稳定 messageId；旧 worker 不能以陈旧 generation 提交；重复消费可在
下游数据库内收敛。

代价：业务 repository 必须支持接收同一 JDBC Connection；输出和 consumer 结果需要可持久化 codec；表需要生命周期
治理；第三方不支持幂等时仍需人工核对或补偿。

## 拒绝的方案

- **先提交业务，再调用通用 OutboxStore.insert**：存在不可关闭的丢消息窗口。
- **在数据库 transaction 内调用第三方网络**：长事务占用连接和锁，仍不能原子提交远端系统。
- **仅用 Run/toolCallId 做幂等键**：业务意图跨 Run 恢复时会重复，且不能表达资源版本。
- **收到重复消息就直接跳过而不保存结果**：上游无法得到确定的重放结果，并发首次处理也难以仲裁。
- **自动执行所有补偿**：原操作成功不代表需要反向操作，误补偿可能比原故障更危险。

## 发布门禁

每个真实业务写工具上线前必须证明：同键并发只提交一次、同键不同正文被拒绝、mutation 失败全事务回滚、远端确认后
崩溃会以同一 messageId 重发、下游重复投递不重复业务提交、补偿重复执行安全、DeadLetter 可查询和人工恢复。
