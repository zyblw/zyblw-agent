package com.zyblw.agent.testkit

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*

/** ProviderContract 2.0 的单项断言结果。
  *
  * 契约测试不在发现第一项问题时立即终止，而是尽可能收集全部问题。这样新增 Provider 时， 开发者一次测试就能看到“usage 不合法”“Completed 次数错误”等全部协议偏差。
  *
  * @param name
  *   稳定的机器可读断言名，可直接作为 CI 报告或指标标签
  * @param passed
  *   是否满足契约
  * @param details
  *   面向开发者的中文诊断信息；不得放入 API Key、请求正文或响应正文
  */
final case class ProviderContractAssertion(name: String, passed: Boolean, details: String)

/** Provider 成功路径的统一契约报告。
  *
  * 保留第一版报告中常用的布尔字段，同时增加 `assertions`，以便逐步迁移已有调用方。 `passed` 才是 ProviderContract 2.0 的最终结论。
  *
  * @param provider
  *   Provider 的稳定 ID，例如 `openai`、`anthropic`、`gemini`
  * @param capabilities
  *   当前模型声明的能力
  * @param emittedCompleted
  *   流是否恰好发送一个且最后发送 `Completed`
  * @param usageReported
  *   usage 是否为非负值；声明支持 usage 的流还需发送 `UsageUpdated`
  * @param toolCallsRoundTripped
  *   工具调用 ID/名称是否合法，工具结果请求是否能完成一次模型调用
  * @param assertions
  *   所有细粒度检查结果
  */
final case class ProviderContractReport(
    provider: String,
    capabilities: ModelCapabilities,
    emittedCompleted: Boolean,
    usageReported: Boolean,
    toolCallsRoundTripped: Boolean,
    assertions: Chunk[ProviderContractAssertion] = Chunk.empty
):
  /** 只有所有断言都通过时，Provider 才满足成功路径契约。 */
  def passed: Boolean = assertions.forall(_.passed)

/** HTTP/流式故障探针的期望。
  *
  * 公共 testkit 不伪造某个 Provider 的网络行为。Anthropic、Gemini 等 Adapter 应使用自己的 stub server 制造 429、5xx、慢流、断流或无效
  * usage，然后把实际调用包装为本类型。
  *
  * @param name
  *   场景名称，例如 `http-429`、`truncated-sse`
  * @param expectedCategory
  *   归一化后应得到的框架错误分类
  * @param expectedRetryable
  *   是否允许可靠性层自动重试
  * @param run
  *   真实执行该故障场景的 effect；场景正确时它应以 `AgentError` 失败
  */
final case class ProviderFailureProbe(
    name: String,
    expectedCategory: ErrorCategory,
    expectedRetryable: Boolean,
    run: IO[AgentError, Any]
)

/** 取消传播探针。
  *
  * 仅检查 Fiber 被中断是不够的，因为底层 HTTP 请求仍可能泄漏。Provider 测试需要让 stub server 或假 Transport 在连接关闭时完成
  * `transportCancelled`，从而证明 ZIO 中断真正到达网络边界。
  *
  * @param name
  *   场景名称
  * @param run
  *   启动一条不会自行结束的真实 Provider 请求
  * @param transportCancelled
  *   查询底层连接是否观察到取消/关闭
  * @param awaitReady
  *   等待请求真正进入 Transport；实现必须可在 settleTime 内完成
  * @param settleTime
  *   中断后允许 Transport 完成资源释放的最长时间
  */
final case class ProviderCancellationProbe(
    name: String,
    run: IO[AgentError, Any],
    transportCancelled: UIO[Boolean],
    awaitReady: UIO[Unit] = ZIO.unit,
    settleTime: Duration = 2.seconds
)

/** 一项故障或取消场景的执行结果。 */
final case class ProviderProbeResult(name: String, passed: Boolean, details: String)

/** ProviderContract 2.0 的完整报告。
  *
  * @param success
  *   成功、流式、usage 与工具回填契约
  * @param failures
  *   429/5xx/断流/无效 usage 等失败语义
  * @param cancellation
  *   ZIO Fiber 中断是否传播到底层 Transport
  */
final case class ProviderContractSuiteReport(
    success: ProviderContractReport,
    failures: Chunk[ProviderProbeResult],
    cancellation: Option[ProviderProbeResult]
):
  /** CI 门禁使用的总结果。 */
  def passed: Boolean = success.passed && failures.forall(_.passed) && cancellation.forall(_.passed)

/** 所有原生或兼容协议 Provider 必须复用的厂商无关契约。
  *
  * 该对象验证框架语义，不验证厂商 JSON 字段本身；JSON/SSE 映射仍应由 Adapter 的 wire test 覆盖。 推荐每个 Provider
  * 至少准备以下用例：普通文本、单/多工具调用、工具结果回填、慢流、流中断、 429、5xx、usage 异常和取消传播。
  */
object ProviderContract:
  /** 验证成功路径，并把本次交互写入默认“仅元数据”cassette。
    *
    * @param model
    *   待验证的真实 Adapter 或连接到 stub server 的 Adapter
    * @param request
    *   应包含 Provider 所声明能力对应的工具/角色等输入
    * @return
    *   汇总后的契约报告；网络或协议调用本身失败时保留 typed `AgentError`
    */
  def verify(model: ChatModel, request: ChatRequest): IO[AgentError, ProviderContractReport] =
    ProviderCassette.inMemory().flatMap(cassette => verify(model, request, cassette))

  /** 验证成功路径并记录脱敏 cassette。
    *
    * `complete` 与 `stream` 都会执行，因为一个成熟 Adapter 不能只保证其中一条路径。cassette 不保存原始消息、工具参数、响应文本或凭据，只保留计数、名称和不可逆摘要。
    *
    * @param model
    *   待验证 Provider
    * @param request
    *   厂商无关请求
    * @param cassette
    *   测试记录器；可在失败时输出到 CI artifact
    */
  def verify(
      model: ChatModel,
      request: ChatRequest,
      cassette: ProviderCassette
  ): IO[AgentError, ProviderContractReport] =
    for
      capabilities <- model.capabilities(request.settings.model)
      completed    <- cassette.capture("complete", model, request)(model.complete(request))
      events       <- cassette.capture("stream", model, request)(model.stream(request).runCollect)
      assertions  = successAssertions(request, capabilities, completed, events)
      completedOk = assertions.find(_.name == "stream.completed.exactly-once-and-last").exists(_.passed)
      usageOk     = assertions.filter(_.name.startsWith("usage.")).forall(_.passed)
      toolOk      = assertions.filter(_.name.startsWith("tool.")).forall(_.passed)
    yield ProviderContractReport(
      provider = model.provider,
      capabilities = capabilities,
      emittedCompleted = completedOk,
      usageReported = usageOk,
      toolCallsRoundTripped = toolOk,
      assertions = assertions
    )

  /** 执行完整 2.0 契约，包括 Provider 自己提供的网络故障和取消探针。
    *
    * @param model
    *   待测试 Provider
    * @param request
    *   成功路径请求，最好包含一次已经回填的工具结果
    * @param failureProbes
    *   由 HTTP stub 构造的错误场景
    * @param cancellationProbe
    *   可选的 Transport 取消探针；生产 Provider 应提供
    * @param cassette
    *   全程只记录安全摘要的 cassette
    */
  def verifySuite(
      model: ChatModel,
      request: ChatRequest,
      failureProbes: Chunk[ProviderFailureProbe],
      cancellationProbe: Option[ProviderCancellationProbe],
      cassette: ProviderCassette
  ): UIO[ProviderContractSuiteReport] =
    for
      successExit <- verify(model, request, cassette).exit
      success = successExit match
        case Exit.Success(report) => report
        case Exit.Failure(cause)  => failedSuccessReport(model, cause.prettyPrint)
      failures     <- ZIO.foreach(failureProbes)(verifyFailureProbe)
      cancellation <- ZIO.foreach(cancellationProbe)(verifyCancellationProbe)
    yield ProviderContractSuiteReport(success, failures, cancellation)

  /** 检查一次预期失败是否被正确归类，并保留正确的 retryable 语义。 */
  private def verifyFailureProbe(probe: ProviderFailureProbe): UIO[ProviderProbeResult] =
    probe.run.exit.map {
      case Exit.Success(_) =>
        ProviderProbeResult(probe.name, passed = false, "场景意外成功：Provider 没有暴露预期故障")
      case Exit.Failure(cause) =>
        cause.failureOption match
          case Some(error) =>
            val categoryOk  = error.category == probe.expectedCategory
            val retryableOk = error.retryable == probe.expectedRetryable
            ProviderProbeResult(
              probe.name,
              categoryOk && retryableOk,
              s"actualCategory=${error.category}, actualRetryable=${error.retryable}"
            )
          case None =>
            ProviderProbeResult(
              probe.name,
              passed = false,
              "场景以 defect 或 interruption 结束，而不是 typed AgentError"
            )
    }

  /** 中断调用 Fiber，并等待底层 Transport 报告连接已关闭。 */
  private def verifyCancellationProbe(probe: ProviderCancellationProbe): UIO[ProviderProbeResult] =
    ZIO.scoped {
      for
        fiber      <- probe.run.forkScoped
        ready      <- probe.awaitReady.timeout(probe.settleTime).map(_.isDefined)
        exit       <- fiber.interrupt
        propagated <- probe.transportCancelled
          .repeatUntil(identity)
          .timeout(probe.settleTime)
          .map(_.contains(true))
      yield ProviderProbeResult(
        probe.name,
        passed = ready && exit.isInterrupted && propagated,
        details =
          s"transportReady=$ready, fiberInterrupted=${exit.isInterrupted}, transportCancelled=$propagated"
      )
    }

  /** 构造成功路径调用失败时的报告，使 `verifySuite` 可以继续跑完所有故障探针。 */
  private def failedSuccessReport(model: ChatModel, details: String): ProviderContractReport =
    ProviderContractReport(
      provider = model.provider,
      capabilities = model.descriptor.capabilities,
      emittedCompleted = false,
      usageReported = false,
      toolCallsRoundTripped = false,
      assertions = Chunk(ProviderContractAssertion("success-path", passed = false, details))
    )

  /** 生成成功路径的全部确定性断言，不进行任何额外 I/O。 */
  private def successAssertions(
      request: ChatRequest,
      capabilities: ModelCapabilities,
      completeResponse: ChatResponse,
      events: Chunk[ModelStreamEvent]
  ): Chunk[ProviderContractAssertion] =
    val streamCompleted = events.collect { case ModelStreamEvent.Completed(response) => response }
    val lastIsCompleted = events.lastOption.exists(_.isInstanceOf[ModelStreamEvent.Completed])
    val completeUsageOk = nonNegative(completeResponse.usage)
    val streamUsageOk   = streamCompleted.lastOption.forall(response => nonNegative(response.usage))
    val usageEvents     = events.collect { case ModelStreamEvent.UsageUpdated(usage) => usage }
    val usageEventOk    =
      !capabilities.usageReporting || usageEvents.nonEmpty || streamCompleted.lastOption.exists(
        _.usage.totalTokens > 0
      )
    val completeCalls = completeResponse.message.toolCalls
    val streamCalls   = streamCompleted.lastOption.map(_.message.toolCalls).getOrElse(Chunk.empty)
    // complete 与 stream 是两次独立请求，Provider 可以复用 stub 中的相同 call ID；唯一性应在单次响应内判断。
    val toolIdsOk = List(completeCalls, streamCalls).forall(calls =>
      calls
        .forall(call => call.id.nonEmpty && call.name.nonEmpty) && calls.map(_.id).distinct.size == calls.size
    )
    val hasToolResult = request.messages.exists(message =>
      message.role == MessageRole.Tool && message.toolCallId.exists(_.nonEmpty)
    )

    Chunk(
      ProviderContractAssertion(
        "stream.completed.exactly-once-and-last",
        streamCompleted.size == 1 && lastIsCompleted,
        s"completedCount=${streamCompleted.size}, lastIsCompleted=$lastIsCompleted"
      ),
      ProviderContractAssertion(
        "usage.complete.non-negative",
        completeUsageOk,
        s"input=${completeResponse.usage.inputTokens}, output=${completeResponse.usage.outputTokens}"
      ),
      ProviderContractAssertion(
        "usage.stream.non-negative",
        streamUsageOk,
        "流式 Completed 中的 token 数必须为非负数"
      ),
      ProviderContractAssertion(
        "usage.stream.reported-when-capable",
        usageEventOk,
        s"declared=${capabilities.usageReporting}, usageEventCount=${usageEvents.size}"
      ),
      ProviderContractAssertion(
        "tool.call-id-and-name",
        toolIdsOk,
        s"completeToolCalls=${completeCalls.size}, streamToolCalls=${streamCalls.size}"
      ),
      ProviderContractAssertion(
        "tool.result-round-trip",
        !hasToolResult || streamCompleted.nonEmpty,
        s"requestContainsToolResult=$hasToolResult"
      )
    )

  /** usage 为计数值，任何负数都表示 Provider 解码或累计逻辑存在缺陷。 */
  private def nonNegative(usage: TokenUsage): Boolean = usage.inputTokens >= 0L && usage.outputTokens >= 0L
