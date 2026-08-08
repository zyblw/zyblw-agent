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

内置 Chat Adapter 的非成功 HTTP 响应统一投影为 `AgentError.ModelHttpFailure`。401/407、403、408、409、429 与
5xx 分别进入 Authentication、Authorization、Timeout、Conflict、RateLimit 与 Unavailable；其余 4xx 进入
Validation。重试语义独立保存，408/409/429/5xx 可重试。适配器不把 Provider 原始响应正文写进错误：只允许从标准
envelope 中提取短、低基数的 code/type，避免提示词、账号信息、API Key 或网关 HTML 进入日志、遥测和管理面。

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

## 运行时切换与凭据边界

装配之后，模型不再是完全冻结的。运行时通过 `ModelPolicySource` 逐次调用解析 provider、模型名、温度与输出上限，
因此运维控制台可以在**已注册**的组合之间切换而不重启进程。这条路径之所以成立，是因为 `RoutedChatModel` 本来就按
请求里的 provider 名路由；把解析点从 `AgentDefinition.modelSettings` 移到策略源，切换就变成了替换一个不可变引用。

覆盖是稀疏的：只改 provider 不会把模型名一起抹成该 provider 的默认值。`toolChoice`、`providerOptions` 与
`metadata` **不可**被部署级覆盖改动——它们是 Agent 的行为契约，而不是部署工作点。

**凭据不在这条路径上。** API Key 只在装配阶段从 ZIO Config（环境变量、系统属性，或宿主替换的任何配置后端）解析。
管理面能看到的只有「凭据是否就位」和一个像 `env:DEEPSEEK_API_KEY` 的展示引用，没有任何端点接收、返回或存储 Key
的值。需要对接 Vault 或 K8s Secret 的部署替换 `ConfigProvider` 即可，框架只要求装配时拿到一个已解析的配置对象。

由此得到一条明确的边界：

- **可以**热切换到已注册的 provider / 模型，立即生效；
- **不能**热增一个全新 provider——它需要新凭据与新 HTTP 客户端，两者都在装配期固化，必须重启。

要让某个 provider 成为可切换目标，就要在启动时把它注册进路由器，即使它平时不承担流量。这正是故障切换的正确准备
方式：备用 provider 的凭据和连通性应该在事故发生**之前**就已验证，而不是在主 provider 挂掉时才第一次尝试解析。
`POST /api/v1/admin/models/probe` 用于做这种事前验证。

管理台如何呈现这些约束、目录如何充当写入校验依据，见 [管理 API 与运维控制台](admin-console.md#16-模型治理)。

## 成本估算

`UsageSummary.estimatedCost` 只有在部署声明了 `ModelPriceBook` 时才非零：

```scala
val prices = ModelPriceBook.of(
  ("deepseek", "deepseek-v4-flash", ModelPrice(BigDecimal("0.28"), BigDecimal("0.42"), currency = "CNY"))
)
```

框架**不内置**任何厂商价格。价格随时间、合同与区域变化，把一份猜测的价目表编译进框架只会让成本看板显示一个看起来
精确但其实错误的数字，而运维没有任何线索知道它错了。缺失条目估算为零，与「未知费用保持零，不伪造账单事实」一致。

两个容易搞错的计费口径框架已经处理：`cachedInputTokens` 是 `inputTokens` 的**子集**，两个字段各自乘单价会把缓存
命中部分收两次费；`reasoningOutputTokens` 同样是 `outputTokens` 的子集且按普通输出 token 计费，为它单独计价就是
重复计费。价格表不允许混用货币，因为 `estimatedCost` 是单一标量。

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

**Embedding 模型与 Chat 模型不同，不能在运行时切换。** 维度由 Flyway 迁移固定（当前为 1536），而一份索引里的向量
只能与生成它的模型比较——换模型等于让整个知识库的既有向量失去意义。因此控制台以只读方式展示它，并不提供切换入口：
一个能保存成功却悄悄让 RAG 召回质量崩塌的开关，比没有这个开关危险得多。真正需要更换模型的部署必须执行新维度迁移
并全量重新摄入。

`PostgresKnowledgeIndexStore` 与 `PostgresPgVectorStore` 会在写入前拒绝维度不匹配的请求，因此错配会以明确失败出现，
而不是写入一批无法正确检索的向量。
