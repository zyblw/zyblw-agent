package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*

/** 目录与探活测试共用的最小 `ChatModel`。
  *
  * 两个刻意的设计:
  *   - 记录调用次数,使"未注册组合不打网络请求"这条断言可以直接观测,而不是靠没有 stub server 间接推断。
  *   - 持有 `apiKey`,使假 Key 真正存在于注册表可达的对象图里。否则"序列化输出不含 Key"这条断言会因为 Key 从来 没进过对象图而恒真,测不出任何东西。
  */
final class StubChatModel(
    val provider: String,
    override val descriptor: ProviderDescriptor,
    val apiKey: String,
    reply: IO[AgentError, ChatResponse],
    calls: Ref[Chunk[ChatRequest]]
) extends ChatModel:
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    calls.update(_ :+ request) *> reply

  /** 已发生的真实调用次数。 */
  def callCount: UIO[Int] = calls.get.map(_.length)

  /** 最后一次真实发出的请求。 */
  def lastRequest: UIO[Option[ChatRequest]] = calls.get.map(_.lastOption)

object ModelGovernanceFixtures:
  /** 可识别的假密钥;任何序列化输出里出现它都说明存在泄漏路径。 */
  val FakeApiKey: String = "sk-fake-0123456789-must-not-appear"

  /** 可识别的模型输出正文;探活结果里出现它就说明回显了模型答案。 */
  val FakeModelOutput: String = "MODEL-OUTPUT-MUST-NOT-APPEAR"

  /** 可识别的 Provider 原始响应正文;失败结果里出现它就说明错误消息被带了出去。 */
  val FakeProviderBody: String = """{"error":{"message":"PROVIDER-BODY-MUST-NOT-APPEAR"}}"""

  /** 声明了逐模型能力清单的 Provider。 */
  val declaredDescriptor: ProviderDescriptor = ProviderDescriptor(
    id = "stub-declared",
    displayName = "Stub Declared",
    protocol = "stub-protocol",
    capabilities = ModelCapabilities(vision = false, thinking = false, streaming = false),
    models = Map(
      "model-vision" -> ModelCapabilities(vision = true, thinking = true, streaming = true),
      "model-basic"  -> ModelCapabilities(vision = false, thinking = false, streaming = false)
    )
  )

  /** 未声明模型清单的 Provider;能力只描述协议本身。 */
  val undeclaredDescriptor: ProviderDescriptor = ProviderDescriptor(
    id = "stub-undeclared",
    displayName = "Stub Undeclared",
    protocol = "stub-protocol",
    capabilities = ModelCapabilities(vision = true, streaming = true, maxOutputTokens = Some(4096L))
  )

  /** 与内置 DeepSeek 适配器同名的 Provider,用于验证真实配置到凭据状态的派生。 */
  val deepSeekDescriptor: ProviderDescriptor = ProviderDescriptor(
    id = "deepseek",
    displayName = "DeepSeek",
    protocol = "openai-chat-completions",
    capabilities = ModelCapabilities()
  )

  /** 由 stub 自身的凭据派生注册声明,使 `present` 与对象图里真实存在的 Key 一致。 */
  def registration(
      model: StubChatModel,
      defaultModel: String,
      variable: String
  ): ProviderRegistration = ProviderRegistration(
    chatModel = model,
    defaultModel = defaultModel,
    credentialReference = CredentialReference.environment(variable),
    credentialPresent = model.apiKey.trim.nonEmpty
  )

  /** 构造一个固定应答的 stub;`apiKey` 为空表示部署未提供该 Provider 的凭据。 */
  def stub(
      descriptor: ProviderDescriptor,
      reply: IO[AgentError, ChatResponse],
      apiKey: String = FakeApiKey
  ): UIO[StubChatModel] =
    Ref.make(Chunk.empty[ChatRequest]).map(StubChatModel(descriptor.id, descriptor, apiKey, reply, _))

  /** 带可识别正文与固定 usage 的成功应答。 */
  def response(inputTokens: Long, outputTokens: Long): ChatResponse = ChatResponse(
    message = AgentMessage.assistant(FakeModelOutput),
    finishReason = FinishReason.Stop,
    usage = TokenUsage(inputTokens = inputTokens, outputTokens = outputTokens)
  )
