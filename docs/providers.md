# Provider 与能力矩阵

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-09-17
>
> 事实来源：对应模块源码、测试与构建定义

## 统一协议

`ChatModel` 提供 `complete`、`stream` 和 `capabilities`。流式结束必须产生 `ModelStreamEvent.Completed`；否则 runtime 以类型化错误失败。

| 能力 | OpenAI Responses | Anthropic Messages | OpenAI-compatible | DeepSeek 档案 | GLM 档案 | Qwen 档案 | Kimi 档案 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Tool Calling | 是 | 是 | 是 | 是 | 是 | 是 | 是 |
| Strict Tool Schema | 是 | 否 | 是 | 否 | 否 | 否 | 保守关闭 |
| 指定单个 Tool Choice | 是 | 是 | 是 | 否 | 仅 auto | 是 | 仅 auto |
| Developer role | 原生 | 合并到顶层 system | 原生 | 映射 system | 映射 system | 映射 system | 映射 system |
| 推理状态回放 | 原始 output items | content blocks/signature | `reasoning_content` | `reasoning_content` | `reasoning_content` | `reasoning_content` | `reasoning_content` |
| typed 推理控制 | `reasoning.effort` | 模型相关 | `reasoning_effort` | `thinking` + effort | `reasoning_effort` | `enable_thinking` | `thinking` |
| SSE streaming | typed event | typed event | choices/delta | choices/delta | choices/delta | choices/delta | choices/delta |

能力表是配置基线，具体模型仍可通过 `ProviderDescriptor.models` 覆盖。

## 配置

```scala
val configs = for
  deepSeek <- ProviderPresets.deepSeekFromEnvironment
  glm      <- ProviderPresets.glmFromEnvironment
  qwen     <- ProviderPresets.qwenFromEnvironment
  kimi     <- ProviderPresets.kimiFromEnvironment
  openAI   <- ProviderPresets.openAIFromEnvironment
yield List(deepSeek, glm, qwen, kimi, openAI)

val layer: ZLayer[Client, AgentError, ChatModel] =
  ZLayer.unwrap(configs.map(MultiProviderChatModel.layer("deepseek", _)))
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
GLM、Qwen、Kimi、OpenAI Chat/Responses、Anthropic、Gemini 的统一 CLI，并提供 MemoryExtractor 真实工具调用 smoke。完整命令和 CI
分层见 [真实 Provider 小流量 Smoke](provider-live-smoke.md)。

原生 Provider 对应加载入口：

| 协议 | 加载方法 | 必填键 |
|---|---|---|
| OpenAI Chat-compatible | `OpenAICompatibleConfig.fromEnvironment` | `OPENAI_API_KEY`、`OPENAI_MODEL` |
| OpenAI Responses | `OpenAIResponsesConfig.fromEnvironment` | `OPENAI_API_KEY`、`OPENAI_MODEL` |
| DeepSeek | `ProviderPresets.deepSeekFromEnvironment` | `DEEPSEEK_API_KEY` |
| GLM | `ProviderPresets.glmFromEnvironment` | `GLM_API_KEY` |
| Qwen | `ProviderPresets.qwenFromEnvironment` | `QWEN_API_KEY`、`QWEN_BASE_URL`、`QWEN_MODEL` |
| Kimi | `ProviderPresets.kimiFromEnvironment` | `MOONSHOT_API_KEY`、`KIMI_MODEL`；`KIMI_BASE_URL` 可选 |
| Anthropic Messages | `AnthropicMessagesConfig.fromEnvironment` | `ANTHROPIC_API_KEY`、`ANTHROPIC_MODEL` |
| Gemini Interactions | `GeminiInteractionsConfig.fromEnvironment` | `GEMINI_API_KEY`、`GEMINI_MODEL` |

模型、base URL、协议版本和 timeout 的完整默认值见 `.env.example`。厂商模型 ID 会变化，因此示例默认值只代表部署配置
占位，不是框架对“最新模型”的永久承诺。

Qwen 不提供编译期默认模型或默认区域端点。部署必须让 `QWEN_BASE_URL` 与 API Key 的 Model Studio 区域保持一致，并通过
`QWEN_MODEL` 明确选择已开通的模型。其一级兼容档案声明 tool calling、完整 tool choice、streaming 和 usage；视觉与思考能力应在多端点
模型清单中逐模型声明并经真实 smoke 验证。

Kimi 使用官方 `https://api.moonshot.ai/v1` 作为默认根端点，但不提供编译期默认模型；部署必须填写 `KIMI_MODEL`。Kimi
档案会把 developer role 映射为 system、限制 tool choice 为 auto、显式发送 `strict=false`，并在工具回合回放
`reasoning_content`。视觉、并行工具和可选推理档位仍按具体模型声明，不按品牌猜测。

## 接入原则：共享传输，不共享幻想

DeepSeek、Qwen、GLM、Kimi 都可以复用 `OpenAICompatibleChatModel` 的 HTTP、SSE、错误分类、取消和 usage 处理，但“兼容”只表示
Chat Completions 的基础形状相近。推理开关、tool choice、strict schema、developer role 和工具回合续接仍由各自
`OpenAICompatibility` 档案负责；`ProviderDescriptor.models` 再描述具体模型的视觉、推理档位、并行工具、上下文和输出上限。

因此新增同协议模型通常不需要新 Adapter：优先选择已有兼容档案并增加模型能力清单。只有认证、请求/响应形状、流事件或
续接语义无法由现有方言表达时，才新增原生 Adapter。未经 wire contract 与真实 smoke 验证的能力保持关闭。

## 中转站与多端点

一个 URL + 一个 Key + 按任务切换模型名时，使用配置驱动的 OpenAI-compatible 端点，而不是再写一个 Adapter。
这里的“可兼容”有明确边界：中转站必须真正实现 `POST /chat/completions`、Bearer Key、OpenAI 形状的 JSON/SSE、工具调用和
usage 语义。仅仅声称“支持某模型”，不能证明这些协议细节成立；应以该中转站自己的协议文档和 live smoke 为准。

环境变量 `ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON` 声明多个端点：

```json
{
  "defaultProvider": "china-reasoning",
  "endpoints": [
    {
      "providerId": "china-reasoning",
      "baseUrl": "https://gateway.example/v1",
      "apiKeyEnv": "RELAY_API_KEY",
      "defaultModel": "deepseek-v4",
      "protocol": "relay",
      "compatibilityProfile": "deepseek",
      "models": [{
        "name": "deepseek-v4",
        "capabilities": {
          "toolCalls": true,
          "streaming": true,
          "thinking": true,
          "reasoningTokens": true,
          "maxInputTokens": 128000,
          "maxOutputTokens": 8192,
          "reasoningEfforts": ["None", "Low", "High", "Max"]
        }
      }]
    }
  ]
}
```

`ProductionSupportHost` 与 `KnowledgeQaHost` 已直接使用这条装配：JSON 非空时构造多端点路由，缺失或空白时使用单一
`OPENAI_*`；JSON 非空但结构错误或违反 URL/模型约束时 fail-closed，不会静默换用 OpenAI。

约束：

- `baseUrl` 必须是无 user-info、query、fragment 的 `https://` 根地址，只有本机 `http://127.0.0.1` 可使用明文 HTTP；
- 密钥只通过 `apiKeyEnv` 指向环境变量，配置对象的 `toString` 不含值；
- `compatibilityProfile` 可选 `openai/deepseek/glm/qwen/kimi/generic`；它与 `providerId` 分离，因此业务别名和网关也能复用已验证方言；
- 未填写 profile 时，已知 providerId 自动选择同名档案；`protocol=relay` 默认使用保守的 `generic` 档案；
- 模型清单非空时必须包含 `defaultModel`，名称不得重复，token 上限必须为正数；
- `ProviderEndpoints.assemble` 产出 `RoutedChatModel` + `ProviderRegistry`，模型清单写入 `ProviderDescriptor.models`；
- `ModelRoleCatalog` 把 Agent 声明的角色（planner / summarizer / extraction）映射到已注册 provider/model。Agent 已显式写 provider/model 时角色只作审计元数据。`AgentApplicationConfig.roleCatalog` 在 `submitStart`、同步 `run` 和 Harness Start 创建 Run 时解析并冻结进 definition 与组合指纹；未声明角色 fail-closed。宿主仍可预先 `applyTo`，但不再是唯一接线方式；
- `FallbackChatModel` 只对 typed 可重试错误换下一个候选。能力不匹配、配置错误和安全拒绝 fail-closed。降级事实写入 `ModelSettings.metadata` 的 `fallback-chain` / `fallback-from`，不进 prompt。

### 同一个中转站承载多家模型

`compatibilityProfile` 描述的是中转站实际暴露的 **wire 方言**，不只是模型品牌。同一个 URL/Key 可以在 `endpoints` 中重复使用，
但 DeepSeek、Qwen、Kimi、OpenAI 等方言应分别声明不同 `providerId`。例如把同一个网关注册为
`relay-openai`、`relay-deepseek` 和 `relay-qwen`，分别选择 `openai`、`deepseek`、`qwen` profile。这样路由、能力目录、
推理字段和故障切换证据都不会混在一起。业务代码通过 `ModelRoleCatalog` 使用稳定的 planner/summarizer/extraction 角色，
不要再为上游模型名设计第二套别名映射。

如果中转站把 Anthropic 或 Gemini 翻译成 OpenAI Chat Completions，使用 `generic`，并只开启实际 smoke 通过的能力；它不等于
框架的 Anthropic Messages 或 Gemini Interactions 原生 Adapter。需要厂商原生工具、缓存、会话或特有事件语义时，应直连原生
Adapter，不能假定中转层会无损翻译。

当前通用中转配置使用标准 `Authorization: Bearer <key>`。需要 `x-api-key`、签名认证、mTLS 或动态租户 Header 的网关不应把
秘密塞进 URL 或 `providerOptions`；优先在受控企业 API Gateway 中转换为 Bearer 接口。只有出现无法由网关治理的真实部署需求时，
再新增窄化、可脱敏、可测试的认证策略，而不是开放任意模型可控 Header。

### 中转站生产准入

将 `ZYBLW_SMOKE_PROVIDER=relay` 后，三个现有 smoke 入口都会加载
`ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON`。`ZYBLW_SMOKE_PROVIDER_ID` 可选择一个已声明的逻辑 Provider；留空则验证
`defaultProvider`。每个实际启用的 `(providerId, model)` 都应分别运行：

1. 通用 complete + stream smoke；
2. 使用工具或模型辅助压缩时，再运行对应的工具协议 smoke；
3. 固定业务 Eval、成本与延迟门禁；
4. 中转站 URL、模型映射、Key、profile 或能力声明改变后重新验证。

中转站是额外故障域和数据处理方。生产部署还需独立确认其数据保留、训练使用、地域、审计、SLA、限流、模型版本固定、
上游错误透传和供应商切换政策；框架的协议兼容不能替代这些合规与运维事实。

`CapabilityMatrix.requireConsistent` 把声明能力与 `ProviderContract` 探测结果对齐；未覆盖的模型不会被猜测成“全支持”。OpenAI Chat 与 Responses 的 HTTP stub 都走 `verifySuite`。

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

`cachedInputTokens`（cache read）与 `cacheWriteInputTokens` 都是 `inputTokens` 的**子集**，因此框架按
fresh/read/write 三段分别计价，不会先对全部 input 计费再叠加缓存价格。`reasoningOutputTokens` 同样是
`outputTokens` 的子集，只用于观测而不再次累加。价格表不允许混用货币，因为 `estimatedCost` 是单一标量。

## 推理档位

Agent 只通过 `ModelSettings.reasoningEffort` 声明 `None/Low/Medium/High/Max`，不直接填写厂商字段。
`ModelCapabilities.reasoningEfforts` 是 Provider + Model 层的已验证集合；显式档位不在集合中时，能力校验和 Adapter
均在发网前拒绝。OpenAI Responses 映射为 `reasoning.effort`，OpenAI 官方 Chat Completions 兼容档映射为
`reasoning_effort`，Gemini Interactions 映射为 `generation_config.thinking_level`。DeepSeek 使用 `thinking.type` 并在
非 None 时发送其 effort；GLM 使用 `reasoning_effort`；Qwen 使用 `enable_thinking`；Kimi 使用 `thinking.type`。除已验证的
DeepSeek 档位外，兼容端点应通过每模型 capability 明确开启，不会因为返回了 reasoning 字段就假设可配档位。

`ReasoningEffort` 是稳定意图，不承诺每家都有相同粒度。Qwen/Kimi 当前只可靠投影为开关；GLM 的档位集合随模型声明；
DeepSeek 不声明 Medium，避免把不存在的中档伪装成精确能力。调用者若不需要控制，应保持 `None`（Scala 的
`Option.None`，即不设置字段），让模型使用部署默认值。

## Embedding Provider

Embedding 使用独立 `EmbeddingModel`，不会和 `ChatModel` 共用一份模糊能力声明。真实 Adapter 通过
`EmbeddingProviderDescriptor(provider, model, dimension, maxBatchSize, supportsDimensions)` 固化索引契约：

```scala
val config = OpenAICompatibleEmbeddingConfig(
  providerId = "openai-embeddings",
  baseUrl = "https://api.openai.com/v1",
  apiKey = sys.env("OPENAI_API_KEY"),
  model = "text-embedding-3-small",
  dimension = 1024
)

val layer = OpenAICompatibleEmbeddingService.configured(config)
```

DeepSeek、GLM 或其他国内厂商只有在其部署明确提供兼容 `/embeddings` 协议时才能复用该 Adapter；聊天兼容不等于
Embedding 兼容。切换模型、维度或向量归一化语义必须创建新知识索引版本，不能对旧向量表混写。

**Embedding 模型与 Chat 模型不同，不能在运行时切换。** 维度由 Flyway 迁移固定（当前为 1024），而一份索引里的向量
只能与生成它的模型比较——换模型等于让整个知识库的既有向量失去意义。因此控制台以只读方式展示它，并不提供切换入口：
一个能保存成功却悄悄让 RAG 召回质量崩塌的开关，比没有这个开关危险得多。真正需要更换模型的部署必须执行新维度迁移
并全量重新摄入。

`PostgresKnowledgeIndexStore` 与 `PostgresPgVectorStore` 会在写入前拒绝维度不匹配的请求，因此错配会以明确失败出现，
而不是写入一批无法正确检索的向量。
