# 声明式 Workflow Graph

> 状态：Experimental
> 最后核验：2026-07-30
> 事实来源：`core.workflow` 源码、`WorkflowSpec`、PostgreSQL 16 集成测试与 `GraphWorkflowExample`

`zyblw-agent` 的 Workflow 是一个小型、类型化、可恢复的 StateGraph。它用于“步骤和控制边在运行前可以声明”的确定性长流程，
不是把普通 Agent loop 包装成图，也不是通用分布式调度平台。

适合使用图的场景：

- 任务存在真实的数据依赖、条件分支或并行汇合；
- 失败后需要从明确的节点边界恢复；
- 审计需要回答“执行了哪些节点、选择了哪条边”；
- 循环、并发和终止条件必须由 Runtime 强制约束。

只有一个模型循环、一个工具调用链或几个顺序函数时，继续使用 `AgentRuntime` 或普通 ZIO 组合；图不会自动提高质量。

## 核心约束

节点只返回新状态或暂停：

```scala
enum NodeOutcome[S]:
  case Succeeded(state: S)
  case Suspended(state: S, reason: String)
```

控制边单独声明：

```scala
val definition = WorkflowDefinition.make(
  id = WorkflowId("article-review"),
  version = WorkflowVersion(1),
  entry = prepare,
  nodes = Map(
    prepare -> prepareNode,
    research -> researchNode,
    review -> reviewNode,
    publish -> publishNode
  ),
  transitions = Map(
    prepare  -> WorkflowTransition.Next(research),
    research -> WorkflowTransition.Next(review),
    review   -> WorkflowTransition.Route(
      NonEmptyChunk(research, publish),
      state => Right(if state.accepted then publish else research)
    ),
    publish  -> WorkflowTransition.Complete()
  ),
  visitLimits = Map(research -> 3, review -> 3)
)
```

把边从节点实现中分离有三个直接收益：

1. 启动前可以检查全部可能路径；
2. 节点不能偷偷跳到未声明目标；
3. 图结构可以稳定投影给测试、Inspector 和未来的版本比较。

状态 `S` 由应用定义并保持不可变。模型驱动的 Agent 可以成为一个节点，但模型只能产出状态；选择下一条边仍由已声明的
`WorkflowTransition` 控制。需要模型分类时，先在 Agent 节点中把结果写入状态，再由纯、快速、无副作用的 `Route.select`
读取该结果。

## 启动前校验

`WorkflowDefinition.make` 返回 `Either[Chunk[WorkflowValidationIssue], WorkflowDefinition[...]]`。只有验证成功的定义才能交给
`WorkflowEngine`。当前会拒绝：

- 入口、源节点或目标节点不存在；
- 节点缺少 transition；
- 从入口不可达的节点；
- 同一 transition 中的重复目标；
- fan-out 分支不是当前内核支持的单步 `Complete` 节点；
- 访问上限非法或引用不存在节点；
- 循环中的任一节点没有正数访问上限。

这使循环成为显式、有预算的控制结构。`maxSteps` 仍提供整个 Run 的第二层硬上限。

## fan-out / fan-in

第一阶段只支持一个有意收窄的语义：

```scala
WorkflowTransition.FanOut(
  branches = NonEmptyChunk(marketResearch, productResearch),
  join = synthesize,
  policy = FanInPolicy.AllSucceeded
)
```

- 每个分支读取相同的不可变基准状态；
- 分支并发度受 `maxParallelism` 限制；
- `AllSucceeded` 下任一分支失败会失败整个 fan-out，并由 ZIO 结构化并发中断仍在运行的兄弟 Fiber；
- 全部分支成功后，`StateReducer` 按声明顺序合并结果，再进入 join；
- 失败时不会写入 join checkpoint，因而不会把部分结果伪装成已汇合。

当前分支必须是单步且不能暂停。多节点子图、quorum、race/first-success 和部分成功策略要在状态与恢复契约明确后再加入。

## checkpoint、执行台账与恢复

每个可继续的节点边界原子保存：

- `WorkflowId` 与正数 `WorkflowVersion`；
- `SessionId`，防止已知 runId 被另一 Session 误恢复；
- `WorkflowCursor`：下一节点或已完成；
- 应用状态；
- 全局 step；
- 每节点访问次数。

`resume` 会先验证 workflow、定义版本与 Session identity，再从最近 checkpoint 继续；已完成 Run 再恢复只返回同一个
`Completed`。当前事件包含节点开始/完成、fan-out 开始/完成、暂停和完成，可用于构造实际执行路径，但事件正文是否可持久化
仍由宿主决定。

内存 Store 与 PostgreSQL Store 使用相同的单调写契约：

- 完全相同的 checkpoint 可以幂等重放；
- 相同 identity 内只有更大的 step 可以推进；
- `step` 必须等于全部节点访问次数之和，`Completed` 是不可重新打开的终态；
- 陈旧 step、同 step 不同内容或 identity 漂移返回 `WorkflowCheckpointConflict`；
- PostgreSQL Adapter 对完整 JSON 执行容量限制、SHA-256、JSONB/确定性 TEXT 一致性和冗余列校验。

普通 `WorkflowEngine.make` 继续使用 checkpoint-only 模式，适合单进程、节点本身幂等或由外部调度器拥有执行权的场景。
生产多 Worker 推荐 `WorkflowEngine.makeDurable` 与 `WorkflowExecutionStore`：

```scala
val workflowLayer: URLayer[DataSource, WorkflowExecutionStore[ReviewState]] =
  PostgresAgentPersistence.workflowExecutions[ReviewState]

val engine = WorkflowEngine.makeDurable(
  definition,
  executionStore,
  reducer,
  WorkflowExecutionPolicy(
    owner = WorkerId("review-worker-7"),
    leaseDuration = 30.seconds,
    heartbeatInterval = 10.seconds
  )
)
```

Durable 模式对每次节点访问建立稳定 `(runId, step, nodeId)` 台账：

1. claim 生成随机 token，并递增 generation；活跃 lease 返回 `Busy`，不会并发执行同一节点；
2. 节点执行期间由作用域化 watchdog heartbeat；心跳失败会中断仍在运行的节点 Fiber；
3. 节点成功后先写 `Prepared` outcome，再把一个节点或整个 fan-out 的 execution 与下一 checkpoint 在同一事务提交；
4. 若进程在 prepare 后、commit 前崩溃，新 owner 在 lease 过期后领取更高 generation，并直接复用 Prepared outcome；
5. 旧 owner 的 heartbeat、prepare 或 commit 会被 owner/token/generation/expiry fencing 拒绝。

暂停 outcome 也先进入 ledger 并与暂停 checkpoint 原子提交；后续 `resume` 使用新的 step/visit 再次进入同一业务节点。应用状态
和 pending outcome 需要 `JsonCodec[S]` 才能使用 PostgreSQL Adapter。V009 同时保存确定性 TEXT、JSONB 和 SHA-256，
读取时对 identity、状态不变量、容量与 checksum fail-closed。

### 低敏 execution timeline

`WorkflowExecutionStore.timeline` 按 `(step, nodeId)` 稳定排序并使用排他复合游标分页：

```scala
val firstPage =
  executionStore.timeline(runId, limit = 100)

val nextPage =
  firstPage.flatMap { entries =>
    executionStore.timeline(runId, entries.lastOption.map(_.cursor), limit = 100)
  }
```

返回的 `WorkflowExecutionTimelineEntry` 只包含 node/step/visit、Running/Prepared/Committed、generation、owner 与时间戳，
并用 `outcomeAvailable` 表示是否已有耐久结果。它故意不包含：

- 应用状态或节点输入；
- Prepared outcome 正文；
- lease token；
- Prompt、工具参数或工具结果。

timeline 是 Inspector/CLI/运维诊断的只读投影，不能用于恢复或重放。Store 接口不掌握业务 tenant，因此 Adapter/HTTP 在调用前
必须使用可信身份验证 `runId` 的读取权限。`limit` 被限制在 1..500；内存和 PostgreSQL 实现共享同一分页、排序和低敏契约。

这关闭了“节点结果已经返回、checkpoint 尚未提交”造成的框架级重复调用窗口，但不宣称任意外部副作用 exactly-once。节点若
直接调用支付、发信或第三方写 API，仍需稳定业务幂等键；需要本地业务写与消息发布一致时使用
[Outbox/Inbox 与补偿](side-effects.md)。

## 最小运行示例

仓库中的 diamond graph 示例无需模型和数据库，使用内存 execution ledger 演示同一套 durable API：

```bash
sbt -batch "examples/runMain com.zyblw.agent.examples.GraphWorkflowExample"
```

完整源码见
[`GraphWorkflowExample.scala`](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/GraphWorkflowExample.scala)。

## 当前边界与下一步

当前已经实现“可验证图内核 + PostgreSQL checkpoint + 节点 execution ledger/pending outcome/fencing + 低敏 timeline”，
并用故障注入证明 prepare 后崩溃可恢复且节点不重复执行。尚未实现：

- timer、外部 signal、人工任务和 durable sleep；
- 多节点子图、checkpoint fork/time travel；
- quorum/race 等更多 fan-in policy；
- 完整 Graph Inspector UI/CLI 和图级质量/成本 eval。

下一纵向切片优先做“耐久 timer/signal 的原子注册、去重接收与唤醒命令”，并补数据库重启、进程 kill 与多 Worker soak；
不能让一个 JVM Fiber `sleep` 数天，也不能出现 signal 已接收但 checkpoint/恢复命令丢失。随后才根据真实业务证据选择人工
任务、子图或更多 fan-in policy。只有固定任务证明单 Agent 受角色或上下文隔离限制时，才在这个内核上增加 Agent handoff
或多 Agent 调度。
