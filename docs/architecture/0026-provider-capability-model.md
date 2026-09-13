# ADR 0026：Provider Capability 模型

> 状态：**Accepted 原则 / 分阶段实施**（Phase 1 扩展字段；矩阵持续演进）
> 日期：2026-09-05
> 影响：`ModelCapabilities`、`CapabilityValidator`、Catalog、Router 硬过滤
> 演进：[ADR-0004](0004-provider-abstraction.md)

## Context

`ModelCapabilities` 已用布尔字段描述 toolCalls、strictToolSchema、vision、streaming、promptCache、reasoningTokens 等，并派生 `ToolCallingCapability` / `StructuredOutputCapability`。`CapabilityValidator` 在发送前拒绝明显不匹配。

不足：

- 业务仍靠模型名字做 if/else 的诱惑还在（尤其是平台与示例）。
- 缺少「限制」而不只是「有/无」：并行工具、strict schema、reasoning modes、上下文上限已有部分，但 Router 选候选时没用上。
- 不能假设各厂 tool calling / JSON schema / reasoning 语义相同。
- 提示词中的 `enum ModelCapability` 与现有 case class 并存时，若再引入一套枚举会重复。

## Decision

1. **继续以 `ModelCapabilities` case class 为权威**，按需加字段；不平行引入第二套 capability ADT。需要当集合过滤时，用派生的 `Set` 视图（例如 `supports(ToolCalling)`），而不是让业务改写所有字面量。
2. Capability **必须能描述限制**：`maxContextTokens`、`maxOutputTokens`、`supportsStrictSchema`、`supportsParallelTools`、`supportsVision`、`reasoningModes`。已有字段对齐改名或增加别名，避免静默语义变化。
3. Router 硬过滤使用 Catalog 声明 + 运行前 `CapabilityValidator` 双闸。声明与 `ProviderContract` 探测不一致则 `CapabilityMatrix.requireConsistent` fail-closed（已有）。
4. **Vision 需求不得落入纯文本候选**——这是验收 Test B，写成 property test。
5. 厂商差异进 `ProviderQuirks`（Adapter 模块），不进 core 业务分支。
6. 结构化输出第一版仍以 Tool schema 为主；native JSON schema 作为可选 capability，不假装每家都有。

## Alternatives

- **A. 按模型名硬编码能力。** 否决：与 ADR-0004 和模型 ID 易变的现实冲突。
- **B. 另做 `enum ModelCapability` 并废弃 case class。** 否决：现网 descriptor、endpoints JSON、admin 探活都绑在 case class 上。
- **C. 宣称 OpenAI-compatible 即全能力相同。** 已由 DeepSeek/GLM quirks 否决。

## Consequences

- Phase 1 的代码增量应落在 `ModelCapabilities` + Catalog 配置 + 测试，而不是新 module。
- Qwen / DeepSeek / Gemini 的 descriptor 必须诚实；Vision / Reasoning / Tool 缺失就 skip 或不可被对应 Profile 选中。
- 业务代码审查新增规则：出现字符串模型名属于部署配置或测试夹具，不属于 Agent 定义（legacy 显式指定除外，且要记 decision code）。
- 能力表仍是配置，随模型版本更新；不把「当前最强 Qwen」写进框架发布说明当承诺。

当前实现边界与后置验收以 [目标架构](model-runtime-target.md) 的 Phase 1 节和 [预算状态表](model-budget-and-cost.md) 为准。
