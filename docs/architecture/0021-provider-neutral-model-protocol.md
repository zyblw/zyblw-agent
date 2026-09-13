# ADR 0021：Provider-neutral Model Protocol（演进 ADR-0004）

> 状态：**Accepted 原则 / 分阶段实施**（Phase 0 文档；编码属 Phase 1+）
> 日期：2026-09-05
> 影响：`agent-core/model`、`agent-providers`、ModelCall 账本字段
> 不推翻：[ADR-0004](0004-provider-abstraction.md)、[ADR-0018](0018-next-generation-runtime-kernel.md)

## Context

ADR-0004 已经决定：厂商无关请求 + 每模型 `ModelCapabilities` + Adapter 显式失败，而不是把 OpenAI 类型当内部模型。现行 SPI 是 `ChatModel` / `ChatRequest` / `ChatResponse` / `ModelStreamEvent`。测试契约是 `ProviderContract`。

压力来自「多厂商生产调度」：业务仍通过 `ModelSettings.provider/model` 点名；提示词示例鼓励再造一套 `ModelProvider.complete(model, request)`。若照抄示例，会与 `CanonicalModelRequest`、现网 Adapter 和平台装配同时断裂。

## Decision

1. **运行时协议保持并演进 `ChatModel`**，不替换为提示词中的新 trait 名。`ModelProvider` 继续作为 `ChatModel` 的命名子类型。
2. **请求/响应继续叫 `ChatRequest` / `ChatResponse`**。需要的字段（profile、sensitivity、request id）加在 `ModelSettings` 或执行上下文上。
3. **`ProviderContract` 只做测试契约**。新 Adapter 必须通过 `verifySuite`；capability 不支持则 skip，不得伪造。
4. **每个厂商独立 Adapter** + 共享 HTTP/codec；禁止单一 `OpenAICompatibleProvider` 覆盖所有国产行为。
5. **私有思维链不是协议的一部分。** `ReasoningDelta` 与厂商 metadata 不得成为跨 Provider 正确性依赖，不得默认写入耐久 Event。

## Alternatives

- **A. 新建平行 `ModelProvider` SPI 并弃用 `ChatModel`。** 否决：迁移面覆盖所有 Adapter、testkit、Runtime、compressor、平台 QA，收益只是换名。
- **B. 以 OpenAI wire 类型作为 canonical。** 已由 ADR-0004 否决；今日 Gemini / Anthropic 证明会泄漏。
- **C. 把 ProviderContract 提升为运行时。** 否决：它依赖测试探针与失败注入，不是生产调用面。

## Consequences

- Phase 1 可以补 `fallbackable`、更细错误类别、Capability 限制字段，而不改调用方类型名。
- 旧 `complete`/`stream` 调用继续工作；ModelRuntime 是编排层，不是新协议。
- Qwen 以独立 preset/quirks 进入，而不是「再开一个 relay 就算一等公民」。
- 文档与代码审阅以本 ADR + ADR-0004 为准；示例接口名不是合同。

当前实现边界与后置验收以 [目标架构](model-runtime-target.md) 的 Phase 1 节和 [预算状态表](model-budget-and-cost.md) 为准。
