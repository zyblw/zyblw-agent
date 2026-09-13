package com.zyblw.agent.runtime

import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.inspection.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.testkit.*
import zio.*
import zio.json.*
import zio.test.*
import zio.stream.*

/** 主模型 Intent → Provider → Settlement 与请求重建不变量。 */
object ModelCallRuntimeSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(
    AgentId("model-call-agent"),
    "Model Call Agent",
    "回答用户问题。",
    instructionSet = Some(
      InstructionSet(
        Chunk(InstructionBlock("agent.core", InstructionAuthority.System, "keep answers short", "1"))
      )
    )
  )

  private def finalResponse(text: String): ChatResponse =
    ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, TokenUsage(3, 2))

  private def countingModel(calls: Ref[Int], response: ChatResponse): ChatModel =
    new ChatModel:
      val provider                                                     = "crash-matrix"
      def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
        calls.update(_ + 1).as(response)

  private def hangingModel(calls: Ref[Int], started: Promise[Nothing, Unit]): ChatModel =
    new ChatModel:
      val provider                                                     = "hang"
      def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
        calls.update(_ + 1) *> started.succeed(()).unit *> ZIO.never

  private def layers(
      model: ChatModel,
      capture: CapturePolicy,
      store: ULayer[RunStore] = RunStore.inMemory,
      routing: Option[ModelRoutingPolicy] = None
  ) =
    TestAgentRuntime.inMemory(
      model,
      profile = RuntimeProfile(capturePolicy = capture, modelRouting = routing),
      store = store
    )

  private def layersWithManager(
      model: ChatModel,
      capture: CapturePolicy,
      manager: ContextManager,
      store: ULayer[RunStore]
  ) =
    TestAgentRuntime.inMemory(
      model,
      profile = RuntimeProfile(capturePolicy = capture),
      store = store,
      contextManager = Some(manager)
    )

  private def compressingManager: ContextManager = new ContextManager:
    def build(
        state: AgentState,
        definition: AgentDefinition,
        sources: ContextSources,
        actualPolicy: ContextPolicy
    ): IO[ContextError, PreparedContext] =
      val _ = (definition, sources, actualPolicy)
      ZIO.succeed(
        PreparedContext(
          state.messages,
          ContextUsage(
            estimatedTokens = 20L,
            droppedMessages = 1,
            truncatedToolResults = 0,
            usedSummary = true,
            compressionModelCalls = 1,
            compressionInputTokens = 9L,
            compressionOutputTokens = 4L
          ),
          summaryUpdate = Some(
            ContextSummaryCheckpoint(
              "[不可信历史摘要]\n已确认事实",
              coveredMessages = 1,
              sourceDigest = "0" * 64,
              compressorVersion = "crash-matrix-v1"
            )
          ),
          compressionUsage = TokenUsage(9L, 4L)
        )
      )

  def spec = suite("ModelCall durability")(
    test("路由取消传播到 Provider，保留次数与未结算调用") {
      for
        calls   <- Ref.make(0)
        started <- Promise.make[Nothing, Unit]
        model  = hangingModel(calls, started)
        policy = ModelRoutingPolicy("v1", Chunk(ModelRouteCandidate(ModelRef(model.provider, "m"))))
        inner    <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        observed <- InjectingRunStore.make(inner, (_, _) => false)
        result   <- (for
          runtime <- ZIO.service[AgentRuntime]
          fiber   <- runtime.run(agent, RunRequest(ThreadId("route-cancel"), AgentMessage.user("hi"))).fork
          _       <- started.await
          exit    <- fiber.interrupt
          id      <- observed.lastRunId.get.someOrFail(AgentError.Unexpected("missing run"))
          ledger  <- inner.getModelCalls(id)
          count   <- calls.get
          state   <- inner.load(id)
        yield (exit, ledger, count, state))
          .provideLayer(layers(model, CapturePolicy.Disabled, ZLayer.succeed(observed), Some(policy)))
      yield assertTrue(
        result._1.isInterrupted,
        Set(ModelCallStatus.Dispatched, ModelCallStatus.Unknown).contains(result._2.head.status),
        result._3 == 1,
        result._4.usage.modelCalls == 1
      )
    },
    test("超预算结算后终态写入失败，恢复仍停止且不再次调用模型") {
      for
        calls <- Ref.make(0)
        model = countingModel(
          calls,
          ChatResponse(AgentMessage.assistant("large"), FinishReason.Stop, TokenUsage(3, 20))
        )
        policy = ModelRoutingPolicy(
          "v1",
          Chunk(ModelRouteCandidate(ModelRef(model.provider, "m"))),
          defaultMaxOutputTokens = 5
        )
        inner    <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        observed <- InjectingRunStore.make(
          inner,
          (state, _) => state.status == RunStatus.BudgetExceeded,
          times = 1
        )
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          _       <- runtime
            .run(
              agent,
              RunRequest(
                ThreadId("overrun-crash"),
                AgentMessage.user("hello"),
                limits = RunLimits(maxOutputTokens = 10)
              )
            )
            .exit
          id        <- observed.lastRunId.get.someOrFail(AgentError.Unexpected("missing run"))
          before    <- inner.load(id)
          recovered <- runtime.recover(id).exit
          after     <- inner.load(id)
          count     <- calls.get
        yield (before, recovered, after, count))
          .provideLayer(layers(model, CapturePolicy.MetadataOnly, ZLayer.succeed(observed), Some(policy)))
      yield assertTrue(
        result._1.status == RunStatus.Running,
        result._2.isFailure,
        result._3.status == RunStatus.BudgetExceeded,
        result._3.usage.outputTokens == 20,
        result._4 == 1
      )
    },
    test("改候选顺序可换主模型，显式组合保持作者选择") {
      for
        model <- ScriptedChatModel.make(Chunk.fill(3)(finalResponse("ok")))
        first  = ModelRouteCandidate(ModelRef(model.provider, "first"))
        second = ModelRouteCandidate(ModelRef(model.provider, "second"))
        policy = ModelRoutingPolicy("v1", Chunk(first, second))
        _ <- ZIO
          .serviceWithZIO[AgentRuntime](
            _.run(agent, RunRequest(ThreadId("primary-a"), AgentMessage.user("hi")))
          )
          .provideLayer(layers(model, CapturePolicy.MetadataOnly, routing = Some(policy)))
        _ <- ZIO
          .serviceWithZIO[AgentRuntime](
            _.run(agent, RunRequest(ThreadId("primary-b"), AgentMessage.user("hi")))
          )
          .provideLayer(
            layers(
              model,
              CapturePolicy.MetadataOnly,
              routing = Some(policy.copy(candidates = policy.candidates.reverse))
            )
          )
        _ <- ZIO
          .serviceWithZIO[AgentRuntime](
            _.run(
              agent
                .copy(modelSettings = ModelSettings(provider = Some(model.provider), model = Some("first"))),
              RunRequest(ThreadId("explicit"), AgentMessage.user("hi"))
            )
          )
          .provideLayer(
            layers(
              model,
              CapturePolicy.MetadataOnly,
              routing = Some(policy.copy(candidates = policy.candidates.reverse))
            )
          )
        requests <- model.recordedRequests
      yield assertTrue(requests.map(_.settings.model) == Chunk(Some("first"), Some("second"), Some("first")))
    },
    test("已输出增量后失败不拼接第二次回答，调用保持 Unknown") {
      for
        calls <- Ref.make(0)
        model = new ChatModel:
          val provider                                                     = "partial"
          def complete(request: ChatRequest): IO[AgentError, ChatResponse] = ZIO.dieMessage("stream only")
          override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
            ZStream.fromZIO(calls.update(_ + 1)).drain ++ ZStream.succeed(
              ModelStreamEvent.TextDelta("partial")
            ) ++
              ZStream.fail(AgentError.ModelHttpFailure(provider, 503))
        policy = ModelRoutingPolicy("v1", Chunk(ModelRouteCandidate(ModelRef(model.provider, "m"))))
        events <- ZStream
          .serviceWithStream[AgentRuntime](
            _.runEvents(agent, RunRequest(ThreadId("partial"), AgentMessage.user("hello")))
          )
          .either
          .runCollect
          .provideLayer(layers(model, CapturePolicy.MetadataOnly, routing = Some(policy)))
        count <- calls.get
      yield assertTrue(
        count == 1,
        events.count(_.exists(_.isInstanceOf[AgentEvent.ModelTextDelta])) == 1,
        events.exists(_.isLeft)
      )
    },
    test("实际用量超出准入估算时先保留成功账本与费用，再停止 Run") {
      for
        calls <- Ref.make(0)
        model = countingModel(
          calls,
          ChatResponse(AgentMessage.assistant("large"), FinishReason.Stop, TokenUsage(3, 20))
        )
        policy = ModelRoutingPolicy(
          "v1",
          Chunk(ModelRouteCandidate(ModelRef(model.provider, "m"))),
          defaultMaxOutputTokens = 5
        )
        inner    <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        observed <- InjectingRunStore.make(inner, (_, _) => false)
        result   <- (for
          runtime <- ZIO.service[AgentRuntime]
          failed  <- runtime
            .run(
              agent,
              RunRequest(
                ThreadId("overrun"),
                AgentMessage.user("hello"),
                limits = RunLimits(maxOutputTokens = 10)
              )
            )
            .exit
          id     <- observed.lastRunId.get.someOrFail(AgentError.Unexpected("missing run"))
          ledger <- inner.getModelCalls(id)
          state  <- inner.load(id)
        yield (failed, ledger, state))
          .provideLayer(layers(model, CapturePolicy.MetadataOnly, ZLayer.succeed(observed), Some(policy)))
      yield assertTrue(
        result._1.isFailure,
        result._2.head.status == ModelCallStatus.Succeeded,
        result._2.head.usage.contains(TokenUsage(3, 20)),
        result._3.usage.outputTokens == 20,
        result._3.status == RunStatus.BudgetExceeded
      )
    },
    test("路由开启时 Disabled 仍写最小账本并准确结算一次调用") {
      for
        model <- ScriptedChatModel.make(Chunk(finalResponse("routed")))
        policy = ModelRoutingPolicy("v1", Chunk(ModelRouteCandidate(ModelRef(model.provider, "small"))))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("routed"), AgentMessage.user("private-data")))
          id = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          ledger   <- store.getModelCalls(id)
          state    <- store.load(id)
          requests <- model.recordedRequests
        yield (ledger, state, requests))
          .provideLayer(layers(model, CapturePolicy.Disabled, routing = Some(policy)))
      yield assertTrue(
        result._1.length == 1,
        result._1.head.status == ModelCallStatus.Succeeded,
        result._1.head.routeDecision.exists(_.selectedModel.model == "small"),
        result._1.head.verifyFrozenTools.isRight,
        result._1.head.copy(provider = "tampered").verifyFrozenTools.isLeft,
        result._1.head.canonicalRequest.isEmpty,
        !result._1.head.toJson.contains("private-data"),
        result._2.usage.modelCalls == 1,
        result._2.usage.inputTokens == 3,
        result._2.budget.consumed == result._2.usage,
        result._3.head.settings.model.contains("small")
      )
    },
    test("路由账本 TX1 失败不得发请求，显式未注册模型也不得发请求") {
      for
        calls <- Ref.make(0)
        model  = countingModel(calls, finalResponse("never"))
        policy = ModelRoutingPolicy("v1", Chunk(ModelRouteCandidate(ModelRef(model.provider, "registered"))))
        inner   <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        failing <- InjectingRunStore.make(
          inner,
          (_, write) => write.exists(_.isInstanceOf[ModelCallWrite.Insert])
        )
        result <- (for
          runtime  <- ZIO.service[AgentRuntime]
          failed   <- runtime.run(agent, RunRequest(ThreadId("route-tx1"), AgentMessage.user("hello"))).exit
          explicit <- runtime
            .run(
              agent.copy(modelSettings =
                ModelSettings(provider = Some(model.provider), model = Some("unregistered"))
              ),
              RunRequest(ThreadId("route-explicit"), AgentMessage.user("hello"))
            )
            .exit
          count <- calls.get
        yield (failed, explicit, count))
          .provideLayer(layers(model, CapturePolicy.Disabled, ZLayer.succeed(failing), Some(policy)))
      yield assertTrue(result._1.isFailure, result._2.isFailure, result._3 == 0)
    },
    test("路由 Provider 失败仍计一次调用，Unknown 恢复不得重放") {
      for
        calls <- Ref.make(0)
        model = new ChatModel:
          val provider                                                     = "failing"
          def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
            calls.update(_ + 1) *> ZIO.fail(AgentError.ModelHttpFailure(provider, 503))
        policy = ModelRoutingPolicy("v1", Chunk(ModelRouteCandidate(ModelRef(model.provider, "m"))))
        inner    <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        observed <- InjectingRunStore.make(inner, (_, _) => false)
        result   <- (for
          runtime <- ZIO.service[AgentRuntime]
          failed  <- runtime.run(agent, RunRequest(ThreadId("route-failed"), AgentMessage.user("hello"))).exit
          id      <- observed.lastRunId.get.someOrFail(AgentError.Unexpected("missing run"))
          recovered <- runtime.recover(id).exit
          ledger    <- inner.getModelCalls(id)
          state     <- inner.load(id)
          count     <- calls.get
        yield (failed, recovered, ledger, state, count))
          .provideLayer(layers(model, CapturePolicy.Disabled, ZLayer.succeed(observed), Some(policy)))
      yield assertTrue(
        result._1.isFailure,
        result._2.isFailure,
        result._3.head.status == ModelCallStatus.Unknown,
        result._4.usage.modelCalls == 1,
        result._4.budget.consumed.modelCalls == 1,
        result._5 == 1
      )
    },
    test("费用硬限下未知价格不得 dispatch") {
      for
        calls <- Ref.make(0)
        model  = countingModel(calls, finalResponse("never"))
        policy = ModelRoutingPolicy("v1", Chunk(ModelRouteCandidate(ModelRef(model.provider, "m"))))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          failed  <- runtime
            .run(
              agent,
              RunRequest(
                ThreadId("route-unpriced"),
                AgentMessage.user("hello"),
                limits = RunLimits(maxEstimatedCost = Some(BigDecimal(1)))
              )
            )
            .exit
          count <- calls.get
        yield (failed, count)).provideLayer(layers(model, CapturePolicy.MetadataOnly, routing = Some(policy)))
      yield assertTrue(result._1.isFailure, result._2 == 0)
    },
    test("Replayable 账本重建的 ChatRequest 与 Fake Model 捕获深比较相等") {
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("reconstructed")))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("replayable"), AgentMessage.user("私密问题")))
          runId = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          recorded <- model.recordedRequests
          ledger   <- store.getModelCalls(runId)
          state    <- store.load(runId)
        yield (recorded, ledger, state, outcome)).provideLayer(layers(model, CapturePolicy.Replayable))
        (recorded, ledger, state, outcome) = result
        reconstructed                      = ledger.head.toChatRequest
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Completed],
        recorded.length == 1,
        ledger.length == 1,
        ledger.head.status == ModelCallStatus.Succeeded,
        ledger.head.capturePolicy == CapturePolicy.Replayable,
        ledger.head.lineage.effectiveModelSettingsFingerprint.exists(_.matches("[0-9a-f]{64}")),
        reconstructed.contains(recorded.head),
        reconstructed.contains(CanonicalModelRequest.from(recorded.head).toChatRequest),
        state.pendingModelCall.isEmpty,
        !ledger.head.toJson.contains("私密问题") || ledger.head.canonicalRequest.exists(
          _.messages.exists(_.text.contains("私密问题"))
        )
      )
    },
    test("MetadataOnly 保存指纹但不能声称 exact replay，公共 Inspector 不含 prompt") {
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("metadata")))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("metadata-only"), AgentMessage.user("私密问题")))
          runId = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          recorded <- model.recordedRequests
          ledger   <- store.getModelCalls(runId)
          events   <- store.events(runId)
          state    <- store.load(runId)
        yield (recorded, ledger, events, state)).provideLayer(layers(model, CapturePolicy.MetadataOnly))
        (recorded, ledger, events, state) = result
        expectedFingerprint               = CanonicalModelRequest.fingerprint(
          recorded.head,
          agent.instructionSet.map(_.fingerprint)
        )
        inspection = RunInspection.build(state, events)
        encoded    = inspection.toJson
      yield assertTrue(
        ledger.length == 1,
        ledger.head.status == ModelCallStatus.Succeeded,
        ledger.head.canonicalRequest.isEmpty,
        ledger.head.toChatRequest.isLeft,
        ledger.head.fingerprint == expectedFingerprint,
        ledger.head.lineage.effectiveModelSettingsFingerprint.exists(_.matches("[0-9a-f]{64}")),
        encoded.contains("ModelCallPrepared"),
        !encoded.contains("私密问题"),
        !encoded.contains("keep answers short")
      )
    },
    test("TX1 persist 失败时不得调用 ChatModel") {
      for
        calls <- Ref.make(0)
        model = countingModel(calls, finalResponse("should-not-run"))
        inner   <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        failing <- InjectingRunStore.make(
          inner,
          (_, write) =>
            write.exists {
              case _: ModelCallWrite.Insert => true
              case _                        => false
            }
        )
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          exit    <- runtime.run(agent, RunRequest(ThreadId("persist-fail"), AgentMessage.user("hello"))).exit
          invoked <- calls.get
        yield (exit, invoked)).provideLayer(layers(model, CapturePolicy.Replayable, ZLayer.succeed(failing)))
      yield assertTrue(result._1.isFailure, result._2 == 0)
    },
    test("CapturePolicy.Disabled 不写模型账本正文，公共 Inspector 不含 prompt") {
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("disabled-ok")))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("disabled"), AgentMessage.user("私密问题")))
          runId = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          ledger <- store.getModelCalls(runId)
          events <- store.events(runId)
          state  <- store.load(runId)
        yield (outcome, ledger, events, state)).provideLayer(layers(model, CapturePolicy.Disabled))
        (outcome, ledger, events, state) = result
        encoded                          = RunInspection.build(state, events).toJson
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Completed],
        ledger.isEmpty,
        state.pendingModelCall.isEmpty,
        !events.exists(_.event.isInstanceOf[AgentEvent.ModelCallPrepared]),
        !encoded.contains("私密问题"),
        !encoded.contains("keep answers short")
      )
    },
    test("Provider 返回后 settlement 失败时不得把调用当成成功，恢复为 Unknown 且不重放") {
      for
        calls <- Ref.make(0)
        model = countingModel(calls, finalResponse("settlement-lost"))
        inner   <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        failing <- InjectingRunStore.make(
          inner,
          (_, write) =>
            write.exists {
              case ModelCallWrite.Transition(_, _, next) if next.status == ModelCallStatus.Succeeded =>
                true
              case _ => false
            },
          AgentError.PersistenceFailure("injected-settlement-failure")
        )
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          failed  <- runtime
            .run(agent, RunRequest(ThreadId("settlement-fail"), AgentMessage.user("hello")))
            .exit
          runId     <- failing.lastRunId.get.someOrFail(AgentError.Unexpected("缺少注入提交的 RunId"))
          recovered <- runtime.recover(runId).exit
          ledger    <- ZIO.serviceWithZIO[RunStore](_.getModelCalls(runId))
          state     <- ZIO.serviceWithZIO[RunStore](_.load(runId))
          invoked   <- calls.get
        yield (failed, recovered, ledger, state, invoked))
          .provideLayer(layers(model, CapturePolicy.Replayable, ZLayer.succeed(failing)))
      yield assertTrue(
        result._1.isFailure,
        result._2.isFailure,
        result._2.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.ModelCallUncertain]),
        result._3.length == 1,
        result._3.head.status == ModelCallStatus.Unknown,
        result._4.status == RunStatus.Failed,
        result._5 == 1
      )
    },
    test("settlement 之后 Complete 前提死进程，恢复收口且不第二次调用 Provider") {
      for
        calls <- Ref.make(0)
        model = countingModel(calls, finalResponse("already-settled"))
        inner   <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        failing <- InjectingRunStore.make(
          inner,
          (state, _) => state.status == RunStatus.Completed,
          AgentError.PersistenceFailure("injected-complete-failure"),
          times = 1
        )
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          failed  <- runtime
            .run(agent, RunRequest(ThreadId("complete-window"), AgentMessage.user("hello")))
            .exit
          runId     <- failing.lastRunId.get.someOrFail(AgentError.Unexpected("缺少注入提交的 RunId"))
          mid       <- ZIO.serviceWithZIO[RunStore](_.load(runId))
          ledgerMid <- ZIO.serviceWithZIO[RunStore](_.getModelCalls(runId))
          recovered <- runtime.recover(runId)
          after     <- ZIO.serviceWithZIO[RunStore](_.load(runId))
          invoked   <- calls.get
        yield (failed, mid, ledgerMid, recovered, after, invoked))
          .provideLayer(layers(model, CapturePolicy.Replayable, ZLayer.succeed(failing)))
      yield assertTrue(
        result._1.isFailure,
        result._2.status == RunStatus.Running,
        result._2.messages.lastOption.exists(_.role == MessageRole.Assistant),
        result._3.exists(_.status == ModelCallStatus.Succeeded),
        result._4.isInstanceOf[RunOutcome.Completed],
        result._5.status == RunStatus.Completed,
        result._6 == 1
      )
    },
    test("settlement 时丢失 lease 不得写成成功，恢复为 Unknown") {
      for
        calls <- Ref.make(0)
        model = countingModel(calls, finalResponse("lease-lost"))
        inner   <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        failing <- InjectingRunStore.make(
          inner,
          (_, write) =>
            write.exists {
              case ModelCallWrite.Transition(_, _, next) if next.status == ModelCallStatus.Succeeded =>
                true
              case _ => false
            },
          AgentError.LeaseLost(
            RunId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")),
            "worker-a",
            1L,
            "injected"
          )
        )
        result <- (for
          runtime   <- ZIO.service[AgentRuntime]
          failed    <- runtime.run(agent, RunRequest(ThreadId("lease-lost"), AgentMessage.user("hello"))).exit
          runId     <- failing.lastRunId.get.someOrFail(AgentError.Unexpected("缺少注入提交的 RunId"))
          recovered <- runtime.recover(runId).exit
          ledger    <- ZIO.serviceWithZIO[RunStore](_.getModelCalls(runId))
          invoked   <- calls.get
        yield (failed, recovered, ledger, invoked))
          .provideLayer(layers(model, CapturePolicy.Replayable, ZLayer.succeed(failing)))
      yield assertTrue(
        result._1.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.LeaseLost]),
        result._2.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.ModelCallUncertain]),
        result._3.exists(_.status == ModelCallStatus.Unknown),
        result._4 == 1
      )
    },
    test("Context 已提交后主模型崩溃，恢复不得清零已消费预算") {
      for
        calls   <- Ref.make(0)
        started <- Promise.make[Nothing, Unit]
        model = hangingModel(calls, started)
        inner   <- ZIO.service[RunStore].provideLayer(RunStore.inMemory)
        tracker <- InjectingRunStore.make(inner, (_, _) => false)
        result  <- (for
          runtime   <- ZIO.service[AgentRuntime]
          store     <- ZIO.service[RunStore]
          fiber     <- runtime.run(agent, RunRequest(ThreadId("budget-crash"), AgentMessage.user("长历史"))).fork
          _         <- started.await
          runId     <- tracker.lastRunId.get.someOrFail(AgentError.Unexpected("缺少注入提交的 RunId"))
          before    <- store.load(runId)
          _         <- fiber.interrupt
          recovered <- runtime.recover(runId).exit
          after     <- store.load(runId)
          invoked   <- calls.get
        yield (before, recovered, after, invoked)).provideLayer(
          layersWithManager(model, CapturePolicy.MetadataOnly, compressingManager, ZLayer.succeed(tracker))
        )
      yield assertTrue(
        result._1.usage.modelCalls == 1,
        result._1.budget.consumed.modelCalls == 1,
        result._1.usage.inputTokens == 9L,
        result._1.usage.outputTokens == 4L,
        result._2.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.ModelCallUncertain]),
        result._3.status == RunStatus.Failed,
        result._3.usage.modelCalls == 1,
        result._3.budget.consumed.modelCalls == 1,
        result._3.usage.inputTokens == 9L,
        result._4 == 1
      )
    }
  )

  /** 确定性故障注入：按状态/账本写种类失败，并记住最近一次提交的 RunId。 */
  final private class InjectingRunStore(
      inner: RunStore,
      shouldFail: (AgentState, Option[ModelCallWrite]) => Boolean,
      error: StoreError,
      remaining: Ref[Int],
      val lastRunId: Ref[Option[RunId]]
  ) extends RunStore:
    export inner.{
      commit => _,
      commitFenced => _,
      createWithEvents => _,
      load,
      save,
      appendEvents,
      events,
      requestCancellation,
      cancellationRequested,
      prepareToolExecutions,
      transitionToolExecution,
      getToolExecution,
      getToolExecutions,
      getModelCall,
      getModelCalls,
      delete
    }

    def createWithEvents(
        state: AgentState,
        incoming: NonEmptyChunk[PersistedAgentEvent]
    ): IO[StoreError, Unit] =
      lastRunId.set(Some(state.runId)) *> inner.createWithEvents(state, incoming)

    def commit(
        expectedVersion: Version,
        state: AgentState,
        events: NonEmptyChunk[PersistedAgentEvent],
        modelCall: Option[ModelCallWrite]
    ): IO[StoreError, Version] =
      lastRunId.set(Some(state.runId)) *> remaining.get.flatMap { left =>
        if left > 0 && shouldFail(state, modelCall) then remaining.update(_ - 1) *> ZIO.fail(error)
        else inner.commit(expectedVersion, state, events, modelCall)
      }

    def commitFenced(
        lease: RunCommandLease,
        expectedVersion: Version,
        state: AgentState,
        events: NonEmptyChunk[PersistedAgentEvent],
        modelCall: Option[ModelCallWrite]
    ): IO[StoreError, Version] =
      commit(expectedVersion, state, events, modelCall)

  private object InjectingRunStore:
    def make(
        inner: RunStore,
        shouldFail: (AgentState, Option[ModelCallWrite]) => Boolean,
        error: StoreError = AgentError.PersistenceFailure("injected-model-call-failure"),
        times: Int = Int.MaxValue
    ): UIO[InjectingRunStore] =
      for
        remaining <- Ref.make(times)
        lastRunId <- Ref.make(Option.empty[RunId])
      yield InjectingRunStore(inner, shouldFail, error, remaining, lastRunId)
