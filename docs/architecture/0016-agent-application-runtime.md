# ADR 0016：Agent Application Runtime 与 Agent / Harness / Workflow 三层边界

> 状态：Accepted
> 日期：2026-07-29
> 影响：公共架构定位、后续 API 设计、路线图与成熟度口径

## 背景

只提供模型调用、Prompt、Tool、RAG 或 ReAct loop，无法独立解决真实智能体应用中的恢复、权限、长任务状态、质量门禁、
运行治理和业务接入问题。前沿框架也正在把这些问题从“Agent 本身”拆开：

- Microsoft Agent Framework 明确区分 Agent、Harness 与 Workflow；
- LangGraph 将完整 checkpoint 与节点 pending writes 分开，避免同一 super-step 中已成功节点在恢复时重复执行；
- OpenAI Agents SDK 的 Sandbox Agent 把 Workspace、Snapshot、Skills、Memory 与 Compaction 作为运行能力；
- Pydantic AI 同时提供 Harness 与多种 durable execution 集成；
- Anthropic 将 Context Engineering、结果评测和多次试验可靠性作为长任务 Agent 的核心工程问题。

仓库已经具有单 Agent Runtime、审批、耐久命令 worker、Context、Memory、RAG、Artifact、Workflow Graph、Inspector、
Telemetry 与 Eval，但如果没有统一的架构语义，新增能力仍可能退化为互不相干的 package。

## 决策

`zyblw-agent` 定位为：

> 面向 Scala 3 / ZIO 2 的、Provider-neutral、可恢复、可治理、可评测的 Agent Application Runtime。

框架采用三个可组合而不互相替代的应用层概念：

| 概念 | 负责 | 不负责 |
|---|---|---|
| **Agent** | 模型循环、结构化输出、工具选择、Context 消费 | 随意绕过权限、持久化和预算；表达确定业务流程 |
| **Harness** | Goal、Plan/Todo、Workspace、Artifact、按需 Skill、Context/Memory、Sandbox、审批与任务级观测 | 把计划文字当作可靠状态；成为第二套 Runtime |
| **Workflow** | 显式节点/边、确定性路由、checkpoint、并发汇合、signal/timer、人工任务与恢复 | 替模型进行开放式推理；强制图化普通函数或简单单 Agent |

业务可只使用 Agent；长任务在 Agent 外组合 Harness；步骤和恢复边界明确时再由 Workflow 编排 Agent、函数或人工节点。
三者都是 `agent-core` 内的 package/API 概念，不能因为名字不同就立即拆成新的 Maven artifact。

## 统一运行语义

所有入口最终必须复用同一组控制不变量：

1. 模型只提议文本、结构化结果或工具调用；
2. Runtime 校验 capability、schema、权限、风险、预算和当前状态；
3. 外部副作用使用稳定幂等身份、执行账本和 fenced commit；
4. 中断、审批、signal 与恢复使用耐久状态，而不是 JVM 回调或 Prompt 约定；
5. Inspector、Trace 和 Eval 读取权威状态/事件的低敏投影，不成为第二个事实源；
6. ZIO Scope 管资源生命周期，Fiber 承担有界并发和可取消执行，ZLayer 负责显式装配可选 Adapter。

这里的“统一”不表示把 Agent Run、Workflow Run 和业务 Session 合并成一个 ID。它表示它们共享租户、权限、成本、
审计、错误和观测语义，同时保持各自生命周期：

- Session：用户/业务连续交互范围；
- Agent Run：一次 Agent 状态机执行；
- Workflow Run：一次图执行及其节点账本；
- Artifact/Workspace：任务材料与产物；
- Memory：可治理的跨 Run 信息；
- Context：某一次模型采样实际看到的有界投影。

## 八个能力平面

八平面是能力审计视角，不是八个 artifact：

| 平面 | 当前落点 | 下一项可验证工作 |
|---|---|---|
| Application SDK | `app`、Builder、Quickstart、HTTP host | PostgreSQL 最小应用与更少样板的生产 preset |
| Intelligence | Provider、Instruction、Context、Tool、Memory、RAG | capability/native extension、真实长会话与 RAG lineage |
| Execution | Agent Runtime、command worker、Workflow execution ledger、低敏 timeline、durable wait/signal | timer worker→wake command、kill/recover 与多节点 soak |
| Control | Permission、Guardrail、Approval、Limit、Cost | policy 管理 UX、任务级总预算与保留策略 |
| State | Run/Event/Command、checkpoint、Memory、Artifact | Goal/Plan/Todo SPI、Artifact durable Adapter、schema upcaster |
| Quality / Ops | Inspector、OTLP、Eval、趋势门禁 | outcome/trajectory 分离、`pass@k`/`pass^k` 趋势、图级 eval |
| Interop | MCP client | OAuth/Roots/identity/隔离；A2A 仅在单 Agent 基线后 |
| Deployment / Governance | PostgreSQL、Flyway、ZIO HTTP host、安全文档 | 多节点 soak、备份恢复、保留/删除审计、SLO |

## Provider 与协议边界

Provider-neutral core 只表达所有 Provider 都能可靠承诺的语义。差异通过显式 capability 协商和受控 native extension
表达；不能为了“统一”静默丢失服务端工具、缓存、推理预算、文件或批处理等能力。

MCP 是 Agent 与工具/资源/Prompt 的上下文交换边界，不负责 Agent 内部运行语义。A2A 是不透明 Agent 应用之间的任务、
消息、Artifact 和能力发现协议，不替代 MCP、内部函数调用或 Workflow。两者都不能直接取得业务权限，必须经过现有
认证、授权、预算、审计和输入不可信边界。

## 实施顺序

### 当前收口

- 0.3 开发线采用 fresh database baseline，并收口 durable Workflow wait/signal；不承诺兼容 0.2 的 API、state JSON 或 schema；
- Eval 增加有界多试验运行及 `pass@k` / `pass^k` 可靠性信号；
- 文档明确 Experimental/Beta，不把上述能力宣传为已经完成生产验证。

### 下一阶段

1. Workflow G3-A2b：把已有 durable wait/signal 与 `expireDue` 接入受监督 timer worker 和耐久 wake command，补数据库重启、
   进程 kill 与多 Worker soak；
2. RAG R2-B：block/page/bbox lineage、parent-child retrieval、相邻块扩展和 ACL 前置；
3. Harness H1：小型 Goal/Plan/Todo/Skill ADT 与 Store SPI，复用 Artifact/Workspace/Approval；
4. Quality Q1：结果与轨迹评分分离，多试验趋势和 failure corpus；
5. DX D1：PostgreSQL 最小接入、Inspector CLI 和生产装配 preset。

### 延后

- A2A server、通用多 Agent 调度和 Agent marketplace；
- 大型 Graph Studio 或把所有任务图化；
- 未经真实语料证明收益的复杂 GraphRAG；
- 新的 Harness/Workflow Maven artifact。

这些能力只有在固定 eval、权限模型、故障恢复与运维可解释性证明收益后才进入实施。

## 结果

优点：

- 业务可以从单 Agent 渐进采用 Harness 和 Workflow，不需要一次接受整套平台；
- 新能力有明确归属，减少重复状态机和“Prompt 即状态”；
- ZIO 的并发、资源与依赖注入优势落在可靠执行上，而不只是语法包装；
- 路线优先关闭恢复、质量和易用性缺口，不以功能数量制造成熟度幻觉。

代价：

- Harness 需要新增持久化协议和治理规则，不能只做几个 case class；
- Agent/Workflow/Session 身份映射会增加文档和投影工作；
- Provider native extension 与 capability matrix 需要持续 contract test；
- A2A、多 Agent 和可视化编排必须接受更晚的交付顺序。

## 参考

- [Microsoft Agent Framework overview](https://learn.microsoft.com/en-us/agent-framework/overview/)
- [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [OpenAI Agents SDK sandbox agents](https://openai.github.io/openai-agents-python/sandbox/guide/)
- [Pydantic AI durable execution](https://pydantic.dev/docs/ai/capabilities/durable_execution/overview/)
- [Anthropic：Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Anthropic：Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- [Model Context Protocol architecture](https://modelcontextprotocol.io/docs/learn/architecture)
- [Agent2Agent Protocol](https://a2a-protocol.org/latest/)
- [Temporal durable timers](https://docs.temporal.io/develop/java/workflows/timers)
- [Temporal workflow message passing](https://docs.temporal.io/develop/java/workflows/message-passing)
- [ZIO effect and parallelism](https://zio.dev/reference/core/zio/)
