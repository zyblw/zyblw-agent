# 声明式 Workflow Graph

> 状态：Experimental
> 最后核验：2026-07-29
> 事实来源：`core.workflow` 源码、`WorkflowSpec` 与 `GraphWorkflowExample`

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

## checkpoint、恢复与事件

每个可继续的节点边界原子保存：

- `WorkflowId` 与正数 `WorkflowVersion`；
- `SessionId`，防止已知 runId 被另一 Session 误恢复；
- `WorkflowCursor`：下一节点或已完成；
- 应用状态；
- 全局 step；
- 每节点访问次数。

`resume` 会先验证 workflow、定义版本与 Session identity，再从最近 checkpoint 继续；已完成 Run 再恢复只返回同一个
`Completed`。暂停节点会从节点入口重新执行，因此节点在 `Suspended` 之前产生的外部副作用必须拥有业务幂等键。当前事件
包含节点开始/完成、fan-out 开始/完成、暂停和完成，可用于构造实际执行路径，但事件正文是否可持久化仍由宿主决定。

内存 Store 与 PostgreSQL Store 使用相同的单调写契约：

- 完全相同的 checkpoint 可以幂等重放；
- 相同 identity 内只有更大的 step 可以推进；
- `step` 必须等于全部节点访问次数之和，`Completed` 是不可重新打开的终态；
- 陈旧 step、同 step 不同内容或 identity 漂移返回 `WorkflowCheckpointConflict`；
- PostgreSQL Adapter 对完整 JSON 执行容量限制、SHA-256、JSONB/确定性 TEXT 一致性和冗余列校验。

应用状态需要 `JsonCodec[S]` 才能使用 PostgreSQL Adapter：

```scala
val checkpointLayer: URLayer[DataSource, WorkflowCheckpointStore[ReviewState]] =
  PostgresAgentPersistence.workflowCheckpoints[ReviewState]
```

## 最小运行示例

仓库中的 diamond graph 示例无需模型和数据库：

```bash
sbt -batch "examples/runMain com.zyblw.agent.examples.GraphWorkflowExample"
```

完整源码见
[`GraphWorkflowExample.scala`](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/GraphWorkflowExample.scala)。

## 当前边界与下一步

当前已经实现“可验证图内核 + PostgreSQL 节点边界 checkpoint”，但仍不是分布式耐久工作流承诺。尚未实现：

- 节点级 pending writes 和崩溃窗口恢复；
- timer、外部 signal、人工任务和 durable sleep；
- 多节点子图、checkpoint fork/time travel；
- 分布式 claim、lease 和 fencing；
- quorum/race 等更多 fan-in policy；
- Graph Inspector 和图级质量/成本 eval。

下一纵向切片是“节点 execution ledger + pending writes + 进程崩溃故障注入”，复用现有 PostgreSQL RunStore
的事务、租约和 fencing 经验。当前 Store 不提供执行所有权：多个 Worker 不能只凭 checkpoint 同时执行同一 Run。只有真实
任务证明单 Agent 受角色或上下文隔离限制时，才在这个内核上增加 Agent handoff 或多 Agent 调度。
