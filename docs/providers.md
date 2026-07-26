# Provider 与能力矩阵

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 统一协议

`ChatModel` 提供 `complete`、`stream` 和 `capabilities`。流式结束必须产生 `ModelStreamEvent.Completed`；否则 runtime 以类型化错误失败。

| 能力 | OpenAI Responses | Anthropic Messages | OpenAI-compatible | DeepSeek 档案 | GLM 档案 |
|---|---:|---:|---:|---:|---:|
| Tool Calling | 是 | 是 | 是 | 是 | 是 |
| Strict Tool Schema | 是 | 否 | 是 | 否 | 否 |
| 指定单个 Tool Choice | 是 | 是 | 是 | 否 | 仅 auto |
| Developer role | 原生 | 合并到顶层 system | 原生 | 映射 system | 映射 system |
| 推理状态回放 | 原始 output items | content blocks/signature | `reasoning_content` | `reasoning_content` | 依模型能力 |
| SSE streaming | typed event | typed event | choices/delta | choices/delta | choices/delta |

能力表是配置基线，具体模型仍可通过 `ProviderDescriptor.models` 覆盖。

## 配置

```scala
val configs = for
  deepSeek <- ProviderPresets.deepSeekFromEnvironment
  glm      <- ProviderPresets.glmFromEnvironment
  openAI   <- ProviderPresets.openAIFromEnvironment
yield List(deepSeek, glm, openAI)

val layer = configs.map(MultiProviderChatModel.layer("deepseek", _))
```

这些 `fromEnvironment` 方法实际使用当前 ZIO `ConfigProvider`，不是直接读取全局 `sys.env`。默认 Provider 会读取环境变量/
系统属性；测试可用 `ConfigProvider.fromMap`，生产也可由宿主换成其他配置后端。API Key 使用 `Config.Secret` 加载，配置错误
和配置对象 `toString` 均不输出真实值。

OpenAI 新项目可以改用原生 Responses Adapter：

```scala
val layer: ZLayer[Client, AgentError, ChatModel] = ZLayer.unwrap(
  OpenAIResponsesConfig.fromEnvironment.map(OpenAIResponsesChatModel.configured)
)
```

Responses 的 Provider ID 是 `openai-responses`。`store=false` 是框架默认值：对话、审批和恢复状态由
`AgentState/RunStore` 持久化，避免 Provider 托管会话与 PostgreSQL 出现两个事实源。模型返回的 reasoning item 会以
命名空间 metadata 随 assistant message 保存，并在工具结果回填时原样重放；业务日志不应输出这段 metadata。

模型 ID 变化频繁，应放在环境变量/配置中，不由业务代码硬编码。所有新 Adapter 必须运行
`ProviderContract.verifySuite`，并覆盖工具回填、任意 SSE 分块、慢流、断流、usage、429/5xx、取消传播和 Redacted
cassette。详见 [ProviderContract 2.0](provider-contract-2.md)。

真实凭据、模型 ID、TLS/DNS/代理和厂商在线协议还需要小流量门禁。框架提供 `LiveProviderSmokeRunner` 以及 DeepSeek、
GLM、OpenAI Chat/Responses、Anthropic、Gemini 的统一 CLI，并提供 MemoryExtractor 真实工具调用 smoke。完整命令和 CI
分层见 [真实 Provider 小流量 Smoke](provider-live-smoke.md)。

原生 Provider 对应加载入口：

| 协议 | 加载方法 | 必填键 |
|---|---|---|
| OpenAI Chat-compatible | `OpenAICompatibleConfig.fromEnvironment` | `OPENAI_API_KEY`、`OPENAI_MODEL` |
| OpenAI Responses | `OpenAIResponsesConfig.fromEnvironment` | `OPENAI_API_KEY`、`OPENAI_MODEL` |
| DeepSeek | `ProviderPresets.deepSeekFromEnvironment` | `DEEPSEEK_API_KEY` |
| GLM | `ProviderPresets.glmFromEnvironment` | `GLM_API_KEY` |
| Anthropic Messages | `AnthropicMessagesConfig.fromEnvironment` | `ANTHROPIC_API_KEY`、`ANTHROPIC_MODEL` |
| Gemini Interactions | `GeminiInteractionsConfig.fromEnvironment` | `GEMINI_API_KEY`、`GEMINI_MODEL` |

模型、base URL、协议版本和 timeout 的完整默认值见 `.env.example`。厂商模型 ID 会变化，因此示例默认值只代表部署配置
占位，不是框架对“最新模型”的永久承诺。

## Embedding Provider

Embedding 使用独立 `EmbeddingService`，不会和 `ChatModel` 共用一份模糊能力声明。真实 Adapter 通过
`EmbeddingProviderDescriptor(provider, model, dimension, maxBatchSize, supportsDimensions)` 固化索引契约：

```scala
val config = OpenAICompatibleEmbeddingConfig(
  providerId = "openai-embeddings",
  baseUrl = "https://api.openai.com/v1",
  apiKey = sys.env("OPENAI_API_KEY"),
  model = "text-embedding-3-small",
  dimension = 1536
)

val layer = OpenAICompatibleEmbeddingService.configured(config)
```

DeepSeek、GLM 或其他国内厂商只有在其部署明确提供兼容 `/embeddings` 协议时才能复用该 Adapter；聊天兼容不等于
Embedding 兼容。切换模型、维度或向量归一化语义必须创建新知识索引版本，不能对旧向量表混写。
