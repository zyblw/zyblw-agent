# 可观测性、OpenTelemetry、Prometheus 与 Langfuse

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-25
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 生产架构与事实边界

框架把“业务事实”和“观测信号”明确分开：

```text
AgentRuntime
  ├─ RunStore / PersistedAgentEvent    审计、恢复、断线续传的事实来源
  └─ RunObserver
       ├─ TelemetryRunObserver         Run / Generation / Tool 持续时间 Trace
       └─ MetricsRunObserver           固定、低基数 Metrics

MemoryRagContextSourceResolver
  └─ AgentOperationTelemetry           Memory / Retriever Trace + Metrics

OpenTelemetry SDK
  ├─ OTLP traces ───────────────► Langfuse / Tempo / Jaeger
  └─ OTLP metrics ► Collector ──► Prometheus ─► Grafana / Alerts
```

Langfuse、OTLP Collector、Prometheus 或网络故障都不能改变 AgentState。审批、工具执行、Outbox/Inbox 和 Run 状态只以
PostgreSQL/RunStore 为准；Trace 被采样或 Metrics 丢失都不影响恢复。

## 2. 当前已经实现的能力

- `TelemetryRunObserver` 把开始/结束事件配成真实 Run、Generation 和 Tool duration；不再用零时长事件冒充调用耗时。
- `AgentOperationTelemetry` 统一观测 Memory、RAG Retriever、Worker command 和数值 Eval。
- `AgentMetric` 是封闭 ADT，调用方不能传任意 label Map。
- `MetricsRunObserver` 正确处理 Run 暂停/恢复的 active gauge、模型 usage、工具并行 callId 配对和终态清理。
- usage 除输入/输出总量外，还保存 Provider 明确报告的缓存输入与推理输出明细；隐藏推理正文仍被丢弃。
- `OpenTelemetryAgentMetrics` 提供真正的 Counter、UpDownCounter 和 Histogram instruments。
- `OtlpAgentObservability` 用同一个 scoped SDK 管理 Trace/Metrics exporter、后台线程、flush 和 close。
- `MetricAttributePolicy` 对模型、工具和 evaluator 使用 allow-list；未知值折叠为 `other`。
- `SanitizingTelemetry` 默认删除 prompt、answer、arguments、result、query、document 和凭据字段。
- Langfuse 使用官方 OTLP traces endpoint、Basic Auth 和 ingestion version 4。
- 已提供 Collector、Prometheus 告警规则和 Grafana dashboard 基线。
- 测试覆盖 duration 配对、暂停恢复、usage、动态 label 折叠、正文/密钥阻断以及 exporter 不可达。

## 3. 指标契约

OTel instrument 名称如下；Prometheus exporter 通常把点转换成下划线，并按 unit/counter 添加后缀。

| OTel instrument | 类型 | 关键维度 | 说明 |
|---|---|---|---|
| `zyblw.agent.run.active` | UpDownCounter | 无 | 当前进程正在执行的 Run；暂停会减一，恢复会加一 |
| `zyblw.agent.run.count` | Counter | `agent.outcome` | 进入终态的 Run 数 |
| `zyblw.agent.run.duration` | Histogram/s | `agent.outcome` | 从创建到终态的 wall-clock，包含审批等待 |
| `zyblw.agent.estimated_cost` | Double Counter/USD | `agent.outcome` | 配置价格估算，不是 Provider 最终账单 |
| `gen_ai.client.operation.duration` | Histogram/s | provider/model/outcome | 对齐 OTel GenAI development 约定 |
| `gen_ai.client.token.usage` | Long Histogram/token | provider/model/input-output | 只记录 Provider 明确报告的 usage |
| `zyblw.agent.model.cached.input.token.count` | Long Histogram/token | provider/model/outcome | 输入总量中的 Prompt Cache 命中子集 |
| `zyblw.agent.model.reasoning.output.token.count` | Long Histogram/token | provider/model/outcome | 输出总量中的内部推理子集；不含推理正文 |
| `zyblw.agent.tool.call.count` | Counter | risk/outcome/可选工具名 | 工具执行结果 |
| `zyblw.agent.tool.duration` | Histogram/s | risk/outcome/可选工具名 | 不包含人工审批等待 |
| `zyblw.agent.guardrail.decision.count` | Counter | stage/allowed | Guardrail 判定 |
| `zyblw.agent.retrieval.*` | Counter/Histogram | operation/outcome | 检索次数、延迟和授权后 hit 数 |
| `zyblw.agent.memory.operation.*` | Counter/Histogram | operation/outcome | 记忆生命周期操作 |
| `zyblw.agent.worker.command.*` | Counter/Histogram | command/outcome | 耐久命令处理 |
| `zyblw.agent.worker.lease.operation.count` | Counter | action/outcome | claim/heartbeat/release/reclaim |
| `zyblw.agent.evaluation.*` | Counter/Histogram | evaluator/passed | 评测分数和通过率 |

绝对禁止作为 Metrics label 的字段：runId、sessionId、tenantId、userId、callId、commandId、prompt、query、工具参数、
工具结果、引用正文、错误消息。它们要么高基数，要么敏感，通常两者兼有。

## 4. ZLayer 接入

业务依赖：

```scala
libraryDependencies += "io.github.zyblw" %% "zyblw-agent-opentelemetry" % agentVersion
```

同时把 Trace 发给 Langfuse、Metrics 发给 Collector：

```scala
import com.zyblw.agent.observability.*
import com.zyblw.agent.observability.otlp.*
import com.zyblw.agent.runtime.*

val langfuse = LangfuseOtlpConfig(
  host = sys.env("LANGFUSE_HOST"),
  publicKey = sys.env("LANGFUSE_PUBLIC_KEY"),
  secretKey = sys.env("LANGFUSE_SECRET_KEY"),
  serviceName = "zyblw-server",
  serviceVersion = sys.env.getOrElse("APP_VERSION", "dev"),
  deploymentEnvironment = sys.env.getOrElse("APP_ENV", "development")
)

val observabilityLayer = ZLayer.fromZIO(langfuse.toOtlp).flatMap { env =>
  val traceConfig = env.get[OtlpTelemetryConfig]
  OtlpAgentObservability.layer(
    traceConfig.copy(
      metricsEndpoint = Some("http://otel-collector:4318/v1/metrics"),
      // Langfuse Authorization 只在 traceHeaders；绝不能复制到 metrics collector。
      metricHeaders = Map.empty
    ),
    metricPolicy = MetricAttributePolicy(
      allowedModels = Set("deepseek-v4-flash", "glm-4.7-flash", "gpt-5.4"),
      allowedToolNames = Set("knowledge_search", "article_draft"),
      allowedEvaluators = Set("citation_correctness_v1", "tool_selection_v1")
    ),
    contentPolicy = ContentRecordingPolicy.MetadataOnly
  )
}

val safeObservability = Redactor.default >>> observabilityLayer
val runtimeObserver    = safeObservability >>> ObservabilityRunObserver.layer
val operationObserver  = safeObservability >>> AgentOperationTelemetry.layer
```

同一个 `safeObservability` 值在同一 ZLayer 图中会被 memoize。不要分别创建两套相同 SDK，否则会产生重复时间序列和
重复 exporter 线程。

真实 Memory/RAG ContextSources 使用：

```scala
val contextSources =
  (memoryStore ++ retriever ++ operationObserver) >>>
    MemoryRagContextSourceResolver.observed(
      MemoryRagContextPolicy(memoryLimit = 8, retrievalLimit = 6)
    )
```

`OtlpAgentObservability` 已使用 BatchSpanProcessor 与 PeriodicMetricReader，`record/emit` 不执行网络请求。对这一实现
通常不需要再套 `BufferedTelemetry`；后者主要服务自定义同步 sink。

## 5. Langfuse 映射

框架输出下列安全映射：

- Run：`langfuse.observation.type=agent`，`langfuse.session.id` 关联多轮会话；
- Model：`generation` + `gen_ai.request.model` + input/output usage；
- Tool：`tool` + 工具名/风险/结果分类，不含 arguments/result；
- RAG：`retriever` + operation/outcome/hit count，不含 query/document；
- Guardrail：`guardrail` + stage/allowed；
- Eval：`evaluator` + 规范化 evaluator 名和数值 score。
- Context：`agent.context.prepared` + 分区后估算 token/丢弃/压缩数量和白名单 rot code，不含任何上下文正文。

需要特别区分：`evaluator observation` 不是 Langfuse `Score` 数据对象。Score 可以关联 Trace、Observation、Session 或
Dataset Run，并用于质量分析；框架不会把普通 OTel span 假称为 Score。`LangfuseScoreClient` 已实现独立 Scores REST
写入协议，并遵循稳定 `id + name + timestamp 日期` 的幂等覆盖语义。

### 5.1 Langfuse Scores

```scala
import com.zyblw.agent.observability.otlp.*
import java.time.Instant
import zio.*
import zio.http.Client

val config = LangfuseScoresConfig(
  host = "https://cloud.langfuse.com",
  publicKey = sys.env("LANGFUSE_PUBLIC_KEY"),
  secretKey = sys.env("LANGFUSE_SECRET_KEY"),
  // 生产建议只开放固定低基数名称；自由文本和 comment 默认禁止。
  allowedScoreNames = Set("correctness", "agent_eval_case_passed")
)

val score = LangfuseScore(
  id = "业务持久化的稳定幂等键",
  name = "correctness",
  timestamp = Instant.parse("2026-07-15T00:00:00Z"), // 重试时必须保持同一个首次时间
  target = LangfuseScoreTarget.Trace("与 OTel trace 对齐的 traceId"),
  value = LangfuseScoreValue.Numeric(0.9)
)

val program = ZIO.serviceWithZIO[LangfuseScoreClient](_.publish(score))
val runnable = program.provide(Client.default, LangfuseScoreClient.configured(config))
```

客户端保证：

- 使用官方 `POST /api/public/scores` 和 Project Basic Auth；key 不进入 `toString`、错误或响应回执；
- Numeric、Categorical、Boolean、Text 是不同 ADT，Boolean 在线协议中明确写为 1/0 + `BOOLEAN`；
- Score 目标是 Trace、Observation、Session 三选一，避免冲突字段；
- 408/409/425/429/5xx 和 transport 错误使用相同完整 payload 有界退避重试，其他 4xx 不热重试；
- HTTP 响应有字节上限，响应正文不进入错误；调用 Fiber 取消会关闭正在读取的 Body；
- Text Score 和 comment 默认关闭；打开前必须确认不会上传 prompt、病历、答案、工具正文或隐藏推理；
- `LangfuseEvalScorePublisher` 对 Agent eval 只上传四个固定维度和 case 布尔门禁；对 Context 压缩 eval 只上传完成、
  证据、引用、禁止内容、稳定性、资源六维和 case 门禁。Score ID 使用稳定 SHA-256 投影，不上传 eval input、答案、
  压缩 attempts、摘要哈希、`EvalGrade.details`、工具参数或引用正文。

Langfuse 官方明确说明：只复用 `id` 不足以覆盖旧 Score，`name` 与 timestamp 的日期也必须保持相同，并应每次发送
完整 payload。因此崩溃重放必须把首次 `evaluatedAt` 与本地 eval 报告一同持久化，不能在每次 retry 时重新取当前时间。
Langfuse Scores 只是查询与反馈视图；本地/CI 报告仍是发布门禁事实源，远端不可用不能被解释为业务质量通过。

## 6. 隐私与基数策略

`MetadataOnly` 明确删除：

- system/developer/user prompt、模型文本 delta、thinking 和最终答案；
- 工具 arguments/result、审批原因；
- RAG query、文档正文、引用片段和 Memory value；
- Authorization、API Key、cookie、password、secret、token；
- SQL、数据库参数和 Provider 原始错误 body。

Trace 可以带 run/session ID 用于单次诊断，但 Metrics 绝不携带这些 ID。模型、工具和 evaluator 只有业务配置
allow-list 后才保留；其他值统一为 `other`。`Redacted` 只应在宿主提供真正的医疗 PII Redactor 后使用。

## 7. 部署基线

- Collector：[otel-collector.yaml](../deploy/observability/otel-collector.yaml)
- Prometheus：[prometheus.yml](../deploy/observability/prometheus/prometheus.yml)
- 告警规则：[zyblw-agent-alerts.yml](../deploy/observability/prometheus/zyblw-agent-alerts.yml)
- Grafana：[zyblw-agent-overview.json](../deploy/observability/grafana/zyblw-agent-overview.json)

Collector/Prometheus 对 OTel 名称的翻译策略可以配置。导入 dashboard 后应先查看 Collector 实际 `/metrics`，若部署
选择“不添加 unit suffix”的 translation strategy，需要同步调整 dashboard/alert 中的 `_seconds`、`_total` 名称。

## 8. 上线门禁

1. 用假 prompt、工具参数、答案、病历、密钥跑一遍，确认 collector payload 和 Langfuse UI 均无正文。
2. 关闭 collector/Langfuse，验证 Run 仍完成、RunStore 审计完整，应用关闭不超过 exporter timeout。
3. 在真实并发量下测量 time-series 数；未知模型/工具/evaluator 必须聚合为 `other`。
4. 验证 Run failure、model timeout、tool failure、P95 告警能触发且有 runbook。
5. Langfuse 项目按环境隔离，secret key 只来自 Secret Manager；Trace 与 Metric headers 分开配置。
6. 对 Eval 同时保留本地/CI 事实报告；Langfuse Score 不能成为唯一发布门禁存储，可靠重放必须保留首次 evaluatedAt。

官方参考：

- OpenTelemetry GenAI Metrics：https://github.com/open-telemetry/semantic-conventions/blob/main/docs/gen-ai/gen-ai-metrics.md
- OpenTelemetry Java SDK：https://opentelemetry.io/docs/languages/java/configuration/
- OpenTelemetry OTLP Metrics/Prometheus：https://opentelemetry.io/docs/compatibility/prometheus/otlp-metrics-export/
- Langfuse OpenTelemetry：https://langfuse.com/integrations/native/opentelemetry
- Langfuse Observation Types：https://langfuse.com/docs/observability/features/observation-types
- Langfuse Sessions：https://langfuse.com/docs/observability/features/sessions
- Langfuse Scores：https://langfuse.com/docs/evaluation/scores/overview
- Langfuse Scores via API/SDK 与幂等覆盖：https://langfuse.com/docs/evaluation/evaluation-methods/scores-via-sdk
- Langfuse 数据更新语义：https://langfuse.com/faq/all/tracing-data-updates
