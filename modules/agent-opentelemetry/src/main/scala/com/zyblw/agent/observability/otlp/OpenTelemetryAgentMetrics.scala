package com.zyblw.agent.observability.otlp

import com.zyblw.agent.observability.*
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.*
import java.util.List as JavaList
import scala.jdk.CollectionConverters.*
import zio.*

/** OpenTelemetry 指标标签的基数策略。
  *
  * Provider 会被框架压缩为固定 family；模型名和工具名却可能由请求、MCP Server 或租户动态提供，因此默认不直接 输出。只有业务明确列入 allow-list 的值才保留，其余统一为
  * `other`。这样既能按已知主力模型做容量分析，也不会 因攻击者不断构造新名字而拖垮 Prometheus/OTLP 后端。
  *
  * @param allowedModels
  *   允许成为 `gen_ai.request.model` label 的精确模型名
  * @param allowedToolNames
  *   允许成为 `agent.tool.name` label 的精确注册工具名；空集合表示完全省略该 label
  * @param allowedEvaluators
  *   允许成为 `agent.evaluation.name` label 的稳定评测器名
  */
final case class MetricAttributePolicy(
    allowedModels: Set[String] = Set.empty,
    allowedToolNames: Set[String] = Set.empty,
    allowedEvaluators: Set[String] = Set.empty
):
  private val normalizedModels     = allowedModels.iterator.map(normalize).filter(_.nonEmpty).toSet
  private val normalizedTools      = allowedToolNames.iterator.map(normalize).filter(_.nonEmpty).toSet
  private val normalizedEvaluators = allowedEvaluators.iterator.map(normalize).filter(_.nonEmpty).toSet

  /** 已知模型保留规范化值，未知值折叠为 other；不返回用户原始字符串。 */
  private[otlp] def model(value: String): String = allowListed(value, normalizedModels)

  /** 工具 allow-list 为空时完全不输出维度；非空时未知工具折叠为 other。 */
  private[otlp] def tool(value: String): Option[String] =
    Option.when(normalizedTools.nonEmpty)(allowListed(value, normalizedTools))

  /** 评测器名称始终输出，但未知值折叠为 other，便于统计未登记评测。 */
  private[otlp] def evaluator(value: String): String = allowListed(value, normalizedEvaluators)

  private def allowListed(value: String, allowed: Set[String]): String =
    val candidate = normalize(value)
    if allowed.contains(candidate) then candidate else "other"

  private def normalize(value: String): String = value.trim.toLowerCase.take(80)

/** 使用 OpenTelemetry Java Metrics API 实现 AgentMetrics。
  *
  * 本类只负责把类型化 `AgentMetric` 映射为固定 instruments。它不拥有 SDK 生命周期；`MeterProvider`、OTLP exporter、 flush 和 close 都由
  * `OtlpAgentObservability` 的 scoped ZLayer 管理。
  *
  * @param meter
  *   OpenTelemetry SDK 创建的 Meter
  * @param attributePolicy
  *   模型/工具等潜在高基数字段的 allow-list
  */
final class OpenTelemetryAgentMetrics(
    meter: Meter,
    attributePolicy: MetricAttributePolicy
) extends AgentMetrics:

  private val activeRuns = meter
    .upDownCounterBuilder("zyblw.agent.run.active")
    .setDescription("当前进程正在执行而非暂停的 Agent Run 数")
    .setUnit("{run}")
    .build()

  private val runCount = meter
    .counterBuilder("zyblw.agent.run.count")
    .setDescription("进入终态的 Agent Run 次数")
    .setUnit("{run}")
    .build()

  private val runDuration = meter
    .histogramBuilder("zyblw.agent.run.duration")
    .setDescription("Agent Run 从创建到终态的 wall-clock 时间，包含人工等待")
    .setUnit("s")
    .setExplicitBucketBoundariesAdvice(
      JavaList.of(0.1, 0.5, 1.0, 2.0, 5.0, 15.0, 30.0, 60.0, 300.0, 1800.0, 86400.0)
    )
    .build()

  private val estimatedCost = meter
    .counterBuilder("zyblw.agent.estimated_cost")
    .ofDoubles()
    .setDescription("框架按配置价格估算的模型费用；不等同于 Provider 最终账单")
    .setUnit("{USD}")
    .build()

  private val modelCallCount = meter
    .counterBuilder("zyblw.agent.model.call.count")
    .setDescription("模型调用完成、失败或取消次数")
    .setUnit("{call}")
    .build()

  // 名称、单位和必需 attributes 对齐 OpenTelemetry GenAI development semantic convention。
  private val modelDuration = meter
    .histogramBuilder("gen_ai.client.operation.duration")
    .setDescription("GenAI 客户端操作耗时")
    .setUnit("s")
    .setExplicitBucketBoundariesAdvice(
      JavaList.of(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 15.0, 30.0, 60.0, 180.0)
    )
    .build()

  private val tokenUsage = meter
    .histogramBuilder("gen_ai.client.token.usage")
    .ofLongs()
    .setDescription("Provider 返回的输入或输出 token 数")
    .setUnit("{token}")
    .setExplicitBucketBoundariesAdvice(
      longBuckets(1L, 4L, 16L, 64L, 256L, 1024L, 4096L, 16384L, 65536L, 262144L, 1048576L)
    )
    .build()

  private val cachedInputTokenUsage = meter
    .histogramBuilder("zyblw.agent.model.cached.input.token.count")
    .ofLongs()
    .setDescription("Provider 明确报告的 Prompt Cache 命中 token")
    .setUnit("{token}")
    .setExplicitBucketBoundariesAdvice(
      longBuckets(1L, 4L, 16L, 64L, 256L, 1024L, 4096L, 16384L, 65536L, 262144L, 1048576L)
    )
    .build()

  private val reasoningOutputTokenUsage = meter
    .histogramBuilder("zyblw.agent.model.reasoning.output.token.count")
    .ofLongs()
    .setDescription("Provider 明确报告的内部推理 token；只记录数量")
    .setUnit("{token}")
    .setExplicitBucketBoundariesAdvice(
      longBuckets(1L, 4L, 16L, 64L, 256L, 1024L, 4096L, 16384L, 65536L, 262144L, 1048576L)
    )
    .build()

  private val toolCallCount = meter
    .counterBuilder("zyblw.agent.tool.call.count")
    .setDescription("工具执行结束次数")
    .setUnit("{call}")
    .build()

  private val toolDuration = meter
    .histogramBuilder("zyblw.agent.tool.duration")
    .setDescription("工具真实执行阶段耗时，不包含人工审批等待")
    .setUnit("s")
    .setExplicitBucketBoundariesAdvice(JavaList.of(0.001, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 30.0, 120.0))
    .build()

  private val guardrailCount = meter
    .counterBuilder("zyblw.agent.guardrail.decision.count")
    .setDescription("Guardrail 允许或拒绝次数")
    .setUnit("{decision}")
    .build()

  private val retrievalCount = meter
    .counterBuilder("zyblw.agent.retrieval.count")
    .setDescription("RAG 检索操作次数")
    .setUnit("{operation}")
    .build()

  private val retrievalDuration = durationHistogram("zyblw.agent.retrieval.duration", "RAG 检索操作耗时")

  private val retrievalHits = meter
    .histogramBuilder("zyblw.agent.retrieval.hit.count")
    .ofLongs()
    .setDescription("单次检索返回的授权后知识片段数")
    .setUnit("{hit}")
    .setExplicitBucketBoundariesAdvice(longBuckets(0L, 1L, 2L, 4L, 8L, 16L, 32L, 64L))
    .build()

  private val memoryCount = meter
    .counterBuilder("zyblw.agent.memory.operation.count")
    .setDescription("长期记忆生命周期操作次数")
    .setUnit("{operation}")
    .build()

  private val memoryDuration = durationHistogram("zyblw.agent.memory.operation.duration", "长期记忆操作耗时")

  private val workerCommandCount = meter
    .counterBuilder("zyblw.agent.worker.command.count")
    .setDescription("Durable Worker 命令处理次数")
    .setUnit("{command}")
    .build()

  private val workerCommandDuration =
    durationHistogram("zyblw.agent.worker.command.duration", "Durable Worker 命令处理耗时")

  private val leaseCount = meter
    .counterBuilder("zyblw.agent.worker.lease.operation.count")
    .setDescription("Worker lease claim、heartbeat、release 或 reclaim 次数")
    .setUnit("{operation}")
    .build()

  private val evaluationCount = meter
    .counterBuilder("zyblw.agent.evaluation.count")
    .setDescription("框架写入的数值评测结果次数")
    .setUnit("{evaluation}")
    .build()

  private val evaluationScore = meter
    .histogramBuilder("zyblw.agent.evaluation.score")
    .setDescription("同名评测器产生的数值分数；不同 evaluator 不应直接混合解释")
    .setUnit("1")
    .build()

  private val contextBuildCount = meter
    .counterBuilder("zyblw.agent.context.build.count")
    .setDescription("模型调用前完成的 Context 构建次数")
    .setUnit("{build}")
    .build()

  private val contextEstimatedTokens = meter
    .histogramBuilder("zyblw.agent.context.estimated.token.count")
    .ofLongs()
    .setDescription("ContextManager 估算的最终输入 token")
    .setUnit("{token}")
    .setExplicitBucketBoundariesAdvice(longBuckets(1000L, 4000L, 8000L, 16000L, 32000L, 64000L, 128000L))
    .build()

  private val contextDroppedItems = meter
    .histogramBuilder("zyblw.agent.context.dropped.item.count")
    .ofLongs()
    .setDescription("单次 Context 构建因预算、重复或压缩影响的条目数")
    .setUnit("{item}")
    .setExplicitBucketBoundariesAdvice(longBuckets(0L, 1L, 2L, 4L, 8L, 16L, 32L, 64L))
    .build()

  private val contextRotSignalCount = meter
    .counterBuilder("zyblw.agent.context.rot.signal.count")
    .setDescription("ContextManager 发现的低敏 Context Rot 诊断信号总数")
    .setUnit("{signal}")
    .build()

  private val contextCompressionCount = meter
    .counterBuilder("zyblw.agent.context.compression.count")
    .setDescription("已持久化的 Context 摘要 checkpoint 次数")
    .setUnit("{compression}")
    .build()

  private val contextCompressionModelCalls = meter
    .counterBuilder("zyblw.agent.context.compression.model.call.count")
    .setDescription("Context 压缩器实际发起的辅助模型调用数")
    .setUnit("{call}")
    .build()

  private val contextCompressionCoveredMessages = meter
    .histogramBuilder("zyblw.agent.context.compression.covered.message.count")
    .ofLongs()
    .setDescription("一次耐久 Context 摘要覆盖的消息前缀长度")
    .setUnit("{message}")
    .setExplicitBucketBoundariesAdvice(longBuckets(1L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L))
    .build()

  /** 将一个类型化事实同步记录到 OpenTelemetry SDK。
    *
    * SDK 的 `record/add` 不进行网络 IO；PeriodicMetricReader 在后台批量导出，所以此方法不会让模型或工具等待 collector。
    */
  def record(metric: AgentMetric): UIO[Unit] = ZIO.succeed {
    metric match
      case AgentMetric.ActiveRuns(delta) => activeRuns.add(delta)

      case AgentMetric.RunFinished(outcome, durationSeconds, cost) =>
        val attributes = outcomeAttributes(outcome)
        runCount.add(1L, attributes)
        recordDuration(runDuration, durationSeconds, attributes)
        if cost.isFinite && cost > 0.0 then estimatedCost.add(cost, attributes)

      case AgentMetric.ModelCallFinished(
            provider,
            model,
            outcome,
            durationSeconds,
            inputTokens,
            outputTokens,
            cachedInputTokens,
            reasoningOutputTokens
          ) =>
        val attributes = modelAttributes(provider, model, outcome)
        modelCallCount.add(1L, attributes)
        recordDuration(modelDuration, durationSeconds, attributes)
        recordTokens(inputTokens, TokenDirection.Input, attributes)
        recordTokens(outputTokens, TokenDirection.Output, attributes)
        if cachedInputTokens > 0L then cachedInputTokenUsage.record(cachedInputTokens, attributes)
        if reasoningOutputTokens > 0L then reasoningOutputTokenUsage.record(reasoningOutputTokens, attributes)

      case AgentMetric.ToolCallFinished(toolName, risk, outcome, durationSeconds) =>
        val builder = Attributes
          .builder()
          .put("agent.outcome", outcome.label)
          .put("agent.tool.risk", bounded(risk, riskValues))
        attributePolicy.tool(toolName).foreach(value => builder.put("agent.tool.name", value))
        val attributes = builder.build()
        toolCallCount.add(1L, attributes)
        recordDuration(toolDuration, durationSeconds, attributes)

      case AgentMetric.GuardrailEvaluated(stage, allowed) =>
        guardrailCount.add(
          1L,
          Attributes
            .builder()
            .put("agent.guardrail.stage", bounded(stage, guardrailStages))
            .put("agent.guardrail.allowed", allowed)
            .build()
        )

      case AgentMetric.ContextPrepared(
            estimatedTokens,
            droppedMessages,
            truncatedToolResults,
            droppedMemories,
            droppedRetrieval,
            rotSignalCount
          ) =>
        contextBuildCount.add(1L)
        contextEstimatedTokens.record(estimatedTokens.max(0L))
        val affected = droppedMessages.max(0L) + truncatedToolResults.max(0L) +
          droppedMemories.max(0L) + droppedRetrieval.max(0L)
        contextDroppedItems.record(affected)
        // Rot 是一次诊断事实，不等于被丢弃的 Context 条目。使用无 label counter 可避免任意 code 形成高基数维度。
        if rotSignalCount > 0L then contextRotSignalCount.add(rotSignalCount)

      case AgentMetric.ContextCompacted(coveredMessages, modelCalls, inputTokens, outputTokens) =>
        contextCompressionCount.add(1L)
        contextCompressionCoveredMessages.record(coveredMessages.max(0L))
        if modelCalls > 0L then contextCompressionModelCalls.add(modelCalls)
        // 不添加 Provider/model label；压缩器可能是路由模型，低基数归一化应由专门 Trace 完成。
        recordTokens(inputTokens, TokenDirection.Input, Attributes.empty())
        recordTokens(outputTokens, TokenDirection.Output, Attributes.empty())

      case AgentMetric.RetrievalFinished(operation, outcome, durationSeconds, hitCount) =>
        val attributes =
          operationAttributes("agent.retrieval.operation", operation, retrievalOperations, outcome)
        retrievalCount.add(1L, attributes)
        recordDuration(retrievalDuration, durationSeconds, attributes)
        retrievalHits.record(hitCount.max(0L), attributes)

      case AgentMetric.MemoryOperationFinished(operation, outcome, durationSeconds) =>
        val attributes = operationAttributes("agent.memory.operation", operation, memoryOperations, outcome)
        memoryCount.add(1L, attributes)
        recordDuration(memoryDuration, durationSeconds, attributes)

      case AgentMetric.WorkerCommandFinished(command, outcome, durationSeconds) =>
        val attributes = operationAttributes("agent.worker.command", command, workerCommands, outcome)
        workerCommandCount.add(1L, attributes)
        recordDuration(workerCommandDuration, durationSeconds, attributes)

      case AgentMetric.LeaseOperationFinished(action, outcome) =>
        leaseCount.add(1L, operationAttributes("agent.worker.lease.action", action, leaseActions, outcome))

      case AgentMetric.EvaluationRecorded(evaluator, score, passed) =>
        if score.isFinite then
          val attributes = Attributes
            .builder()
            .put("agent.evaluation.name", attributePolicy.evaluator(evaluator))
            .put("agent.evaluation.passed", passed)
            .build()
          evaluationCount.add(1L, attributes)
          evaluationScore.record(score, attributes)
  }

  /** 创建框架自定义的秒级 duration histogram，并统一常用 buckets。 */
  private def durationHistogram(name: String, description: String): DoubleHistogram =
    meter
      .histogramBuilder(name)
      .setDescription(description)
      .setUnit("s")
      .setExplicitBucketBoundariesAdvice(JavaList.of(0.001, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 30.0, 120.0))
      .build()

  /** Scala Long 显式装箱为 Java Long，满足 OpenTelemetry Java API 的泛型边界。 */
  private def longBuckets(values: Long*): java.util.List[java.lang.Long] =
    values.iterator.map(java.lang.Long.valueOf).toList.asJava

  /** Provider 归一化为固定 family，模型名经过 allow-list。 */
  private def modelAttributes(provider: String, model: String, outcome: MetricOutcome): Attributes =
    Attributes
      .builder()
      .put("gen_ai.operation.name", "chat")
      .put("gen_ai.provider.name", providerFamily(provider))
      .put("gen_ai.request.model", attributePolicy.model(model))
      .put("agent.outcome", outcome.label)
      .build()

  /** 为 token histogram 追加标准 input/output 维度；零值不记录，避免制造无意义样本。 */
  private def recordTokens(value: Long, direction: TokenDirection, base: Attributes): Unit =
    if value > 0L then
      tokenUsage.record(value, base.toBuilder.put("gen_ai.token.type", direction.label).build())

  /** 只记录有限、非负 duration。 */
  private def recordDuration(
      instrument: DoubleHistogram,
      value: Option[Double],
      attributes: Attributes
  ): Unit =
    value.filter(number => number.isFinite && number >= 0.0).foreach(instrument.record(_, attributes))

  private def outcomeAttributes(outcome: MetricOutcome): Attributes =
    Attributes.builder().put("agent.outcome", outcome.label).build()

  private def operationAttributes(
      key: String,
      value: String,
      allowed: Set[String],
      outcome: MetricOutcome
  ): Attributes =
    Attributes.builder().put(key, bounded(value, allowed)).put("agent.outcome", outcome.label).build()

  /** 未知扩展值统一为 other，阻断任意插件字符串成为时间序列维度。 */
  private def bounded(value: String, allowed: Set[String]): String =
    val normalized = value.trim.toLowerCase
    if allowed.contains(normalized) then normalized else "other"

  /** 将具体 Provider/别名压缩为有限 family。 */
  private def providerFamily(value: String): String =
    val normalized = value.trim.toLowerCase
    if normalized.contains("anthropic") || normalized.contains("claude") then "anthropic"
    else if normalized.contains("gemini") || normalized.contains("google") || normalized.contains("vertex")
    then "gcp.gen_ai"
    else if normalized.contains("deepseek") then "deepseek"
    else if normalized.contains("zhipu") || normalized.contains("glm") then "zhipu"
    else if normalized.contains("azure") then "azure.ai.openai"
    else if normalized.contains("openrouter") then "openrouter"
    else if normalized.contains("ollama") then "ollama"
    else if normalized.contains("openai") then "openai"
    else if normalized == "local" then "local"
    else "other"

  private val riskValues =
    Set("readonly", "userscopedread", "draftwrite", "approvalwrite", "adminapproval", "unknown")
  private val guardrailStages     = Set("input", "output", "tool", "retrieval", "other")
  private val retrievalOperations = Set("retrieve", "rerank", "hybrid_search", "embed", "index", "other")
  private val memoryOperations    =
    Set("capture", "extract", "search", "list", "upsert", "delete", "purge", "other")
  private val workerCommands = Set("submit", "approve", "reject", "cancel", "retry", "resume", "other")
  private val leaseActions   = Set("claim", "heartbeat", "release", "reclaim", "other")

object OpenTelemetryAgentMetrics:
  /** 用宿主提供的 Meter 创建 recorder，不管理 MeterProvider 生命周期。 */
  def make(meter: Meter, policy: MetricAttributePolicy = MetricAttributePolicy()): AgentMetrics =
    OpenTelemetryAgentMetrics(meter, policy)
