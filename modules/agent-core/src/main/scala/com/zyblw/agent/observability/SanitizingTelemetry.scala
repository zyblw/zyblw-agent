package com.zyblw.agent.observability

import com.zyblw.agent.core.*
import zio.*

/** 在所有 exporter 之前执行的遥测安全边界。
  *
  * 设计重点是“字段名决定正文风险”，而不是依赖脆弱的正则去猜一段中文是否包含病历、姓名或处方。 生产推荐
  * `MetadataOnly`：正文类字段直接移除；Provider、模型、耗时、token、状态等运行元数据保留。
  *
  * @param underlying
  *   真正写 Console、OpenTelemetry、Langfuse 或其他后端的 sink
  * @param policy
  *   正文记录策略
  * @param redactor
  *   凭据/PII 脱敏器；`Redacted` 模式若允许正文，宿主应提供领域实现
  */
final class SanitizingTelemetry(
    underlying: AgentTelemetry,
    policy: ContentRecordingPolicy,
    redactor: Redactor
) extends AgentTelemetry:
  /** 对 attributes 脱敏后再发给 exporter；事件名、ID 和数值指标不包含业务正文。 */
  def emit(event: TelemetryEvent): UIO[Unit] =
    sanitize(event.attributes).flatMap(attributes => underlying.emit(event.copy(attributes = attributes)))

  /** 与离散事件使用同一安全规则，避免 span wrapper 成为绕过脱敏的后门。 */
  def span[R, E, A](name: String, attributes: Map[String, String])(
      effect: ZIO[R, E, A]
  ): ZIO[R, E, A] =
    sanitize(attributes).flatMap(safe => underlying.span(name, safe)(effect))

  /** 按策略过滤/转换所有字段；`ZIO.foreach` 保持不可变 Map 和确定性行为。 */
  private def sanitize(attributes: Map[String, String]): UIO[Map[String, String]] =
    ZIO
      .foreach(attributes.toList) { case (key, value) =>
        val normalized = key.toLowerCase.replace('-', '.').replace('_', '.')
        if isSecretKey(normalized) then ZIO.succeed(Some(key -> "<redacted>"))
        else if isContentKey(normalized) then
          policy match
            case ContentRecordingPolicy.Disabled     => ZIO.none
            case ContentRecordingPolicy.MetadataOnly => ZIO.none
            case ContentRecordingPolicy.Redacted     =>
              redactor.redact(value).map(redacted => Some(key -> redacted))
        else if policy == ContentRecordingPolicy.Disabled && !operationalKeys.contains(normalized) then
          ZIO.none
        else redactor.redact(value).map(redacted => Some(key -> redacted))
      }
      .map(_.flatten.toMap)

  /** 正文类字段永远按显式 policy 处理，不尝试根据 value 猜测敏感度。 */
  private def isContentKey(key: String): Boolean =
    contentFragments.exists(fragment =>
      key == fragment || key.endsWith(s".$fragment") || key.contains(s".$fragment.")
    )

  /** 凭据字段无论选择哪种正文策略都固定替换，不把部分密钥暴露给 exporter。 */
  private def isSecretKey(key: String): Boolean =
    secretFragments.exists(fragment =>
      key == fragment || key.endsWith(s".$fragment") || key.contains(s".$fragment.")
    )

  private val contentFragments = Set(
    "prompt",
    "input",
    "output",
    "content",
    "message",
    "messages",
    "arguments",
    "result",
    "answer",
    "body",
    "query",
    "document"
  )

  private val secretFragments = Set(
    "authorization",
    "api.key",
    "apikey",
    "password",
    "secret",
    "token",
    "cookie",
    "set.cookie"
  )

  /** Disabled 模式仍允许这些低风险字段支撑最小运行监控。 */
  private val operationalKeys = Set(
    "agent.run.id",
    "agent.event",
    "agent.status",
    "agent.step",
    "agent.provider",
    "agent.model",
    "agent.tool.name",
    "agent.error.category",
    "langfuse.session.id",
    "langfuse.trace.name",
    "langfuse.observation.type",
    "langfuse.observation.level",
    "gen.ai.operation.name",
    "gen.ai.provider.name",
    "gen.ai.request.model"
  )

object SanitizingTelemetry:
  /** 包装一个 exporter。
    *
    * @param underlying
    *   原始 sink
    * @param policy
    *   正文策略，生产建议 `MetadataOnly`
    * @param redactor
    *   自定义脱敏器
    */
  def make(
      underlying: AgentTelemetry,
      policy: ContentRecordingPolicy,
      redactor: Redactor
  ): AgentTelemetry = SanitizingTelemetry(underlying, policy, redactor)

  /** 从 ZLayer 环境读取 exporter 与 Redactor，输出已经过安全边界包装的 AgentTelemetry。 */
  def layer(policy: ContentRecordingPolicy): ZLayer[AgentTelemetry & Redactor, Nothing, AgentTelemetry] =
    ZLayer.fromFunction((telemetry: AgentTelemetry, redactor: Redactor) => make(telemetry, policy, redactor))
