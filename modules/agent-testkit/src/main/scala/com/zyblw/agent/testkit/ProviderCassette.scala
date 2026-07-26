package com.zyblw.agent.testkit

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import zio.*
import zio.json.*

/** cassette 对内容的处理策略。
  *
  * 两种策略都不会保存原始提示词、工具参数、模型输出或认证头。`Redacted` 额外把模型名、工具名等 可能包含租户业务语义的字段也替换为固定占位符，适合把 CI artifact 提交到共享存储。
  */
enum ProviderCassettePolicy:
  /** 保存 Provider/模型/工具名等低风险元数据，并对完整请求做 SHA-256 指纹。 */
  case MetadataOnly

  /** 只保存计数、结果分类和不可逆指纹，不保存模型名与工具名。 */
  case Redacted

/** 一次 Provider 操作的安全记录。
  *
  * @param operation
  *   `complete` 或 `stream`
  * @param provider
  *   Provider ID
  * @param model
  *   模型名；Redacted 策略下固定为 `<redacted>`
  * @param messageCount
  *   请求消息数量
  * @param toolNames
  *   工具名称；Redacted 策略下为空
  * @param requestFingerprint
  *   请求规范 JSON 的 SHA-256；用于判断两次录制是否为同一输入，不可还原正文
  * @param outcome
  *   `succeeded`、`failed` 或 `interrupted`
  * @param errorCategory
  *   失败后的稳定错误分类，不保存异常 message
  */
final case class ProviderCassetteEntry(
    operation: String,
    provider: String,
    model: String,
    messageCount: Int,
    toolNames: Chunk[String],
    requestFingerprint: String,
    outcome: String,
    errorCategory: Option[String]
) derives JsonCodec

/** Provider 契约测试的脱敏交换记录器。
  *
  * 这里有意没有“保存 HTTP headers/body”的方法，避免测试作者无意中把 API Key 或用户正文写盘。 若排查 wire-level 问题，应在 Provider stub server
  * 内记录已知的假数据，而不是生产请求。
  */
trait ProviderCassette:
  /** 执行一次调用并记录安全摘要。
    *
    * @param operation
    *   操作名
    * @param model
    *   Provider 实例，仅读取稳定 ID
    * @param request
    *   厂商无关请求；只进入内存计算摘要，不会原样保存
    * @param effect
    *   实际 Provider 调用
    */
  def capture[A](operation: String, model: ChatModel, request: ChatRequest)(
      effect: IO[AgentError, A]
  ): IO[AgentError, A]

  /** 读取当前进程中已录制的不可变快照。 */
  def entries: UIO[Chunk[ProviderCassetteEntry]]

object ProviderCassette:
  /** 创建内存 cassette，默认只保存元数据。
    *
    * @param policy
    *   内容策略；共享 CI 环境推荐 `Redacted`
    */
  def inMemory(policy: ProviderCassettePolicy = ProviderCassettePolicy.MetadataOnly): UIO[ProviderCassette] =
    Ref.make(Chunk.empty[ProviderCassetteEntry]).map(ref => InMemoryProviderCassette(ref, policy))

final private class InMemoryProviderCassette(
    ref: Ref[Chunk[ProviderCassetteEntry]],
    policy: ProviderCassettePolicy
) extends ProviderCassette:
  def capture[A](operation: String, model: ChatModel, request: ChatRequest)(
      effect: IO[AgentError, A]
  ): IO[AgentError, A] =
    effect.onExit(exit => ref.update(_ :+ entryFor(operation, model, request, exit)).unit)

  def entries: UIO[Chunk[ProviderCassetteEntry]] = ref.get

  /** Exit 只转换成稳定分类，绝不序列化 Cause、异常 message 或响应对象。 */
  private def entryFor[A](
      operation: String,
      model: ChatModel,
      request: ChatRequest,
      exit: Exit[AgentError, A]
  ): ProviderCassetteEntry =
    val (outcome, category) = exit match
      case Exit.Success(_)                            => "succeeded" -> None
      case Exit.Failure(cause) if cause.isInterrupted =>
        "interrupted" -> Some(ErrorCategory.Cancelled.toString)
      case Exit.Failure(cause) => "failed" -> cause.failureOption.map(_.category.toString)
    val modelName = policy match
      case ProviderCassettePolicy.MetadataOnly => request.settings.model.getOrElse("<default>")
      case ProviderCassettePolicy.Redacted     => "<redacted>"
    val tools = policy match
      case ProviderCassettePolicy.MetadataOnly => request.tools.map(_.name)
      case ProviderCassettePolicy.Redacted     => Chunk.empty
    ProviderCassetteEntry(
      operation = operation,
      provider = model.provider,
      model = modelName,
      messageCount = request.messages.size,
      toolNames = tools,
      requestFingerprint = sha256(request.toJson),
      outcome = outcome,
      errorCategory = category
    )

  /** 使用固定 UTF-8 和 SHA-256 生成跨 JVM 稳定的十六进制指纹。 */
  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
