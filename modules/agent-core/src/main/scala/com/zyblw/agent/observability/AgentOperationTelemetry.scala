package com.zyblw.agent.observability

import com.zyblw.agent.core.*
import java.util.concurrent.TimeUnit
import zio.*

/** RAG、Memory、Worker 和 Eval 的统一语义观测器。
  *
  * 这些操作并不都由 `AgentEvent` 主循环直接产生，若各模块自行拼接字符串 span，字段和安全边界很快会漂移。本类 提供少量固定入口：调用方只给操作名、结果数量或分数，query、文档、记忆正文、命令
  * payload 均没有参数入口。
  *
  * @param telemetry
  *   Trace/Langfuse 出口
  * @param metrics
  *   低基数指标出口
  */
final class AgentOperationTelemetry(
    telemetry: AgentTelemetry,
    metrics: AgentMetrics
):

  /** 观测一次知识检索，并同时生成 Langfuse retriever observation 与 retrieval Metrics。
    *
    * @param runId
    *   归属的 Agent Run；用于 trace 聚合，不进入 metric label
    * @param operation
    *   有限操作名，例如 retrieve/hybrid_search/rerank
    * @param effect
    *   真正检索 effect；错误和缺陷保持原样返回
    * @param hitCount
    *   成功结果的授权后命中数提取函数，不能读取或记录正文
    */
  def retrieval[R, E, A](
      runId: RunId,
      operation: String
  )(effect: ZIO[R, E, A])(hitCount: A => Long): ZIO[R, E, A] =
    observe(runId, "agent.retrieval", "retriever", "agent.retrieval.operation", boundedRetrieval(operation))(
      effect
    ) {
      case Exit.Success(value) =>
        AgentMetric.RetrievalFinished(
          boundedRetrieval(operation),
          MetricOutcome.Succeeded,
          None,
          hitCount(value).max(0L)
        )
      case Exit.Failure(cause) =>
        AgentMetric.RetrievalFinished(boundedRetrieval(operation), outcome(cause), None, 0L)
    }

  /** 观测一次长期记忆生命周期操作。
    *
    * @param runId
    *   触发记忆操作的 Run
    * @param operation
    *   capture/extract/search/upsert/delete/purge 等有限值
    * @param effect
    *   保持原错误类型和取消语义的业务 effect
    */
  def memory[R, E, A](runId: RunId, operation: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    observe(runId, "agent.memory", "span", "agent.memory.operation", boundedMemory(operation))(effect) {
      case Exit.Success(_) =>
        AgentMetric.MemoryOperationFinished(boundedMemory(operation), MetricOutcome.Succeeded, None)
      case Exit.Failure(cause) =>
        AgentMetric.MemoryOperationFinished(boundedMemory(operation), outcome(cause), None)
    }

  /** 观测 Durable Worker 的一条控制命令。
    *
    * @param runId
    *   命令关联 Run
    * @param command
    *   submit/approve/reject/cancel/retry/resume
    * @param effect
    *   命令处理 effect
    */
  def workerCommand[R, E, A](runId: RunId, command: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    observe(runId, "agent.worker.command", "span", "agent.worker.command", boundedCommand(command))(effect) {
      case Exit.Success(_) =>
        AgentMetric.WorkerCommandFinished(boundedCommand(command), MetricOutcome.Succeeded, None)
      case Exit.Failure(cause) =>
        AgentMetric.WorkerCommandFinished(boundedCommand(command), outcome(cause), None)
    }

  /** 记录一个不含评语正文的数值评测 observation。
    *
    * 该方法会生成 Langfuse `evaluator` observation 和 OTel Metrics，但不会伪装成 Langfuse Score 对象。若要让分数 进入 Langfuse Scores
    * 数据模型，宿主还需调用官方 Scores API，并使用稳定幂等 score id。
    *
    * @param runId
    *   被评测 Run
    * @param evaluator
    *   稳定评测器名，例如 citation_correctness_v1
    * @param score
    *   有限数值
    * @param passed
    *   是否通过当前发布门槛
    */
  def evaluation(runId: RunId, evaluator: String, score: Double, passed: Boolean): UIO[Unit] =
    if !score.isFinite then ZIO.logWarning("忽略非有限 Agent evaluation score")
    else
      val safeName = normalizeName(evaluator)
      val event    = TelemetryEvent(
        name = "agent.evaluation",
        runId = Some(runId),
        traceId = Some(runId.asString.replace("-", "")),
        attributes = Map(
          "agent.event"               -> "agent.evaluation",
          "agent.evaluation.name"     -> safeName,
          "agent.evaluation.passed"   -> passed.toString,
          "langfuse.trace.name"       -> "zyblw-agent-run",
          "langfuse.observation.type" -> "evaluator"
        ),
        measurements = Map("agent.evaluation.score" -> score),
        atEpochMilli = 0L
      )
      // evaluation 常发生在离线测试，没有业务时钟要求；使用 Clock 填入真实事件时间。
      Clock
        .currentTime(TimeUnit.MILLISECONDS)
        .flatMap(at =>
          telemetry.emit(event.copy(atEpochMilli = at)) *>
            metrics.record(AgentMetric.EvaluationRecorded(safeName, score, passed))
        )

  /** 在可中断业务 effect 外建立极短的不可中断收尾区，保证取消/缺陷也能记录结果，然后用 ZIO.done 原样恢复 Exit。
    */
  private def observe[R, E, A](
      runId: RunId,
      spanName: String,
      observationType: String,
      operationKey: String,
      operation: String
  )(
      effect: ZIO[R, E, A]
  )(
      metric: Exit[E, A] => AgentMetric
  ): ZIO[R, E, A] =
    ZIO.uninterruptibleMask { restore =>
      for
        started <- Clock.currentTime(TimeUnit.MILLISECONDS)
        exit    <- restore(effect).exit
        ended   <- Clock.currentTime(TimeUnit.MILLISECONDS)
        duration = Option.when(ended >= started)((ended - started).toDouble / 1000.0)
        point    = withDuration(metric(exit), duration)
        trace    = TelemetryEvent(
          name = spanName,
          runId = Some(runId),
          traceId = Some(runId.asString.replace("-", "")),
          attributes = Map(
            "agent.event"               -> spanName,
            operationKey                -> operation,
            "agent.outcome"             -> outcome(exit).label,
            "langfuse.trace.name"       -> "zyblw-agent-run",
            "langfuse.observation.type" -> observationType
          ),
          atEpochMilli = ended,
          startedAtEpochMilli = Option.when(ended >= started)(started)
        )
        _      <- telemetry.emit(trace)
        _      <- metrics.record(point)
        result <- exit match
          case Exit.Success(value) => ZIO.succeed(value)
          case Exit.Failure(cause) => ZIO.failCause(cause)
      yield result
    }

  /** 把统一测得的 duration 写回不同类型指标，避免调用方自行计时。 */
  private def withDuration(metric: AgentMetric, duration: Option[Double]): AgentMetric = metric match
    case value: AgentMetric.RetrievalFinished       => value.copy(durationSeconds = duration)
    case value: AgentMetric.MemoryOperationFinished => value.copy(durationSeconds = duration)
    case value: AgentMetric.WorkerCommandFinished   => value.copy(durationSeconds = duration)
    case other                                      => other

  private def outcome[E](exit: Exit[E, Any]): MetricOutcome = exit match
    case Exit.Success(_)     => MetricOutcome.Succeeded
    case Exit.Failure(cause) => outcome(cause)

  private def outcome[E](cause: Cause[E]): MetricOutcome =
    if cause.isInterrupted then MetricOutcome.Cancelled else MetricOutcome.Failed

  private def boundedRetrieval(value: String): String =
    bounded(value, Set("retrieve", "rerank", "hybrid_search", "embed", "index"))
  private def boundedMemory(value: String): String =
    bounded(value, Set("capture", "extract", "search", "list", "upsert", "delete", "purge"))
  private def boundedCommand(value: String): String =
    bounded(value, Set("submit", "approve", "reject", "cancel", "retry", "resume"))

  /** 任意插件操作统一折叠为 other；原始字符串不会进入 trace 或 metric。 */
  private def bounded(value: String, allowed: Set[String]): String =
    val normalized = value.trim.toLowerCase
    if allowed.contains(normalized) then normalized else "other"

  /** 评测器名只允许 ASCII 标识符并限制长度，防止评语或病历被误传成名称。 */
  private def normalizeName(value: String): String =
    val normalized = value.trim.toLowerCase.replaceAll("[^a-z0-9._-]", "_").take(80)
    if normalized.nonEmpty then normalized else "other"

object AgentOperationTelemetry:
  /** 从同一环境取得 Trace 与 Metrics 出口。 */
  val layer: URLayer[AgentTelemetry & AgentMetrics, AgentOperationTelemetry] =
    ZLayer.fromFunction(AgentOperationTelemetry.apply)
