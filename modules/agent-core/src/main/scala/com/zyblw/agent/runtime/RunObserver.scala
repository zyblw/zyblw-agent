package com.zyblw.agent.runtime

// 运行观察者消费统一 AgentEvent；它是非关键观测扩展点，失败不得反向破坏 Agent 的业务结果。

import com.zyblw.agent.core.*
import com.zyblw.agent.observability.*
import zio.*
import zio.stream.*

trait RunObserver:
  /** 接收已经过安全边界筛选的运行事件。
    * @param event
    *   与 SSE 使用同一类型的领域事件；实现不得在此阻塞主运行循环
    */
  def emit(event: AgentEvent): UIO[Unit]

object RunObserver:
  /** 不启用实时事件或外部遥测时使用的零成本实现。 */
  val noop: ULayer[RunObserver] = ZLayer.succeed((_: AgentEvent) => ZIO.unit)

  /** 构建进程内实时事件 Hub。
    *
    * `Hub.sliding` 在慢订阅者落后时保留最新 1024 项，不允许 SSE 客户端反向阻塞 Durable Runtime。 需要断线续传、审计或恢复时必须读取
    * `RunStore.events`，不能依赖本 Hub。
    */
  val hub: ULayer[RunObserver & RunEventStream] =
    ZLayer.fromZIOEnvironment {
      Hub.sliding[AgentEvent](1024).map { eventHub =>
        val observer = new RunObserver:
          def emit(event: AgentEvent): UIO[Unit] = eventHub.publish(event).unit
        val stream = new RunEventStream:
          def events: ZStream[Any, Nothing, AgentEvent] = ZStream.fromHub(eventHub)
        ZEnvironment[RunObserver](observer) ++ ZEnvironment[RunEventStream](stream)
      }
    }

  /** 合并多个观察者，例如同时输出 SSE Hub 与 OpenTelemetry。
    *
    * @param observers
    *   已完成各自缓冲/脱敏配置的观察者
    * @return
    *   按注册顺序发送事件的观察者；任何单个实现都只能返回 UIO，不能破坏主循环
    */
  def combine(observers: RunObserver*): RunObserver =
    (event: AgentEvent) => ZIO.foreachDiscard(observers)(_.emit(event))

trait RunEventStream:
  /** 订阅进程内全部运行的实时事件；审计与断线续传应改用 `RunStore.events`。 */
  def events: ZStream[Any, Nothing, AgentEvent]

/** 把 Durable Runtime 的 `AgentEvent` 转换为厂商无关 `TelemetryEvent`。
  *
  * 这是 Runtime 与 OpenTelemetry/Langfuse 之间唯一的桥：它明确列出允许离开业务边界的字段， 不输出模型文本
  * delta、最终答案、工具参数、工具结果、审批原因或错误详情。即便下游错误配置为 “记录全部 attribute”，这些正文也从未进入遥测对象。
  *
  * @param telemetry
  *   通常应是 `BufferedTelemetry` 外加 `SanitizingTelemetry(MetadataOnly)`
  */
final class TelemetryRunObserver private (
    telemetry: AgentTelemetry,
    state: Ref.Synchronized[TelemetryRunObserver.State]
) extends RunObserver:
  /** 安全事件被归一化后输出；开始/结束事件会先在内存中配对成真实 duration observation。 模型正文、工具参数/结果和错误详情从未进入投影状态。
    */
  def emit(event: AgentEvent): UIO[Unit] =
    state.modify(current => TelemetryRunObserver.project(current, event)).flatMap {
      case Some(safe) => telemetry.emit(safe)
      case None       => ZIO.unit
    }

object TelemetryRunObserver:
  /** 从已配置的 AgentTelemetry 构造 Runtime observer。 */
  val layer: ZLayer[AgentTelemetry, Nothing, RunObserver] =
    ZLayer.fromZIO {
      for
        telemetry <- ZIO.service[AgentTelemetry]
        state     <- Ref.Synchronized.make(State())
      yield TelemetryRunObserver(telemetry, state)
    }

  /** 测试或组合 Trace + Metrics observer 时直接创建带独立配对状态的实例。 */
  def make(telemetry: AgentTelemetry): UIO[TelemetryRunObserver] =
    Ref.Synchronized.make(State()).map(TelemetryRunObserver(telemetry, _))

  /** Run 的安全 Trace 关联信息；不保存用户输入或最终答案。 */
  final private case class RunTrace(sessionId: SessionId, startedAtEpochMilli: Long)

  /** 模型 observation 只保存 Provider、模型名与开始时间。 */
  final private case class ModelTrace(provider: String, model: String, startedAtEpochMilli: Long)

  /** 工具 observation 保存工具名、风险和真实执行开始时间，不保存 arguments/result。 */
  final private case class ToolTrace(toolName: String, risk: String, startedAtEpochMilli: Option[Long])

  /** 非耐久 Trace 配对状态；Run 终态后立即清除。 */
  final private case class State(
      runs: Map[RunId, RunTrace] = Map.empty,
      models: Map[RunId, ModelTrace] = Map.empty,
      tools: Map[(RunId, String), ToolTrace] = Map.empty
  )

  /** 把开始/结束事件组合为 Langfuse/OTel 能理解的真实 observation。 若观察者热重启导致开始事件缺失，则回退到无 duration 的安全纯投影，不伪造延迟。
    */
  private def project(current: State, event: AgentEvent): (Option[TelemetryEvent], State) =
    event match
      case AgentEvent.RunCreated(runId, sessionId, at) =>
        None -> current.copy(runs = current.runs.updated(runId, RunTrace(sessionId, at)))

      case AgentEvent.ModelCallStarted(runId, provider, model, at) =>
        None -> current.copy(models = current.models.updated(runId, ModelTrace(provider, model, at)))

      case AgentEvent.ModelCallCompleted(runId, usage, at) =>
        current.models.get(runId) match
          case Some(started) =>
            val event = durationEvent(
              runId,
              "agent.model.call",
              started.startedAtEpochMilli,
              at,
              Map(
                "agent.provider"            -> started.provider,
                "agent.model"               -> started.model,
                "gen_ai.operation.name"     -> "chat",
                "gen_ai.provider.name"      -> providerFamily(started.provider),
                "gen_ai.request.model"      -> started.model,
                "langfuse.observation.type" -> "generation"
              ),
              Map(
                "gen_ai.usage.input_tokens"  -> usage.inputTokens.toDouble,
                "gen_ai.usage.output_tokens" -> usage.outputTokens.toDouble
              )
            )
            Some(event) -> current.copy(models = current.models.removed(runId))
          case None => toTelemetry(event) -> current

      case AgentEvent.ToolCallRequested(runId, call, _) =>
        val key      = runId -> call.id
        val previous = current.tools.getOrElse(key, ToolTrace(call.name, "unknown", None))
        None -> current.copy(tools = current.tools.updated(key, previous.copy(toolName = call.name)))

      case AgentEvent.ToolApprovalRequired(runId, approval, _) =>
        val key      = runId -> approval.toolCall.id
        val previous = current.tools.getOrElse(key, ToolTrace(approval.toolCall.name, "unknown", None))
        val next = previous.copy(toolName = approval.toolCall.name, risk = approval.risk.toString.toLowerCase)
        // 审批本身是值得观察的离散事件，但审批原因和工具参数不会进入 attributes。
        toTelemetry(event) -> current.copy(tools = current.tools.updated(key, next))

      case AgentEvent.ToolExecutionStarted(runId, callId, at) =>
        val key      = runId -> callId
        val previous = current.tools.getOrElse(key, ToolTrace("unknown", "unknown", None))
        None -> current.copy(tools =
          current.tools.updated(key, previous.copy(startedAtEpochMilli = Some(at)))
        )

      case AgentEvent.ToolExecutionCompleted(runId, callId, result, at) =>
        finishToolTrace(current, runId, callId, at, Map("agent.tool.is_error" -> result.isError.toString))

      case AgentEvent.ToolExecutionFailed(runId, callId, category, at) =>
        finishToolTrace(
          current,
          runId,
          callId,
          at,
          Map("agent.error.category" -> category, "langfuse.observation.level" -> "ERROR")
        )

      case AgentEvent.RunSuspended(runId, _, at) =>
        val projected = current.runs
          .get(runId)
          .map(run => runEvent(runId, run, "suspended", at, Map.empty))
          .orElse(toTelemetry(event))
        projected -> current

      case AgentEvent.RunCompleted(runId, _, usage, at) =>
        finishRunTrace(current, runId, "completed", at, usageMeasurements(usage))

      case AgentEvent.RunFailed(runId, category, _, at) =>
        finishRunTrace(
          current,
          runId,
          "failed",
          at,
          Map.empty,
          Map("agent.error.category" -> category, "langfuse.observation.level" -> "ERROR")
        )

      case AgentEvent.RunCancelled(runId, at) =>
        finishRunTrace(current, runId, "cancelled", at, Map.empty)

      case _ => toTelemetry(event) -> current

  /** 完成工具 observation；开始事件缺失时仍输出安全离散事件。 */
  private def finishToolTrace(
      current: State,
      runId: RunId,
      callId: String,
      at: Long,
      extra: Map[String, String]
  ): (Option[TelemetryEvent], State) =
    val key       = runId -> callId
    val trace     = current.tools.get(key)
    val projected = trace match
      case Some(value) =>
        val attributes = Map(
          "agent.tool.name"           -> value.toolName,
          "agent.tool.risk"           -> value.risk,
          "langfuse.observation.type" -> "tool"
        ) ++ extra
        value.startedAtEpochMilli
          .map(startedAt => durationEvent(runId, "agent.tool.execute", startedAt, at, attributes))
          .orElse(some(runId, "agent.tool.execute", at, attributes))
      case None =>
        some(
          runId,
          "agent.tool.execute",
          at,
          Map(
            "agent.tool.name"           -> "unknown",
            "agent.tool.risk"           -> "unknown",
            "langfuse.observation.type" -> "tool"
          ) ++ extra
        )
    projected -> current.copy(tools = current.tools.removed(key))

  /** 完成 Run observation 并清理全部关联，防止常驻 Worker 的内存随历史 Run 增长。 */
  private def finishRunTrace(
      current: State,
      runId: RunId,
      status: String,
      at: Long,
      measurements: Map[String, Double],
      extra: Map[String, String] = Map.empty
  ): (Option[TelemetryEvent], State) =
    val projected = current.runs.get(runId).map(run => runEvent(runId, run, status, at, measurements, extra))
    val next      = current.copy(
      runs = current.runs.removed(runId),
      models = current.models.removed(runId),
      tools = current.tools.filterNot(_._1._1 == runId)
    )
    projected -> next

  /** 构造 Langfuse Agent 类型的 Run duration；session 用于跨多次 Run 的会话聚合。 */
  private def runEvent(
      runId: RunId,
      run: RunTrace,
      status: String,
      at: Long,
      measurements: Map[String, Double],
      extra: Map[String, String] = Map.empty
  ): TelemetryEvent =
    durationEvent(
      runId,
      "agent.run",
      run.startedAtEpochMilli,
      at,
      Map(
        "agent.status"              -> status,
        "langfuse.session.id"       -> run.sessionId.asString,
        "langfuse.observation.type" -> "agent"
      ) ++ extra,
      measurements
    )

  /** 创建持续时间事件并统一追加 trace 名称和事件名。 */
  private def durationEvent(
      runId: RunId,
      name: String,
      startedAt: Long,
      endedAt: Long,
      attributes: Map[String, String],
      measurements: Map[String, Double] = Map.empty
  ): TelemetryEvent =
    TelemetryEvent(
      name = name,
      runId = Some(runId),
      traceId = Some(runId.asString.replace("-", "")),
      attributes = attributes ++ Map("agent.event" -> name, "langfuse.trace.name" -> "zyblw-agent-run"),
      measurements = measurements,
      atEpochMilli = endedAt,
      startedAtEpochMilli = Option.when(startedAt <= endedAt)(startedAt)
    )

  /** Trace 维度使用稳定 Provider family；原始 provider 仍作为诊断 attribute 保留。 */
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
    else "other"

  /** 将领域事件投影成低基数、无正文的遥测事件。
    *
    * `traceId` 使用 Run UUID 的 32 位十六进制形式。OpenTelemetry Adapter 会把它作为远程父 trace， 因此跨 Fiber、经过异步队列的事件仍可在同一个 trace
    * 下展示。
    */
  private[runtime] def toTelemetry(event: AgentEvent): Option[TelemetryEvent] =
    event match
      case AgentEvent.ModelTextDelta(_, _, _) | AgentEvent.ModelToolCallDelta(_, _, _, _) => None
      case AgentEvent.RunCreated(runId, sessionId, at)                                    =>
        some(
          runId,
          "agent.run.created",
          at,
          Map(
            "agent.status"              -> "created",
            "langfuse.session.id"       -> sessionId.asString,
            "langfuse.observation.type" -> "agent"
          )
        )
      case AgentEvent.RunStarted(runId, at) =>
        some(runId, "agent.run.started", at, Map("agent.status" -> "running"))
      case AgentEvent.RunResumed(runId, at) =>
        some(runId, "agent.run.resumed", at, Map("agent.status" -> "running"))
      case AgentEvent.StepStarted(runId, step, at) =>
        some(runId, "agent.step.started", at, Map("agent.step" -> step.toString))
      case AgentEvent.ContextPrepared(
            runId,
            estimatedTokens,
            droppedMessages,
            truncatedToolResults,
            droppedMemories,
            droppedRetrieval,
            rotSignalCodes,
            at
          ) =>
        val safeRotCodes =
          rotSignalCodes.map(code => if contextRotCodes(code) then code else "other").distinct.sorted
        some(
          runId,
          "agent.context.prepared",
          at,
          Map(
            "agent.context.rot.present" -> safeRotCodes.nonEmpty.toString,
            "agent.context.rot.codes"   -> safeRotCodes.mkString(","),
            "langfuse.observation.type" -> "span"
          ),
          Map(
            "agent.context.estimated_tokens"       -> estimatedTokens.max(0L).toDouble,
            "agent.context.dropped_messages"       -> droppedMessages.max(0).toDouble,
            "agent.context.truncated_tool_results" -> truncatedToolResults.max(0).toDouble,
            "agent.context.dropped_memories"       -> droppedMemories.max(0).toDouble,
            "agent.context.dropped_retrieval"      -> droppedRetrieval.max(0).toDouble,
            "agent.context.rot_signal_count"       -> safeRotCodes.size.toDouble
          )
        )
      case AgentEvent.ContextCompacted(runId, coveredMessages, modelCalls, usage, compressorVersion, at) =>
        some(
          runId,
          "agent.context.compacted",
          at,
          Map(
            "agent.context.compressor.version" -> compressorVersion,
            "langfuse.observation.type"        -> "span"
          ),
          Map(
            "agent.context.covered_messages"        -> coveredMessages.max(0).toDouble,
            "agent.context.compression.model_calls" -> modelCalls.max(0).toDouble,
            "gen_ai.usage.input_tokens"             -> usage.inputTokens.max(0L).toDouble,
            "gen_ai.usage.output_tokens"            -> usage.outputTokens.max(0L).toDouble
          )
        )
      case AgentEvent.ModelCallStarted(runId, provider, model, at) =>
        some(
          runId,
          "agent.model.started",
          at,
          Map(
            "agent.provider"            -> provider,
            "agent.model"               -> model,
            "gen_ai.operation.name"     -> "chat",
            "gen_ai.provider.name"      -> providerFamily(provider),
            "gen_ai.request.model"      -> model,
            "langfuse.observation.type" -> "generation"
          )
        )
      case AgentEvent.ModelCallCompleted(runId, usage, at) =>
        some(
          runId,
          "agent.model.completed",
          at,
          measurements = Map(
            "gen_ai.usage.input_tokens"  -> usage.inputTokens.toDouble,
            "gen_ai.usage.output_tokens" -> usage.outputTokens.toDouble
          )
        )
      case AgentEvent.ToolCallRequested(runId, call, at) =>
        some(
          runId,
          "agent.tool.requested",
          at,
          Map(
            "agent.tool.name"           -> call.name,
            "agent.tool.call.id"        -> call.id,
            "langfuse.observation.type" -> "tool"
          )
        )
      case AgentEvent.ToolBatchPlanned(runId, planId, batchCount, callCount, at) =>
        some(
          runId,
          "agent.tool.batch.planned",
          at,
          Map("agent.tool.plan.id"     -> planId),
          Map("agent.tool.batch.count" -> batchCount.toDouble, "agent.tool.call.count" -> callCount.toDouble)
        )
      case AgentEvent.ToolBatchStarted(runId, planId, batchIndex, callCount, at) =>
        some(
          runId,
          "agent.tool.batch.started",
          at,
          Map("agent.tool.plan.id"    -> planId, "agent.tool.batch.index" -> batchIndex.toString),
          Map("agent.tool.call.count" -> callCount.toDouble)
        )
      case AgentEvent.ToolBatchCommitted(runId, planId, batchIndex, callCount, at) =>
        some(
          runId,
          "agent.tool.batch.committed",
          at,
          Map("agent.tool.plan.id"    -> planId, "agent.tool.batch.index" -> batchIndex.toString),
          Map("agent.tool.call.count" -> callCount.toDouble)
        )
      case AgentEvent.ToolApprovalRequired(runId, approval, at) =>
        some(
          runId,
          "agent.tool.approval.required",
          at,
          Map(
            "agent.tool.name"           -> approval.toolCall.name,
            "agent.tool.risk"           -> approval.risk.toString,
            "langfuse.observation.type" -> "tool"
          )
        )
      case AgentEvent.ToolExecutionStarted(runId, callId, at) =>
        some(
          runId,
          "agent.tool.started",
          at,
          Map("agent.tool.call.id" -> callId, "langfuse.observation.type" -> "tool")
        )
      case AgentEvent.ToolExecutionCompleted(runId, callId, result, at) =>
        some(
          runId,
          "agent.tool.completed",
          at,
          Map(
            "agent.tool.call.id"        -> callId,
            "agent.tool.is_error"       -> result.isError.toString,
            "langfuse.observation.type" -> "tool"
          )
        )
      case AgentEvent.ToolExecutionFailed(runId, callId, category, at) =>
        some(
          runId,
          "agent.tool.failed",
          at,
          Map(
            "agent.tool.call.id"         -> callId,
            "agent.error.category"       -> category,
            "langfuse.observation.type"  -> "tool",
            "langfuse.observation.level" -> "ERROR"
          )
        )
      case AgentEvent.GuardrailEvaluated(runId, stage, allowed, at) =>
        some(
          runId,
          "agent.guardrail.evaluated",
          at,
          Map(
            "agent.guardrail.stage"     -> stage,
            "agent.guardrail.allowed"   -> allowed.toString,
            "langfuse.observation.type" -> "guardrail"
          )
        )
      case AgentEvent.UsageUpdated(runId, usage, at) =>
        some(runId, "agent.usage.updated", at, measurements = usageMeasurements(usage))
      case AgentEvent.CheckpointSaved(runId, version, at) =>
        some(runId, "agent.checkpoint.saved", at, Map("agent.checkpoint.version" -> version.value.toString))
      case AgentEvent.RunSuspended(runId, _, at) =>
        some(runId, "agent.run.suspended", at, Map("agent.status" -> "suspended"))
      case AgentEvent.RunCompleted(runId, _, usage, at) =>
        some(
          runId,
          "agent.run.completed",
          at,
          Map("agent.status" -> "completed", "langfuse.observation.type" -> "agent"),
          usageMeasurements(usage)
        )
      case AgentEvent.RunFailed(runId, category, _, at) =>
        some(
          runId,
          "agent.run.failed",
          at,
          Map(
            "agent.status"               -> "failed",
            "agent.error.category"       -> category,
            "langfuse.observation.type"  -> "agent",
            "langfuse.observation.level" -> "ERROR"
          )
        )
      case AgentEvent.RunCancelled(runId, at) =>
        some(
          runId,
          "agent.run.cancelled",
          at,
          Map("agent.status" -> "cancelled", "langfuse.observation.type" -> "agent")
        )

  /** Context Rot Trace 属性允许的固定 code；未知插件值统一折叠为 `other`。 */
  private val contextRotCodes = Set(
    "context-input-near-limit",
    "context-history-heavy-drop",
    "context-tool-output-truncated",
    "context-memory-dropped",
    "context-retrieval-dropped",
    "context-duplicate-source"
  )

  /** 构造统一事件并追加 Langfuse trace 名称；不接受任意正文参数。 */
  private def some(
      runId: RunId,
      name: String,
      atEpochMilli: Long,
      attributes: Map[String, String] = Map.empty,
      measurements: Map[String, Double] = Map.empty
  ): Option[TelemetryEvent] =
    Some(
      TelemetryEvent(
        name = name,
        runId = Some(runId),
        traceId = Some(runId.asString.replace("-", "")),
        attributes = attributes ++ Map("agent.event" -> name, "langfuse.trace.name" -> "zyblw-agent-run"),
        measurements = measurements,
        atEpochMilli = atEpochMilli
      )
    )

  /** 将 Run 级资源消耗转换为数值 attributes，便于 dashboard 做聚合和预算告警。 */
  private def usageMeasurements(usage: UsageSummary): Map[String, Double] =
    Map(
      "agent.usage.model_calls"             -> usage.modelCalls.toDouble,
      "agent.usage.tool_calls"              -> usage.toolCalls.toDouble,
      "gen_ai.usage.input_tokens"           -> usage.inputTokens.toDouble,
      "gen_ai.usage.output_tokens"          -> usage.outputTokens.toDouble,
      "agent.usage.cached_input_tokens"     -> usage.cachedInputTokens.toDouble,
      "agent.usage.reasoning_output_tokens" -> usage.reasoningOutputTokens.toDouble,
      "agent.usage.estimated_cost"          -> usage.estimatedCost.toDouble
    )

/** 把 Runtime 事件配对为低基数、可聚合的生产指标。
  *
  * Trace 适合回答“某一次 Run 发生了什么”，Metrics 适合回答“整个服务是否健康”。因此本观察者只把开始时间、 Provider/模型和工具风险暂存在进程内
  * `Ref.Synchronized`，绝不保存 prompt、工具参数或结果。进程崩溃时这些 临时计时可以丢失：RunStore 仍负责事实恢复，而监控系统不应被误当作业务状态库。
  *
  * @param metrics
  *   类型化指标出口；通常为 OpenTelemetry，也可以在测试中使用 InMemoryAgentMetrics
  * @param state
  *   每个 Run 当前的非耐久计时状态
  */
final class MetricsRunObserver private (
    metrics: AgentMetrics,
    state: Ref.Synchronized[MetricsRunObserver.State]
) extends RunObserver:

  /** 原子地投影一个事件，再按确定性顺序写指标出口。
    *
    * 同一 Run 的模型调用在主 loop 中顺序发生；工具可以并行，因此工具计时按 `(runId, callId)` 隔离。 输出是 `UIO`，exporter 失败不能反向改变 Agent 运行结果。
    */
  def emit(event: AgentEvent): UIO[Unit] =
    state
      .modify(current => MetricsRunObserver.project(current, event))
      .flatMap(points => ZIO.foreachDiscard(points)(metrics.record))

object MetricsRunObserver:
  /** Run 的首次创建时间及当前是否计入 active gauge。 */
  final private case class RunTiming(startedAtEpochMilli: Long, active: Boolean)

  /** 模型调用开始时冻结 provider/model，避免完成事件缺少这些字段。 */
  final private case class ModelTiming(provider: String, model: String, startedAtEpochMilli: Long)

  /** 工具名、风险与真实执行开始时间；审批等待时间不计入工具执行延迟。 */
  final private case class ToolTiming(toolName: String, risk: String, startedAtEpochMilli: Option[Long])

  /** 观察者的全部非耐久状态。
    *
    * Map key 使用领域 ID 只服务本进程事件关联；这些 ID 从不进入 Metrics label。
    */
  final private case class State(
      runs: Map[RunId, RunTiming] = Map.empty,
      models: Map[RunId, ModelTiming] = Map.empty,
      tools: Map[(RunId, String), ToolTiming] = Map.empty
  )

  /** 从 AgentMetrics 环境构造可并发使用的观察者。 */
  val layer: ZLayer[AgentMetrics, Nothing, RunObserver] =
    ZLayer.fromZIO {
      for
        metrics <- ZIO.service[AgentMetrics]
        state   <- Ref.Synchronized.make(State())
      yield MetricsRunObserver(metrics, state)
    }

  /** 测试或手工组装时直接创建实例。 */
  def make(metrics: AgentMetrics): UIO[MetricsRunObserver] =
    Ref.Synchronized.make(State()).map(MetricsRunObserver(metrics, _))

  /** 纯投影函数：返回“要记录的指标 + 新状态”。纯函数使乱序、缺失事件和终态清理可以确定性测试。
    */
  private def project(current: State, event: AgentEvent): (Chunk[AgentMetric], State) =
    event match
      case AgentEvent.RunCreated(runId, _, at) =>
        Chunk.empty -> current.copy(runs = current.runs.updated(runId, RunTiming(at, active = false)))

      case AgentEvent.RunStarted(runId, at) => activate(current, runId, at)
      case AgentEvent.RunResumed(runId, at) => activate(current, runId, at)

      case AgentEvent.ModelCallStarted(runId, provider, model, at) =>
        Chunk.empty -> current.copy(models = current.models.updated(runId, ModelTiming(provider, model, at)))

      case AgentEvent.ModelCallCompleted(runId, usage, at) =>
        current.models.get(runId) match
          case Some(started) =>
            val point = AgentMetric.ModelCallFinished(
              provider = started.provider,
              model = started.model,
              outcome = MetricOutcome.Succeeded,
              durationSeconds = elapsed(started.startedAtEpochMilli, at),
              inputTokens = usage.inputTokens.max(0L),
              outputTokens = usage.outputTokens.max(0L),
              cachedInputTokens = usage.cachedInputTokens.max(0L),
              reasoningOutputTokens = usage.reasoningOutputTokens.max(0L)
            )
            Chunk(point) -> current.copy(models = current.models.removed(runId))
          case None =>
            // 完成事件可能来自断线续传或观察者热重启；次数仍应记录，但不能伪造 provider/model 与耗时。
            val point = AgentMetric.ModelCallFinished(
              provider = "unknown",
              model = "unknown",
              outcome = MetricOutcome.Succeeded,
              durationSeconds = None,
              inputTokens = usage.inputTokens.max(0L),
              outputTokens = usage.outputTokens.max(0L),
              cachedInputTokens = usage.cachedInputTokens.max(0L),
              reasoningOutputTokens = usage.reasoningOutputTokens.max(0L)
            )
            Chunk(point) -> current

      case AgentEvent.ToolCallRequested(runId, call, _) =>
        val key      = runId -> call.id
        val previous = current.tools.getOrElse(key, ToolTiming(call.name, "unknown", None))
        Chunk.empty -> current.copy(tools = current.tools.updated(key, previous.copy(toolName = call.name)))

      case AgentEvent.ToolApprovalRequired(runId, approval, _) =>
        val key      = runId -> approval.toolCall.id
        val previous = current.tools.getOrElse(key, ToolTiming(approval.toolCall.name, "unknown", None))
        Chunk.empty -> current.copy(
          tools = current.tools.updated(
            key,
            previous.copy(toolName = approval.toolCall.name, risk = approval.risk.toString.toLowerCase)
          )
        )

      case AgentEvent.ToolExecutionStarted(runId, callId, at) =>
        val key      = runId -> callId
        val previous = current.tools.getOrElse(key, ToolTiming("unknown", "unknown", None))
        Chunk.empty -> current.copy(tools =
          current.tools.updated(key, previous.copy(startedAtEpochMilli = Some(at)))
        )

      case AgentEvent.ToolExecutionCompleted(runId, callId, result, at) =>
        finishTool(
          current,
          runId,
          callId,
          if result.isError then MetricOutcome.Failed else MetricOutcome.Succeeded,
          at
        )

      case AgentEvent.ToolExecutionFailed(runId, callId, category, at) =>
        finishTool(current, runId, callId, outcomeFromCategory(category), at)

      case AgentEvent.GuardrailEvaluated(_, stage, allowed, _) =>
        Chunk(AgentMetric.GuardrailEvaluated(normalizeStage(stage), allowed)) -> current

      case AgentEvent.ContextPrepared(
            _,
            estimatedTokens,
            droppedMessages,
            truncatedToolResults,
            droppedMemories,
            droppedRetrieval,
            rotSignalCodes,
            _
          ) =>
        Chunk(
          AgentMetric.ContextPrepared(
            estimatedTokens.max(0L),
            droppedMessages.max(0).toLong,
            truncatedToolResults.max(0).toLong,
            droppedMemories.max(0).toLong,
            droppedRetrieval.max(0).toLong,
            rotSignalCodes.size.toLong
          )
        ) -> current

      case AgentEvent.ContextCompacted(_, coveredMessages, modelCalls, usage, _, _) =>
        Chunk(
          AgentMetric.ContextCompacted(
            coveredMessages.max(0).toLong,
            modelCalls.max(0).toLong,
            usage.inputTokens.max(0L),
            usage.outputTokens.max(0L)
          )
        ) -> current

      case AgentEvent.RunSuspended(runId, _, _) => deactivate(current, runId)

      case AgentEvent.RunCompleted(runId, _, usage, at) =>
        finishRun(current, runId, MetricOutcome.Succeeded, at, usage.estimatedCost.toDouble)

      case AgentEvent.RunFailed(runId, category, _, at) =>
        finishRun(current, runId, outcomeFromCategory(category), at, 0.0)

      case AgentEvent.RunCancelled(runId, at) =>
        finishRun(current, runId, MetricOutcome.Cancelled, at, 0.0)

      case _ => Chunk.empty -> current

  /** Run 首次进入执行态时增加 active；重复 Started/Resumed 不重复计数。 */
  private def activate(current: State, runId: RunId, at: Long): (Chunk[AgentMetric], State) =
    current.runs.get(runId) match
      case Some(timing) if timing.active => Chunk.empty -> current
      case Some(timing)                  =>
        Chunk(AgentMetric.ActiveRuns(1L)) -> current.copy(runs =
          current.runs.updated(runId, timing.copy(active = true))
        )
      case None =>
        Chunk(AgentMetric.ActiveRuns(1L)) -> current.copy(runs =
          current.runs.updated(runId, RunTiming(at, active = true))
        )

  /** 暂停时只移出 active gauge，保留首次开始时间供最终 wall-clock duration 使用。 */
  private def deactivate(current: State, runId: RunId): (Chunk[AgentMetric], State) =
    current.runs.get(runId) match
      case Some(timing) if timing.active =>
        Chunk(AgentMetric.ActiveRuns(-1L)) -> current.copy(runs =
          current.runs.updated(runId, timing.copy(active = false))
        )
      case _ => Chunk.empty -> current

  /** 完成一个工具并清理 callId 关联，避免长生命周期 Worker 内存增长。 */
  private def finishTool(
      current: State,
      runId: RunId,
      callId: String,
      outcome: MetricOutcome,
      at: Long
  ): (Chunk[AgentMetric], State) =
    val key    = runId -> callId
    val timing = current.tools.getOrElse(key, ToolTiming("unknown", "unknown", None))
    val point  = AgentMetric.ToolCallFinished(
      timing.toolName,
      timing.risk,
      outcome,
      timing.startedAtEpochMilli.flatMap(elapsed(_, at))
    )
    Chunk(point) -> current.copy(tools = current.tools.removed(key))

  /** Run 进入终态时同时结束悬空模型/工具计时并删除所有关联状态。
    *
    * SIGKILL 无法产生终态事件，因此不能承诺每个进程指标绝对配对；分布式恢复的事实仍由 lease 与 RunStore 负责。
    */
  private def finishRun(
      current: State,
      runId: RunId,
      outcome: MetricOutcome,
      at: Long,
      estimatedCostUsd: Double
  ): (Chunk[AgentMetric], State) =
    val runTiming   = current.runs.get(runId)
    val activePoint = Chunk.fromIterable(runTiming.filter(_.active).map(_ => AgentMetric.ActiveRuns(-1L)))
    val runPoint    = AgentMetric.RunFinished(
      outcome,
      runTiming.flatMap(timing => elapsed(timing.startedAtEpochMilli, at)),
      if estimatedCostUsd.isFinite && estimatedCostUsd > 0.0 then estimatedCostUsd else 0.0
    )
    val modelPoint = current.models
      .get(runId)
      .map(started =>
        AgentMetric.ModelCallFinished(
          started.provider,
          started.model,
          outcome,
          elapsed(started.startedAtEpochMilli, at),
          inputTokens = 0L,
          outputTokens = 0L,
          cachedInputTokens = 0L,
          reasoningOutputTokens = 0L
        )
      )
    val toolPoints = current.tools.iterator.collect {
      case ((toolRunId, _), timing) if toolRunId == runId =>
        AgentMetric.ToolCallFinished(
          timing.toolName,
          timing.risk,
          outcome,
          timing.startedAtEpochMilli.flatMap(elapsed(_, at))
        )
    }.toVector
    val next = current.copy(
      runs = current.runs.removed(runId),
      models = current.models.removed(runId),
      tools = current.tools.filterNot(_._1._1 == runId)
    )
    (activePoint ++ Chunk.fromIterable(modelPoint) ++ Chunk.fromIterable(toolPoints) :+ runPoint) -> next

  /** 只接受非负、有限耗时；时钟倒退或损坏事件返回 None。 */
  private def elapsed(startedAtEpochMilli: Long, endedAtEpochMilli: Long): Option[Double] =
    Option.when(endedAtEpochMilli >= startedAtEpochMilli)(
      (endedAtEpochMilli - startedAtEpochMilli).toDouble / 1000.0
    )

  /** 把任意错误分类压缩为固定 outcome，原始 category 只进入安全 Trace。 */
  private def outcomeFromCategory(category: String): MetricOutcome =
    category.trim.toLowerCase match
      case value if value.contains("timeout") || value.contains("timed_out") => MetricOutcome.TimedOut
      case value if value.contains("cancel")                                 => MetricOutcome.Cancelled
      case value if value.contains("reject") || value.contains("denied")     => MetricOutcome.Rejected
      case value if value.contains("conflict")                               => MetricOutcome.Conflict
      case _                                                                 => MetricOutcome.Failed

  /** Guardrail stage 采用固定白名单，未知插件阶段统一聚合为 other。 */
  private def normalizeStage(stage: String): String =
    stage.trim.toLowerCase match
      case "input" | "model_input"   => "input"
      case "output" | "model_output" => "output"
      case "tool" | "tool_call"      => "tool"
      case "retrieval" | "rag"       => "retrieval"
      case _                         => "other"

/** 生产环境推荐的统一观察者 Layer。
  *
  * 宿主只需提供同一套 OpenTelemetry SDK 产生的 `AgentTelemetry & AgentMetrics`，本 Layer 就会创建各自独立、 并发安全的事件配对状态，并按 Trace 后
  * Metrics 的固定顺序输出。两者都是 `UIO`，任何观测故障都不会改变主循环。
  */
object ObservabilityRunObserver:
  /** @return 同时输出持续时间 Trace/Langfuse observation 与低基数 Metrics 的单一 RunObserver */
  val layer: ZLayer[AgentTelemetry & AgentMetrics, Nothing, RunObserver] =
    ZLayer.fromZIO {
      for
        telemetry      <- ZIO.service[AgentTelemetry]
        metrics        <- ZIO.service[AgentMetrics]
        traceObserver  <- TelemetryRunObserver.make(telemetry)
        metricObserver <- MetricsRunObserver.make(metrics)
      yield RunObserver.combine(traceObserver, metricObserver)
    }
