package com.zyblw.agent.guardrails

import com.zyblw.agent.core.*
import zio.*

enum GuardrailMode:
  case Blocking, Monitoring

enum GuardrailFailurePolicy:
  case FailClosed, FailOpen

final case class GuardrailDecision(
    allowed: Boolean,
    reason: Option[String] = None,
    tags: Set[String] = Set.empty,
    redactions: Map[String, String] = Map.empty
)

final case class GuardrailContext(runId: RunId, runContext: RunContext, agentId: AgentId)

trait InputGuardrail:
  /** 规则名称，用于事件、指标和错误定位。 */
  def name: String

  /** 在模型调用前评估用户输入。 */
  def evaluate(message: AgentMessage, context: GuardrailContext): IO[GuardrailError, GuardrailDecision]

trait OutputGuardrail:
  /** 输出规则名称。 */
  def name: String

  /** 在最终答案返回用户前评估助手消息。 */
  def evaluate(message: AgentMessage, context: GuardrailContext): IO[GuardrailError, GuardrailDecision]

trait ToolGuardrail:
  /** 工具规则名称。 */
  def name: String

  /** 在执行前（result=None）或执行后（result=Some）检查工具。 */
  def evaluate(
      call: ToolCall,
      result: Option[ToolResult],
      context: GuardrailContext
  ): IO[GuardrailError, GuardrailDecision]

trait RunGuardrail:
  /** Run 级规则名称。 */
  def name: String

  /** 检查累计状态、预算或行为历史。 */
  def evaluate(state: AgentState, context: GuardrailContext): IO[GuardrailError, GuardrailDecision]

final case class ConfiguredGuardrails(
    input: Chunk[(InputGuardrail, GuardrailMode)],
    output: Chunk[(OutputGuardrail, GuardrailMode)],
    tools: Chunk[(ToolGuardrail, GuardrailMode)],
    run: Chunk[(RunGuardrail, GuardrailMode)],
    failurePolicy: GuardrailFailurePolicy = GuardrailFailurePolicy.FailClosed
)

/** 执行 Guardrail 链；Monitoring 拒绝只记录结果，不打开或关闭执行路径。 */
final class GuardrailEngine(config: ConfiguredGuardrails):
  /** 执行全部输入规则并保留每条规则的决策。 */
  def checkInput(
      message: AgentMessage,
      context: GuardrailContext
  ): IO[GuardrailError, Chunk[(String, GuardrailDecision)]] =
    evaluate(config.input, guardrail => guardrail.evaluate(message, context), _.name)

  /** 执行全部输出规则。 */
  def checkOutput(
      message: AgentMessage,
      context: GuardrailContext
  ): IO[GuardrailError, Chunk[(String, GuardrailDecision)]] =
    evaluate(config.output, guardrail => guardrail.evaluate(message, context), _.name)

  /** 执行工具前/后规则。 */
  def checkTool(
      call: ToolCall,
      result: Option[ToolResult],
      context: GuardrailContext
  ): IO[GuardrailError, Chunk[(String, GuardrailDecision)]] =
    evaluate(config.tools, guardrail => guardrail.evaluate(call, result, context), _.name)

  /** 执行 Run 级规则。
    *
    * @param state
    *   即将继续执行的完整耐久状态；规则可据此检查累计步骤、费用、失败模式或业务属性
    * @param context
    *   从可信认证信息构造的规则上下文，不能由模型消息覆盖
    * @return
    *   每条规则的名称与判定；Blocking 拒绝会以 `GuardrailRejected` 结束当前执行路径
    */
  def checkRun(
      state: AgentState,
      context: GuardrailContext
  ): IO[GuardrailError, Chunk[(String, GuardrailDecision)]] =
    evaluate(config.run, guardrail => guardrail.evaluate(state, context), _.name)

  /** 统一处理 Blocking/Monitoring 与 FailOpen/FailClosed，避免各阶段语义漂移。 */
  private def evaluate[A](
      configured: Chunk[(A, GuardrailMode)],
      run: A => IO[GuardrailError, GuardrailDecision],
      name: A => String
  ): IO[GuardrailError, Chunk[(String, GuardrailDecision)]] =
    ZIO.foreach(configured) { case (guardrail, mode) =>
      run(guardrail)
        .catchAll { error =>
          config.failurePolicy match
            case GuardrailFailurePolicy.FailClosed => ZIO.fail(error)
            case GuardrailFailurePolicy.FailOpen   =>
              ZIO.succeed(GuardrailDecision(allowed = true, Some("guardrail error; fail-open")))
        }
        .flatMap { decision =>
          if !decision.allowed && mode == GuardrailMode.Blocking then
            ZIO.fail(AgentError.GuardrailRejected(name(guardrail), decision.reason.getOrElse("未提供原因")))
          else ZIO.succeed(name(guardrail) -> decision)
        }
    }

object GuardrailEngine:
  val empty: ULayer[GuardrailEngine] = ZLayer.succeed(
    GuardrailEngine(ConfiguredGuardrails(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty))
  )

/** 防止外部资料把“忽略系统规则”之类文本提升为可信指令；这里只做基础监测，授权仍由代码策略负责。 */
final class PromptInjectionMonitor extends InputGuardrail:
  val name               = "prompt-injection-monitor"
  private val suspicious = List("ignore previous instructions", "忽略之前的指令", "system prompt", "系统提示词")

  /** 检测常见指令劫持短语；这是监测信号，真正权限仍由 ToolExecutor 与 ToolMetadata 强制。 */
  def evaluate(message: AgentMessage, context: GuardrailContext): IO[GuardrailError, GuardrailDecision] =
    val matched = suspicious.filter(token => message.text.toLowerCase.contains(token.toLowerCase))
    ZIO.succeed(
      GuardrailDecision(
        allowed = matched.isEmpty,
        Option.when(matched.nonEmpty)(s"检测到可疑指令: ${matched.mkString(",")}")
      )
    )
