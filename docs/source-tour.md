# 源码阅读路线

> 状态：当前
> 最后核验：2026-08-01
> 事实来源：`build.sbt`、`modules/*/src/main`、`modules/*/src/test`

本页解决“从哪个文件开始、读到什么程度、如何证明自己理解了”的问题。概念解释仍以
[学习指南](learning-guide.md) 为准，模块稳定度以 [成熟度与路线](maturity-and-roadmap.md) 为准。阅读前也请了解
[代码注释与源码阅读约定](code-commenting-guide.md)：公共契约、安全/耐久不变量和非直观算法会详细说明；显然的
getter、局部变量和 Scala 语法不会机械逐行翻译。

## 阅读原则

- 先跑确定性示例，再读门面、领域语言和主循环；不要从 Provider 或数据库 Adapter 倒着猜框架。
- 每读一个生产文件，同时读对应测试。测试里的失败路径通常比接口注释更能说明真实契约。
- 第一次只追一条 `submit -> claim -> runtime -> inspect` 主线；Memory、RAG、MCP、Workflow 和写工具留到第二轮。
- 遇到 ZIO/ZIO HTTP 版本问题，从官方 `llms.txt` 找精确页面，并以根 `build.sbt` 锁定的版本编译验证。

## 第 0 阶段：建立可运行基线

先执行：

```bash
sbt "examples/runMain com.zyblw.agent.examples.QuickstartAgentExample"
sbt "testkit/testOnly com.zyblw.agent.app.AgentApplicationSpec"
```

然后阅读：

1. `modules/agent-examples/.../QuickstartAgentExample.scala`
2. `modules/agent-core/.../app/AgentQuickstart.scala`
3. `modules/agent-core/.../app/AgentApplication.scala`
4. `modules/agent-testkit/.../app/AgentApplicationSpec.scala`

完成标准：能用自己的话解释为什么示例没有 API Key 和数据库仍能走真实 Runtime，以及
`inMemoryDefaults` 为什么只能用于学习、测试和单进程开发。

## 第 1 阶段：掌握领域语言

按顺序阅读：

| 文件 | 重点问题 |
|---|---|
| `core/Ids.scala` | 外部字符串在哪里校验，为什么 opaque ID 的 `apply` 不能用于不可信输入？ |
| `core/Instructions.scala` | System/Developer 指令如何排序、版本化和生成稳定指纹？ |
| `core/Agent.scala` | `AgentDefinition` 冻结哪些能力，为什么不使用继承扩展循环？ |
| `core/Policy.scala` | Run、模型、token、工具、费用与时长预算如何表达？ |
| `core/Message.scala`、`core/Model.scala` | Provider-neutral 消息、工具调用与 usage 的边界是什么？ |
| `core/State.scala` | `AgentState` 中哪些字段必须跨崩溃恢复？ |
| `core/Events.scala` | 内部事件、耐久事件和公共事件为什么不能是同一个类型？ |
| `core/Error.scala` | 哪些失败可重试，哪些必须终止或等待人工？ |

配套测试从 `modules/agent-core/src/test/.../core` 开始，再读
`modules/agent-core/src/test/.../memory/RunStoreSpec.scala`。

完成标准：画出 `AgentDefinition`、`RunRequest`、`AgentState`、`AgentEvent`、`RunOutcome` 的关系，并标记每个对象的
创建者、事实来源和兼容性表面。

## 第 2 阶段：逐步跟踪唯一主循环

不要一开始通读 1400 多行的 `AgentRuntimeLive.scala`。按方法追踪：

1. `AgentRuntime.scala`：先理解公开入口和同步/耐久使用边界。
2. `AgentRuntimeLive.runWithId`：Run ID、初始状态与 `RunCreated` 如何原子建立。
3. `startCreated`：输入 Guardrail、总时限和 `Created -> Running`。
4. `loop`：预算、取消、Context、capability、模型调用、usage、工具计划或完成。
5. `createDurableToolPlan`：为什么未知或高风险工具会降级为串行。
6. `processToolPlan` / `executeDurableBatch`：审批、并行批次、账本和稳定结果顺序。
7. `saveEvents` / `save`：乐观锁、事件序号和 lease-aware fenced commit。
8. `recover` / `resume` / `cancel`：部分成功、人工决定与中断如何回到同一事实状态。

对应测试是
`modules/agent-testkit/src/test/scala/com/zyblw/agent/runtime/AgentRuntimeSpec.scala`。建议每次只运行一个测试标签：

```bash
sbt 'testkit/testOnly com.zyblw.agent.runtime.AgentRuntimeSpec -- -t "关键词"'
```

完成标准：选择一个“模型提出两个工具、其中一个需要审批”的场景，逐步写出每次模型调用、工具账本变化、
`AgentState.version` 变化和可观察事件。

## 第 3 阶段：理解耐久控制面

按调用方向阅读：

1. `runtime/AgentCommandService.scala`
2. `memory/RunSubmissionStore.scala`
3. `memory/RunCommandStore.scala`
4. `scheduler/CommandWorker.scala`
5. `scheduler/WorkerHost.scala`
6. `runtime/LeaseAwareAgentRuntime` 与 `AgentRuntimeLive.executeLeased`
7. `memory/RunStore.scala`

配套测试：

- `runtime/AgentCommandServiceSpec.scala`
- `scheduler/RunCommandStoreSpec.scala`
- `scheduler/WorkerHostSpec.scala`
- `agent-postgres/.../PostgresRunCommandStoreIntegrationSpec.scala`
- `agent-postgres/.../PostgresRunStoreIntegrationSpec.scala`

完成标准：解释 HTTP 返回 `202` 后为什么 Run 还可能是 `Created`，以及 lease、heartbeat、token、generation 和
`commitFenced` 如何共同阻止 zombie worker。

## 第 4 阶段：从 SPI 读到真实 Adapter

每次只选一条纵向能力：

| 能力 | 先读 SPI | 再读 Adapter/测试 |
|---|---|---|
| Provider | `model/ChatModel.scala` | `agent-providers/integrations/*` 与对应 HTTP contract spec |
| Context | `context/ContextManager.scala` | `DefaultContextManagerSpec`、压缩器与评测 |
| 工具 | `tools/TypedTool.scala`、`ToolExecutionPolicy.scala` | `ToolRegistrySpec`、`ToolBatchSchedulerSpec` |
| Guardrail | `guardrails/Guardrails.scala` | Runtime 输入/工具/输出失败场景 |
| PostgreSQL | `memory/*Store.scala` | `PostgresAgentPersistence.scala`、迁移与 integration spec |
| HTTP | `http/contract` | `AgentHttpApi.scala`、projection/contract/host specs |
| RAG | `agent-rag` 公共契约 | retriever/citation/eval、PostgreSQL knowledge index |
| Workflow | `workflow/Workflow.scala`、`WorkflowWait.scala`、`WorkflowExecution.scala`、`WorkflowWakeWorker.scala` | `WorkflowSpec`、PostgreSQL checkpoint integration spec |
| 观测 | `observability/*` | OTLP Adapter、脱敏与基数测试 |

完成标准：能指出 Adapter 只负责什么、绝不能负责什么，并能新增一个测试替身而不改 Runtime。

### RAG 纵向阅读顺序

需要理解 PDF 到引用回答时，不要从 pgvector SQL 单点倒推。按数据变换顺序阅读：

1. `rag/DocumentLoading.scala`：`DocumentInput`、Loader 注册与批量失败语义；
2. `rag/RetrievalLineage.scala`：block、page、bbox 与坐标系；
3. `loaders/DoclingDocumentLoader.scala`：Docling Markdown+JSON 到 Provider-neutral 结构投影；
4. `rag/DocumentStructureChunker.scala`：同父 block 合并、超限切分和 parent/neighbor lineage；
5. `rag/Rag.scala`：`RagApplication`、`KnowledgeIndexer`、Retriever 与 Citation；
6. `postgres/PostgresKnowledgeIndexStore.scala`：Building/stage/activate 原子发布；
7. `postgres/PostgresPgVectorStore.scala`：ACL-first vector/FTS/RRF 与谱系扩展；
8. 0.4 pgvector V001 和 `PostgresKnowledgeIndexIntegrationSpec`：物理约束与真实数据库证据。

完成标准：能解释原始 PDF、Markdown、chunk、向量和 citation 分别由谁拥有，以及为什么不同文档复用相同 chunk ID 不会
覆盖或跨文档扩展。

## 第二轮专题

掌握主线后，再按目标选择：

- 长会话：`instruction-context-cost.md`、`context-compression.md`、`memory-governance.md`
- 知识问答：`context-memory-rag.md`、`embedding-governance.md`、`rag-evaluation.md`
- 可靠写工具：`side-effects.md` 和 ADR 0011
- API 服务：`http-api-versioning.md`、`http-host.md`、`durable-streaming.md`
- 调试与质量：`run-inspection.md`、`testing.md`、`eval-trend-and-release-gate.md`
- 扩展协议：`mcp.md`、`sandbox.md`，并先核对成熟度矩阵

## 建议的学习产物

不要只做摘抄。每个阶段至少留下一个可验证产物：

1. 一张自己画的状态/时序图；
2. 一个现有测试的变体；
3. 一段对失败恢复语义的解释；
4. 一个只读工具和对应 policy/eval；
5. 一个使用 Maven Central 最新正式版或唯一 `0.4.0-local.*` 候选的独立最小消费者。

最后在 `zyblw-platform` 中用两条路径验证同一业务：

- 源码模式：`ZYBLW_AGENT_SOURCE_DIR=../zyblw-agent`
- 制品模式：精确 `ZYBLW_AGENT_VERSION`（正式版本或唯一 Maven-local 候选）

两者都通过，才能证明“理解并改好了框架”，而不是只让同一工作树偶然编译。
