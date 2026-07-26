# ADR-0008：参考成熟框架，但保持 ZIO 原生控制面

> 历史决策：运行时原则继续有效；文中的旧 artifact 名称已由
> [ADR-0014](0014-consolidate-public-modules.md) 收敛为 `zyblw-agent-core` 内部 package。

## 背景

LLM4S、Rig 和 LangGraph 已分别在 Scala、Rust 和 Python 生态形成较完整的 Agent 能力。`zyblw-agent` 需要持续吸收它们经过验证的设计，但不能通过复制 API 数量来证明完成度。

## 需要解决的问题

1. 如何同时提供“简单 Agent 易用入口”和“耐久工作流低层控制”。
2. 如何让 `AgentState`/`RunStore` 成为唯一运行事实来源。
3. 如何支持并行 worker、审批暂停和恢复，又不牺牲 ZIO 的 Scope、取消和背压语义。
4. 如何避免 Provider、工具、RAG 和工作流能力反向污染 core。

## 参考方案

- **LLM4S**：Scala 类型安全工具、多 Provider、不可变会话、guardrail、memory、handoff、事件和丰富示例。
- **Rig**：以小型 trait 和 builder 组合 completion、tool、extractor、RAG；mock/VCR 测试和统一开发体验尤其值得借鉴。
- **LangGraph**：把自身定位为低层 runtime，强调节点边界 checkpoint、thread、interrupt/resume、durable execution 和多种流式投影。

## 决定

采用三层结构，而不是一个万能 Agent 类：

```text
业务易用层：AgentDefinitionBuilder / AgentApplication / 异步示例
运行控制层：AgentRuntime + WorkflowEngine
基础设施层：Model / Tool / RunStore / Telemetry SPI 与独立 Adapter
```

本轮具体决定：

1. 工具发现保持默认拒绝，空白名单不再解释为“全部工具”。
2. `RunStore` 是唯一生产耐久事实来源，Runtime 直接提交版本化 `AgentState` 与精选领域事件。
3. Workflow 每个节点显式输出 `NodeStarted`，checkpoint 保存的是下一恢复游标。
4. fan-out 必须声明独立 join 节点，不能隐式把最后一个 worker 当作 join。
5. 暂停恢复会从节点入口重放，因此暂停前副作用必须幂等，危险动作应放在审批之后或使用幂等账本。
6. 多 Agent 与容器沙箱继续留在 experimental，不进入稳定 core；分布式 command 调度通过独立 scheduler 模块接入。
7. core 内 `app` package 只组合稳定 SPI；生产 `durable` 不提供内存 fallback，`inMemoryDefaults` 必须明确标记为教程/测试入口。
8. Builder 保持不可变，并在启动期校验 Agent 工具白名单是全局执行策略的子集；它不能成为隐藏依赖的 Service Locator。

## 未选择的方案

- 不直接依赖 LLM4S：它的能力可作为集成或参考，但不能替代 ZIO Fiber/Scope 贯穿的运行语义。
- 不复制完整 LangGraph 图 DSL：当前业务只需要显式节点、跳转、暂停、checkpoint 和有界 fan-out。
- 不把所有外围 SPI 宣称为生产实现：接口存在不等于已经完成故障注入、负载和真实基础设施验证。

## 风险

- Workflow 恢复要求节点作者理解重放与幂等规则。
- 过多公共 API 会增加长期二进制兼容成本，稳定 API 必须以业务接入和契约测试为准入条件。
- 真实业务 adapter、Provider 原生协议和跨主机混沌/soak 仍须通过发布门禁，基础 SPI 存在不代表这些外围能力成熟。

## 官方参考

- https://llm4s.org/guide/agents/
- https://llm4s.org/tool-calling-api-design.html
- https://rig.rs/docs/concepts
- https://docs.langchain.com/oss/python/langgraph/overview
- https://docs.langchain.com/oss/python/langgraph/persistence
- https://docs.langchain.com/oss/python/langgraph/interrupts
- https://zio.dev/reference/stream/
- https://zio.dev/reference/resource/scope/
