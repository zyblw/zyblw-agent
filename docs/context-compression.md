# 模型辅助 Context 压缩与耐久摘要

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-25
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 这个组件解决什么问题

长会话不能无限把全部历史发送给模型。只做尾部字符截断虽然确定、便宜，却可能丢失：

- 当前任务目标；
- 用户明确约束；
- 已确认决定；
- 已完成工具结果；
- 等待审批或未完成事项；
- 来源引用；
- 已经发生但尚未解决的错误。

`zyblw-agent-core` 的 `context.llm` package 提供一个可选的模型辅助压缩器，但它没有让模型自由生成一段“看起来合理”的摘要。模型只能调用唯一工具
`submit_context_summary`，从真实历史中选择逐字 `evidenceQuote`。框架随后执行 schema、来源索引、逐字证据、引用、数量、
字符和 token 预算校验。

因此它的准确定位是：

> 模型负责选择哪些证据重要；ZIO harness 负责证明这些证据真实存在、控制成本、保存恢复边界。

## 2. 模块边界

```text
zyblw-agent-core / context
  ├─ ContextManager
  ├─ ContextCompressor SPI
  ├─ 分区预算、原子工具回合与 Context Rot
  └─ ContextSummaryCheckpoint

zyblw-agent-core / context.llm
  └─ LlmContextCompressor
       ├─ ChatModel
       ├─ 唯一 strict tool
       ├─ 逐字 evidence 校验
       ├─ 有限 schema repair
       └─ validation 失败时确定性降级

zyblw-agent-core / runtime
  └─ 在主模型调用前原子提交 checkpoint + usage
```

`context` 和 `context.llm` 都位于 Provider-neutral 的 core：它们只依赖 `ChatModel` SPI，不依赖任何具体 Provider。
只有业务在 ZLayer 装配中显式选择 `LlmContextCompressor` 时才会调用辅助模型，所以普通 Agent 不会产生额外 HTTP
请求、后台线程或模型费用。

## 3. 为什么采用抽取式摘要

普通生成式摘要有三个生产风险：

1. 模型可能把没有发生的动作总结为“已经完成”；
2. 引用、审批和用户约束可能被改写后失去精确语义；
3. 恢复时无法机械判断摘要是否忠于原历史。

当前 wire DTO 没有自由 `summaryText` 字段，每个条目只能提交：

| 字段 | 含义 | 本地验证 |
|---|---|---|
| `kind` | 目标、约束、决定、事实、工具结果、审批、待办、错误或引用 | 固定枚举 |
| `sourceMessageIndex` | 本次压缩输入中的来源消息位置 | 必须存在 |
| `evidenceQuote` | 需要保留的逐字片段 | 必须逐字出现在稳定消息渲染中 |
| `priority` | 1～5 的选择优先级 | 固定范围 |
| `references` | 来源中真实出现的引用 ID/URI | 安全字符、长度、数量和逐字存在校验 |

最终摘要按优先级选择、按来源时间顺序输出。Provider 返回数组的顺序不会改变 checkpoint 文本。

## 4. 耐久恢复语义

`AgentState.contextSummary` 保存 `ContextSummaryCheckpoint`：

```scala
final case class ContextSummaryCheckpoint(
  summary: String,
  coveredMessages: Int,
  sourceDigest: String,
  compressorVersion: String
)
```

- `coveredMessages` 表示摘要覆盖 `messages[0, coveredMessages)`；
- `sourceDigest` 对原始消息前缀做稳定 SHA-256；
- `compressorVersion` 标识 Prompt/schema/渲染协议版本；
- `summary` 是已经标为“不可信事实数据”的摘要正文，不会进入 HTTP 或 Telemetry。

下一回合只压缩：

```text
既有摘要 + messages[coveredMessages, 新 droppedMessages)
```

如果没有新增淘汰消息，直接复用 checkpoint，不调用 Provider。若历史前缀被异常改写、摘要覆盖范围与 recent messages 重叠，
或消息数量回退，ContextManager 会 fail-closed。

摘要、辅助模型 usage、`lastEventSequence` 和状态版本通过同一个 `RunStore.commit/commitFenced` 事务提交。Worker 在摘要完成后
崩溃，新的 Worker 会从 checkpoint 继续，而不是再次付费压缩相同历史。

## 5. 模型调用预算

ContextManager 会先计算：

```text
compressionCallBudget =
  maxModelCalls - alreadyConsumedModelCalls - 1
```

最后的 `1` 为本回合主 Agent 模型保留。`LlmContextCompressor` 必须接受这个预算：

```scala
compress(messages, targetTokens, maxModelCalls)
```

当预算为零时，它会在调用 Provider 之前返回
`context-compressor-model-budget`。Schema repair 也受同一个调用预算限制。

成功压缩返回：

- 合计 `TokenUsage`；
- 实际模型调用次数；
- 稳定 compressor version。

Runtime 在主模型调用前把这些值加入 `UsageSummary`，再重新检查 model call、input/output/total token 和费用预算。

## 6. 推荐配置

生产知识问答建议先从 ZIO Config 加载非密钥治理参数，再把压缩器显式放入应用 Layer：

```scala
import com.zyblw.agent.app.*
import com.zyblw.agent.context.*
import com.zyblw.agent.context.llm.*
import com.zyblw.agent.core.*

val applicationLayer = ZLayer.unwrap {
  LlmContextCompressorConfigLoader.load().map { compressionConfig =>
    ZLayer.make[AgentApplication.Services](
      dataSourceLayer,
      PostgresAgentPersistence.layer,
      chatModelLayer,
      registeredToolsLayer,
      contextSourcesLayer,
      guardrailLayer,
      observerLayer,
      LlmContextCompressor.configured(compressionConfig),
      AgentApplication.durableWithContextCompressor(
        WorkerId("knowledge-worker"),
        applicationConfig
      )
    )
  }
}
```

Agent 定义建议只对历史启用模型辅助压缩：

```scala
val contextPolicy = ContextPolicy(
  historyCompression = CompressionMode.ModelAssisted,
  toolOutputCompression = CompressionMode.Deterministic
)
```

对应环境变量示例：

```bash
ZYBLW_AGENT_CONTEXT_COMPRESSION_MODEL_PROVIDER=deepseek
ZYBLW_AGENT_CONTEXT_COMPRESSION_MODEL_MODEL=deepseek-v4-flash
ZYBLW_AGENT_CONTEXT_COMPRESSION_MODEL_MAX_OUTPUT_TOKENS=1200
ZYBLW_AGENT_CONTEXT_COMPRESSION_BEHAVIOR_REQUEST_TIMEOUT=20s
ZYBLW_AGENT_CONTEXT_COMPRESSION_BEHAVIOR_MAX_SCHEMA_REPAIRS=1
ZYBLW_AGENT_CONTEXT_COMPRESSION_BEHAVIOR_COMPRESSOR_VERSION=tcm-learning-summary-v1
```

启用条件是三重显式选择：

1. Agent 的 `historyCompression` 为 `ModelAssisted`；
2. 应用使用 `durableWithContextCompressor`、`inMemoryWithContextCompressor` 或
   `inMemoryDefaultsWithContextCompressor`；
3. ZLayer 图中提供能力声明为模型辅助的 `ContextCompressor`。

若只设置第 1 项而仍使用默认确定性装配，ContextManager 会返回
`context-model-assisted-compressor-not-configured`，不会静默假装已启用。反过来，即使装配了 LLM compressor，
使用 `CompressionMode.Deterministic` 的 Agent 也始终走框架本地算法，不产生辅助模型费用。

可以先运行无公网、无真实费用的完整示例理解装配与状态变化：

```bash
mise exec -- sbt "examples/runMain com.zyblw.agent.examples.ContextCompressionExample"
```

另一个离线示例会从版本化中文 JSON 数据集运行三次真实 `LlmContextCompressor`，并对事实、引用、注入、稳定性、延迟、
Token、调用次数和成本执行发布门禁：

```bash
mise exec -- sbt "examples/runMain com.zyblw.agent.examples.ContextCompressionEvalExample"
```

数据格式、价格表和 CI 接入见 [Context 压缩质量评测与发布门禁](context-compression-evaluation.md)。

启用真实模型前还应运行专用小流量门禁：

```bash
ZYBLW_SMOKE_PROVIDER=deepseek \
  mise exec -- sbt "examples/runMain com.zyblw.agent.examples.ContextCompressionLiveSmokeExample"
```

该入口会关闭确定性 fallback，并验证当前真实模型能否稳定保留固定约束/引用、拒绝注入诱饵和满足资源/价格预算。

`toolOutputCompression` 默认继续使用确定性方式。单独用模型压缩一条 Tool result 会增加费用、恢复缓存和敏感数据边界；
`LlmContextCompressor` 默认以 `context-compressor-standalone-tool-disabled` 拒绝这种调用。只有业务完成专门 eval 与数据流向
审查后，才设置 `allowStandaloneToolOutput = true`。

## 7. Repair 与确定性降级

模型返回下列错误时可以有限 repair：

- 未调用或调用了错误工具；
- 参数 JSON 不符合 schema；
- 引用了不存在的消息索引；
- evidenceQuote 不是原文子串；
- 引用不存在于同一来源；
- 条目数量或最终摘要预算超限。

Repair Prompt 不回填无效模型参数、证据正文或解析错误，只告诉模型重新调用唯一工具。

如果 validation 在有限次数后仍失败，默认使用 `ContextCompressor.deterministicValue`，并：

- 丢弃所有未验证模型摘要；
- 保留已经发生的 Provider usage 和调用次数；
- 使用 `*.fallback` compressor version；
- 把本地确定性结果与 usage 一起持久化。

鉴权、限流、网络、超时等 Provider/Transport 错误不会被该降级掩盖，仍按 typed retryable 错误交给上层处理。

## 8. 可观测性

耐久事件 `ContextCompacted` 只包含：

- 覆盖消息数；
- 辅助模型调用数；
- input/output token；
- compressor version；
- 时间戳。

不包含摘要、源哈希、Prompt、Tool result 或用户正文。

OpenTelemetry 指标：

- `zyblw.agent.context.compression.count`
- `zyblw.agent.context.compression.model.call.count`
- `zyblw.agent.context.compression.covered.message.count`
- `gen_ai.client.token.usage`

Trace 事件为 `agent.context.compacted`；Langfuse 会把它作为 span，而不是把摘要正文上传为 generation 内容。

## 9. 测试证据

`LlmContextCompressorSpec` 覆盖：

- 唯一 strict tool 和 specific/required tool choice；
- 逐字证据与引用验证；
- 温度强制为零和输出预算；
- 安全 repair 与多次 usage 累计；
- validation 持续失败时确定性降级；
- 辅助模型调用预算在 Provider 前生效；
- 默认拒绝独立 Tool result 模型压缩；
- 超时中断与 retryable typed error。

`DefaultContextManagerSpec` 另外覆盖：

- checkpoint 生成；
- 相同历史恢复后不重复压缩或计费；
- 消息前缀改写时 source digest fail-closed。

`AgentRuntimeSpec` 验证 checkpoint、usage 和 `ContextCompacted` 在主模型之前进入同一耐久状态链路。

`ContextCompressionEvaluationSpec` 另外覆盖：

- 重复运行的逐字证据和引用最差召回率；
- 任意一次提示注入命中即硬失败；
- 输出摘要 SHA-256 和 compressorVersion 稳定性；
- 延迟、输入/输出 Token、模型调用、摘要长度与带版本成本；
- typed ContextError 继续执行数据集且不泄漏错误正文；
- 普通文件/JAR 资源的大小、严格 UTF-8、空集和重复 ID 边界。

## 10. 仍然诚实保留的边界

- 正式评测 Harness、中文 starter dataset 与离线示例已经完成，但还需要用真实中医长会话失败样本扩充约束、引用、
  审批和待办数据集并形成 CI 趋势；
- DeepSeek、GLM、OpenAI Chat/Responses、Anthropic 和 Gemini 的统一 compressor smoke 入口已经完成；当前环境没有
  厂商密钥，仍需部署团队生成各账号/模型/价格版本的真实通过报告；
- Provider 在网络失败前已经计费、但没有返回 usage 时，框架无法凭空恢复厂商账单数据；
- 当前摘要是抽取式，不追求文学化或高度抽象；只有事实保真 eval 证明安全后，才考虑受约束的生成式二级摘要；
- 默认 `AgentApplication.durable/inMemory/inMemoryDefaults` 只装配确定性压缩；模型辅助必须使用名称明确的
  `*WithContextCompressor` 入口；
- AgentState schemaVersion 已提升为 4。首次正式发布前没有历史兼容负担；已有旧持久化 Run 的环境必须明确迁移或清理，
  不能把缺字段解码失败当作普通重试。
