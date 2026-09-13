# Provider 契约与 Adapter

> 状态：Proposed（Phase 0；测试契约已存在，运行时 SPI 不替换）
>
> 最后核验：2026-09-05
>
> 决策来源：[ADR-0004](0004-provider-abstraction.md)、[ADR-0021](0021-provider-neutral-model-protocol.md)、[ADR-0026](0026-provider-capability-model.md)
>
> 现行说明：[providers.md](../providers.md)、[ProviderContract 2.0](../provider-contract-2.md)

---

## 运行时 SPI 保持 ChatModel

core 继续只认识：

```text
ChatModel
  provider / providerId
  descriptor / capabilities(model)
  complete(ChatRequest): IO[AgentError, ChatResponse]
  stream(ChatRequest): ZStream[..., ModelStreamEvent]
```

禁止：

- 把 OpenAI / Gemini / DashScope SDK 类型放进 core
- 用一个巨大的 `OpenAICompatibleProvider` 假装国产 API 行为一致
- 让 Agent Runtime 解析「OpenAI function call」或「Gemini function call」

OpenAI-compatible 只表示 **HTTP 形状接近**。tool calling、strict schema、reasoning、usage、cache、error envelope 仍按 `ProviderQuirks` + `ModelCapabilities` 声明。

OpenAI-compatible 厂商可以共享经过完整 HTTP 契约验证的 transport/wire engine，但每家必须有独立的兼容档案、配置入口和能力声明。
Gemini、Anthropic、OpenAI Responses 等不同协议继续使用独立 Adapter；不能靠模型名分支猜测厂商行为。

---

## ProviderContract 仍是测试契约

`ProviderContract.verify` / `verifySuite` 继续留在 `agent-testkit`。它证明 Adapter 遵守 SPI，不代替 `ChatModel`。

现有覆盖：complete + stream、usage、tool 回填、429/5xx 分类、取消传播。

后续只 **追加**，不重写：

| 用例 | 不支持时 |
|---|---|
| simple completion | 不可 skip |
| streaming | capability 无 streaming 则 skip |
| tool call | 无 toolCalls 则 skip |
| structured output / strict schema | 按 capability skip |
| usage | 无 usageReporting 则 skip，不得伪造 token |
| cancellation / timeout | 不可 skip |
| error normalization | 不可 skip |
| malformed tool-call | 必须变成 `InvalidModelResponse` / `ToolProtocolError`，不得执行工具 |
| partial stream（无 Completed） | 必须 typed fail |
| context overflow | 映成明确类别，默认不可盲目 fallback 到同样窗口的模型 |

Capability 不支持时 **明确 skip**。伪造支持是契约失败。

---

## 统一请求 / 响应（演进，不换名）

继续使用 `ChatRequest` / `ChatResponse` / `AgentMessage` / `ContentPart`。

请求侧已有或应补齐的语义（字段可以长在 `ModelSettings` / metadata / 未来显式 ADT，但不要另起 `ModelRequest`）：

```text
messages, system / developer instructions
tools, toolChoice
response format / json schema（今天主路径是 Tool schema）
max output tokens, temperature, stop
reasoning policy（要不要思考，不要存 hidden CoT）
context / trace metadata, request id
dataSensitivity, tenant（给 Router，不给 Adapter 做主）
```

内容 ADT 已有 Text / ImageUrl / ImageArtifact / Tool result。Document reference 与 Structured data 按需扩展，仍走 provider-neutral 映射。

响应必须能表达：

```text
text
structured / tool calls（归一化 ToolCall）
finishReason
usage（input / output / cached / reasoning）
latency（由 Runtime 计，不信厂商单字段）
providerRequestId
safety / cache 低敏信息
```

`ReasoningDelta` 可以在流里出现，Runtime **不依赖、不默认持久化** 私有思维链。需要解释时让模型输出独立的 `decision_summary`。

---

## 错误：retryable / fallbackable / fatal

今天 `AgentError` 已有 `ErrorCategory` 与 `retryable`。目标补 **fallbackable**，并固定常见映射：

| 类别 | retry 同模型 | fallback 换模型 | 说明 |
|---|---|---|---|
| Timeout | 是 | 可选 | 先有界 retry |
| RateLimited | 尊重 Retry-After | 额度耗尽则是 | |
| ProviderUnavailable / 5xx | 有界 | 是 | |
| Authentication / Authorization | 否 | 否 | 配置错误 |
| InvalidRequest / Validation | 否 | 否 | 换十个模型会重复失败 |
| UnsupportedModelCapability | 否 | 否 | 路由 bug |
| ContextOverflow | 否（除非先压缩） | 仅当候选窗口明显更大 | |
| SchemaViolation | 有界 repair | 否（那是 Escalation） | |
| SafetyBlocked | 否 | 否 | |
| ToolProtocolError | 否 | 否 | 不得执行工具 |
| Cancelled | 否 | 否 | 传播中断 |

`FallbackChatModel` 已把能力/配置/安全标为不可降级。新 Router 继承这条，不要放松。

---

## 第一阶段 Adapter 范围

优先三家代表性协议，而不是「支持六家」：

| Provider | 现况 | Phase 4 |
|---|---|---|
| DeepSeek | OpenAI-compatible preset，已有 | 保持；作 Reasoning/Coding 候选 |
| Gemini | 原生 Interactions Adapter | 保持；作 Vision / 国际备源 |
| Qwen | 已有一级 Chat Completions preset、显式区域配置和 smoke 入口 | 以逐模型能力清单与真实 Eval 决定默认候选 |
| GLM | 已有 preset | 不阻塞核心；Phase 8 按 Eval 纳入默认候选 |
| OpenAI | Chat + Responses 已有 | 同上 |
| Kimi | 无 | 同上 |
| Anthropic | 已有 | 保持可用，不作为第一阶段路由叙事中心 |

新 Provider 准入：不同协议实现 Adapter；兼容协议至少实现命名档案；两者都要有 Catalog 条目、配置、`verifySuite`/wire
契约和真实 smoke。**不改** Agent Runtime 与业务 Agent。

---

## Conversation 可移植性

canonical 上下文是 `AgentState.messages` + `PreparedContext`，不是厂商 thread id。

Qwen 失败落到 Gemini 时，Runtime 必须用同一份 canonical messages 重建 `ChatRequest`。Provider-native continuation（Responses items、Gemini steps signature）只是性能优化，缺了也能正确跑，只是可能更贵或丢掉该厂商的 thinking 回放。

`WorldStateDelivery.TrustedStatefulDelta` 已要求宿主证明 continuation 可信；跨 Provider fallback **必须**改回 FullSnapshot。

---

## Secrets

API Key 不得进入 ModelCatalog、数据库、log、event、trace。Adapter 只拿 `SecretRef` / `Config.Secret`。`toString` 与错误 envelope 已禁止回放响应正文；新 Adapter 必须遵守同一红线。

## Phase 1 错误资格

`AgentError.fallbackable` 已与 `retryable` 分开：只有可重试的 ModelError 且类别为 Timeout / RateLimit / Unavailable
才具备故障切换资格。409 可重试但不自动换 Provider；鉴权、能力、协议、安全与工具错误不能通过该属性触发模型切换。
这个属性不是 dispatch 授权。现有 FallbackChatModel 保持 legacy 行为且不能嵌套在新路由中；新主调用暂不重试。
可见流增量之后失败不拼接第二次回答；未来支持重试必须先定义 attempt 标识、撤回/替换与客户端兼容合同。
