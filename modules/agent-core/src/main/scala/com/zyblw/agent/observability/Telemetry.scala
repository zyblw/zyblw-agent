package com.zyblw.agent.observability

import com.zyblw.agent.core.*
import io.opentelemetry.api.trace.{Span, SpanContext, StatusCode, TraceFlags, TraceState, Tracer}
import io.opentelemetry.context.Context
import java.util.concurrent.TimeUnit
import zio.*
import zio.json.*

/** 遥测正文策略。生产默认应选 `MetadataOnly`，医疗、隐私或多租户业务尤其如此。
  *
  *   - `Disabled`：只保留 allow-list 中的框架运行字段。
  *   - `MetadataOnly`：丢弃正文类字段，同时对其余字段执行凭据脱敏。
  *   - `Redacted`：允许自定义 Redactor 脱敏后的正文进入遥测；默认 Redactor 只适合开发环境。
  */
enum ContentRecordingPolicy:
  case Disabled, MetadataOnly, Redacted

/** 把任意字符串转换为可安全输出的形式；实现不得失败，以免观测故障影响业务运行。 */
trait Redactor:
  /** @param value 可能含凭据或业务正文的原值；@return 已脱敏的新字符串 */
  def redact(value: String): UIO[String]

object Redactor:
  /** 默认凭据脱敏器。
    *
    * 它覆盖 Bearer、常见 key/value 凭据、OpenAI/Anthropic 风格密钥、Google API Key 和 JWT。 它不是姓名、手机号、病历等 PII
    * 识别器；需要记录正文时，宿主必须注入自己的领域 Redactor。
    */
  val default: ULayer[Redactor] =
    ZLayer.succeed(new Redactor:
      private val patterns = List(
        "(?i)bearer\\s+[a-z0-9._-]+".r,
        "(?i)(api[_-]?key|authorization|password|secret)\\s*[\"']?\\s*[:=]\\s*[\"']?[^\\s,\"']+".r,
        "(?i)sk-(ant-)?[a-z0-9_-]{12,}".r,
        "AIza[0-9A-Za-z_-]{20,}".r,
        "[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}".r
      )
      def redact(value: String): UIO[String] =
        ZIO.succeed(
          patterns.foldLeft(value)((current, pattern) => pattern.replaceAllIn(current, "<redacted>"))
        ))

/** 厂商无关遥测接口；审计事件应写 RunStore，不能只依赖本接口。 */
trait AgentTelemetry:
  def emit(event: TelemetryEvent): UIO[Unit]
  def span[R, E, A](name: String, attributes: Map[String, String])(effect: ZIO[R, E, A]): ZIO[R, E, A]

object AgentTelemetry:
  val noop: ULayer[AgentTelemetry] = ZLayer.succeed(new AgentTelemetry:
    def emit(event: TelemetryEvent): UIO[Unit] = ZIO.unit
    def span[R, E, A](name: String, attributes: Map[String, String])(effect: ZIO[R, E, A]): ZIO[R, E, A] =
      effect)

  val console: ULayer[AgentTelemetry] = ZLayer.succeed(new AgentTelemetry:
    def emit(event: TelemetryEvent): UIO[Unit] = ZIO.logInfo(event.toJson)
    def span[R, E, A](name: String, attributes: Map[String, String])(effect: ZIO[R, E, A]): ZIO[R, E, A] =
      attributes.foldLeft(ZIO.logDebug(s"span.start $name") *> effect <* ZIO.logDebug(s"span.end $name")) {
        case (current, (key, value)) => ZIO.logAnnotate(key, value)(current)
      })

final class InMemoryTelemetry private (ref: Ref[Chunk[TelemetryEvent]]) extends AgentTelemetry:
  def emit(event: TelemetryEvent): UIO[Unit] = ref.update(_ :+ event)
  def events: UIO[Chunk[TelemetryEvent]]     = ref.get
  def span[R, E, A](name: String, attributes: Map[String, String])(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    effect

object InMemoryTelemetry:
  val layer: ULayer[InMemoryTelemetry] =
    ZLayer.fromZIO(Ref.make(Chunk.empty[TelemetryEvent]).map(InMemoryTelemetry(_)))

/** 使用 OpenTelemetry API 输出 Agent span。
  *
  * SDK 与 exporter 由可选 `agent-observability-otlp` 模块提供，基础模块因此不会强迫所有业务项目 引入 OTLP 网络客户端。若
  * `TelemetryEvent.traceId` 是 32 位十六进制字符串，本实现用它创建同一 远程父 trace，使异步 BufferedTelemetry 中同一 Run 的事件仍能在
  * Langfuse/Jaeger 中聚合。
  *
  * @param tracer
  *   由宿主或 OTLP 模块创建的 OpenTelemetry Tracer
  */
final class OpenTelemetryAgentTelemetry(tracer: Tracer) extends AgentTelemetry:
  /** 把一个离散框架事件输出为瞬时 span；原始 prompt、answer 与工具结果不得出现在 attributes。 */
  def emit(event: TelemetryEvent): UIO[Unit] =
    ZIO.succeed {
      val builder = event.traceId.flatMap(parentContext).fold(tracer.spanBuilder(event.name)) { parent =>
        tracer.spanBuilder(event.name).setParent(parent)
      }
      val startedAt = event.startedAtEpochMilli.filter(_ <= event.atEpochMilli).getOrElse(event.atEpochMilli)
      val span      = builder.setStartTimestamp(startedAt, TimeUnit.MILLISECONDS).startSpan()
      event.runId.foreach(runId => span.setAttribute("agent.run.id", runId.asString))
      event.traceId.foreach(traceId => span.setAttribute("agent.trace.id", traceId))
      event.attributes.foreach((key, value) => span.setAttribute(key, value))
      event.measurements.foreach((key, value) => span.setAttribute(key, value))
      span.end(event.atEpochMilli, TimeUnit.MILLISECONDS)
    }

  /** 在一个真实 ZIO effect 生命周期外包裹 span，成功/失败通过 `Exit` 标记状态，Scope 保证上下文关闭。
    * @param name
    *   span 名称
    * @param attributes
    *   不含正文和凭据的低基数字段
    * @param effect
    *   被观测的业务 effect
    */
  def span[R, E, A](name: String, attributes: Map[String, String])(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(ZIO.succeed {
          val span = tracer.spanBuilder(name).startSpan()
          attributes.foreach((key, value) => span.setAttribute(key, value))
          span -> span.makeCurrent()
        }) { case (_, scope) => ZIO.succeed(scope.close()) }
        .flatMap { case (otelSpan, _) =>
          effect
            .onExit {
              case Exit.Success(_) => ZIO.succeed(otelSpan.setStatus(StatusCode.OK)).unit
              case Exit.Failure(_) => ZIO.succeed(otelSpan.setStatus(StatusCode.ERROR)).unit
            }
            .ensuring(ZIO.succeed(otelSpan.end()))
        }
    }

  /** 把框架 traceId 转换为 OpenTelemetry 远程父上下文；非法 ID 回退为新 trace。 */
  private def parentContext(traceId: String): Option[Context] =
    val normalized = traceId.replace("-", "").toLowerCase
    Option.when(normalized.matches("[0-9a-f]{32}") && normalized.exists(_ != '0')) {
      val spanContext = SpanContext.createFromRemoteParent(
        normalized,
        "0000000000000001",
        TraceFlags.getSampled,
        TraceState.getDefault
      )
      Context.root().`with`(Span.wrap(spanContext))
    }

object OpenTelemetryAgentTelemetry:
  def layer(tracer: Tracer): ULayer[AgentTelemetry] = ZLayer.succeed(OpenTelemetryAgentTelemetry(tracer))

enum TelemetryOverflowPolicy:
  case BackPressure, DropNewest, DropOldest

/** 有界异步遥测。worker 使用 `forkScoped`，随 Layer Scope 结束，不产生 daemon fiber。
  */
object BufferedTelemetry:
  def layer(
      capacity: Int,
      overflow: TelemetryOverflowPolicy,
      sink: AgentTelemetry
  ): ULayer[AgentTelemetry] =
    ZLayer.scoped {
      for
        queue <- overflow match
          case TelemetryOverflowPolicy.BackPressure => Queue.bounded[TelemetryEvent](capacity)
          case TelemetryOverflowPolicy.DropNewest   => Queue.dropping[TelemetryEvent](capacity)
          case TelemetryOverflowPolicy.DropOldest   => Queue.sliding[TelemetryEvent](capacity)
        _ <- queue.take.flatMap(sink.emit).forever.forkScoped
      yield new AgentTelemetry:
        def emit(event: TelemetryEvent): UIO[Unit] = queue.offer(event).unit
        def span[R, E, A](name: String, attributes: Map[String, String])(effect: ZIO[R, E, A]): ZIO[R, E, A] =
          sink.span(name, attributes)(effect)
    }
