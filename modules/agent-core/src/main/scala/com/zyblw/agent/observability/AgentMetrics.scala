package com.zyblw.agent.observability

import zio.*

/** 指标记录的有限结果集合。
  *
  * 指标标签必须是可聚合、低基数的枚举值。这里故意不接受任意错误文本：错误详情属于 Trace/日志， 若把异常消息放入 Metrics label，会同时造成隐私泄漏和时间序列爆炸。
  */
enum MetricOutcome:
  case Succeeded, Failed, Cancelled, Suspended, Rejected, TimedOut, Conflict

  /** OpenTelemetry/Prometheus 中使用的稳定小写值。 */
  def label: String = productPrefix.toLowerCase

/** Token 的方向；值与 OpenTelemetry GenAI `gen_ai.token.type` 约定保持一致。 */
enum TokenDirection:
  case Input, Output

  /** @return `input` 或 `output`，可直接作为低基数指标维度。 */
  def label: String = productPrefix.toLowerCase

/** 框架允许记录的指标事实。
  *
  * 这是 Metrics 的稳定契约，而不是“字符串指标名 + 任意 Map”。封闭 ADT 有三个目的：
  *
  *   1. 编译器保证每个 exporter 都显式处理新增指标；
  *   2. 调用方无法偷偷把 runId、sessionId、用户输入或工具参数塞进 label；
  *   3. 测试可以对业务语义断言，而不绑定某个监控厂商。
  *
  * 所有 duration 单位均为秒；缺少可靠开始事件时传 `None`，此时仍记录调用次数，但不伪造 0 秒延迟。
  */
enum AgentMetric:
  /** 进程内活跃 Run 数变化；Run 开始为 `+1`，进入任何终态为 `-1`。 */
  case ActiveRuns(delta: Long)

  /** 一次 Run 进入终态。
    *
    * @param outcome
    *   固定结果分类
    * @param durationSeconds
    *   从 RunCreated/RunStarted 到终态的耗时；开始事件缺失时为 None
    * @param estimatedCostUsd
    *   Runtime 根据 Provider 价格表估算的美元费用；未知时传 0
    */
  case RunFinished(outcome: MetricOutcome, durationSeconds: Option[Double], estimatedCostUsd: Double)

  /** 一次模型调用结束。
    *
    * @param provider
    *   Provider 标识；exporter 会归一化为有限 provider family
    * @param model
    *   实际请求的模型名；默认策略不会直接把未知模型名作为 label
    * @param outcome
    *   调用结果
    * @param durationSeconds
    *   模型调用耗时
    * @param inputTokens
    *   Provider 明确返回的输入 token；不得离线猜测后冒充真实 usage
    * @param outputTokens
    *   Provider 明确返回的输出 token
    * @param cachedInputTokens
    *   输入中由 Prompt Cache 命中的 token 子集
    * @param reasoningOutputTokens
    *   输出中用于内部推理的 token 子集；只记录计数，不记录推理正文
    */
  case ModelCallFinished(
      provider: String,
      model: String,
      outcome: MetricOutcome,
      durationSeconds: Option[Double],
      inputTokens: Long,
      outputTokens: Long,
      cachedInputTokens: Long = 0L,
      reasoningOutputTokens: Long = 0L
  )

  /** 一次工具执行结束。
    *
    * @param toolName
    *   注册工具名；只有进入 allow-list 时 exporter 才把它作为 label
    * @param risk
    *   工具风险等级的稳定字符串
    * @param outcome
    *   工具结果
    * @param durationSeconds
    *   从 ToolExecutionStarted 到结束的耗时
    */
  case ToolCallFinished(
      toolName: String,
      risk: String,
      outcome: MetricOutcome,
      durationSeconds: Option[Double]
  )

  /** 一次 Guardrail 判定；stage 必须是框架定义的有限阶段名。 */
  case GuardrailEvaluated(stage: String, allowed: Boolean)

  /** 一次模型调用前完成的 Context 构建事实。
    *
    * 所有字段都是非负计数，不接受任意 label 或正文。Rot signal 的具体 code 进入 Trace；Metrics 只记录数量，防止未来插件 自定义 code 导致时间序列基数增长。
    */
  case ContextPrepared(
      estimatedTokens: Long,
      droppedMessages: Long,
      truncatedToolResults: Long,
      droppedMemories: Long,
      droppedRetrieval: Long,
      rotSignalCount: Long
  )

  /** 一次 Context 摘要 checkpoint 已持久化。
    *
    * @param coveredMessages
    *   摘要覆盖的消息前缀长度
    * @param modelCalls
    *   本次新发生的辅助模型调用数；复用 checkpoint 时不会发出本指标
    * @param inputTokens
    *   压缩模型输入 token
    * @param outputTokens
    *   压缩模型输出 token
    */
  case ContextCompacted(
      coveredMessages: Long,
      modelCalls: Long,
      inputTokens: Long,
      outputTokens: Long
  )

  /** 一次检索结束；operation 应使用 `retrieve`、`rerank`、`hybrid_search` 等有限操作名。 */
  case RetrievalFinished(
      operation: String,
      outcome: MetricOutcome,
      durationSeconds: Option[Double],
      hitCount: Long
  )

  /** 一次长期记忆操作结束；operation 应使用 `capture`、`search`、`upsert`、`delete` 等有限值。 */
  case MemoryOperationFinished(operation: String, outcome: MetricOutcome, durationSeconds: Option[Double])

  /** Worker command 处理结束；command 是 Submit/Approve/Cancel/Retry 等有限命令类型。 */
  case WorkerCommandFinished(command: String, outcome: MetricOutcome, durationSeconds: Option[Double])

  /** Lease 动作结果；action 是 claim/heartbeat/release/reclaim 等有限集合。 */
  case LeaseOperationFinished(action: String, outcome: MetricOutcome)

  /** 一条数值评测结果。
    *
    * @param evaluator
    *   评测器稳定名；exporter 仅允许 allow-list 值成为 label
    * @param score
    *   有限数值，量纲由同名 evaluator 预先约定
    * @param passed
    *   是否通过该评测器的业务门槛
    */
  case EvaluationRecorded(evaluator: String, score: Double, passed: Boolean)

/** 指标出口 SPI。
  *
  * 方法返回 `UIO` 是刻意的：监控后端故障不能改变 AgentState、工具副作用或命令提交结果。真正 exporter 的 失败由 OpenTelemetry SDK 自监控和日志报告；生产审计仍以
  * RunStore/EventStore 为准。
  */
trait AgentMetrics:
  /** @param metric 已通过封闭 ADT 限制字段的指标事实 */
  def record(metric: AgentMetric): UIO[Unit]

object AgentMetrics:
  /** 未启用 Metrics 时的零成本实现。 */
  val noop: ULayer[AgentMetrics] = ZLayer.succeed((_: AgentMetric) => ZIO.unit)

  /** 从环境记录一个指标，便于 RAG、Memory、Worker 等模块在业务 effect 周围组合。 */
  def record(metric: AgentMetric): URIO[AgentMetrics, Unit] =
    ZIO.serviceWithZIO[AgentMetrics](_.record(metric))

/** 测试用内存指标出口；它保存类型化事实，不模拟任何厂商聚合行为。 */
final class InMemoryAgentMetrics private (ref: Ref[Chunk[AgentMetric]]) extends AgentMetrics:
  /** 追加一条指标事实。 */
  def record(metric: AgentMetric): UIO[Unit] = ref.update(_ :+ metric)

  /** @return 按记录顺序返回全部指标，方便断言确定性事件轨迹。 */
  def recorded: UIO[Chunk[AgentMetric]] = ref.get

object InMemoryAgentMetrics:
  /** 创建隔离的内存 recorder；每次提供 Layer 都从空集合开始。 */
  val layer: ULayer[InMemoryAgentMetrics] =
    ZLayer.fromZIO(Ref.make(Chunk.empty[AgentMetric]).map(InMemoryAgentMetrics(_)))
