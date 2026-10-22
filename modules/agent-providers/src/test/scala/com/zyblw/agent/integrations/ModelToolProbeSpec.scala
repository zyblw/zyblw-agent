package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.admin.ModelProbeRequest
import com.zyblw.agent.model.*
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object ModelToolProbeSpec extends ZIOSpecDefault:
  private val config =
    ModelProbeConfig(timeout = 2.seconds, maxOutputTokens = 256, mode = ModelProbeMode.ToolRoundTrip)
  private val probeCapabilities = ModelCapabilities(toolCalls = true, streaming = true)
  private val first             = ChatResponse(
    AgentMessage
      .assistantToolCalls(
        Chunk(ToolCall("actual-call-id", "probe_echo", Json.Obj("value" -> Json.Str("probe-ok"))))
      )
      .copy(metadata = Map("opaque-protocol-state" -> "preserve-me")),
    FinishReason.ToolCalls,
    TokenUsage(2, 3)
  )
  private val last = ChatResponse(AgentMessage.assistant("probe-ok"), FinishReason.Stop, TokenUsage(5, 7))

  private def model(
      requests: Ref[Chunk[ChatRequest]],
      response: ChatRequest => IO[AgentError, ChatResponse],
      started: Boolean = true
  ): ChatModel = new ChatModel:
    val provider            = "probe-provider"
    override val descriptor = ProviderDescriptor(provider, provider, "test", probeCapabilities)
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      requests.update(_ :+ request) *> response(request)
    override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
      ZStream.fromIterable(
        Option.when(started)(ModelStreamEvent.ResponseStarted(Some("request-id"))).toList
      ) ++
        ZStream.fromZIO(complete(request).map(ModelStreamEvent.Completed(_)))

  def spec = suite("Model tool round-trip probe")(
    test("the real admin entry resolves target capabilities through a multi-provider router") {
      for
        requests <- Ref.make(Chunk.empty[ChatRequest])
        target = model(requests, request => ZIO.succeed(if request.messages.size == 1 then first else last))
        other  = new ChatModel:
          val provider                       = "other-default"
          def complete(request: ChatRequest) = ZIO.dieMessage("wrong provider selected")
        router   <- RoutedChatModel.make(other.provider, List(other, target))
        registry <- ProviderRegistry.make(
          router,
          List(
            ProviderRegistration(other, "other-model", CredentialReference.Unspecified, true),
            ProviderRegistration(target, "model", CredentialReference.Unspecified, true)
          )
        )
        catalog = ModelCatalogLive.make(registry)
        service = ModelAdminLive.make(registry, catalog, ModelPolicySource.default, config)
        result <- service.probe(ModelProbeRequest(target.provider, Some("model")))
        sent   <- requests.get
      yield assertTrue(
        result.succeeded,
        sent.size == 2,
        sent.forall(_.settings.provider.contains(target.provider))
      )
    },
    test("truncated first turns and malformed stream lifecycles never certify tool support") {
      for
        requests  <- Ref.make(Chunk.empty[ChatRequest])
        truncated <- ZIO.foreach(Chunk(FinishReason.Length, FinishReason.ContentFilter)) { reason =>
          ModelAdminLive.probeWith(
            model(
              requests,
              request =>
                ZIO.succeed(if request.messages.size == 1 then first.copy(finishReason = reason) else last)
            ),
            "probe-provider",
            "model",
            config
          )
        }
        malformed <- ZIO.foreach(
          Chunk(
            Chunk(ModelStreamEvent.ResponseStarted(None)),
            Chunk(ModelStreamEvent.Completed(first), ModelStreamEvent.ResponseStarted(None)),
            Chunk(
              ModelStreamEvent.ResponseStarted(None),
              ModelStreamEvent.Completed(first),
              ModelStreamEvent.Completed(first)
            )
          )
        ) { events =>
          val adapter = new ChatModel:
            val provider            = "probe-provider"
            override val descriptor = ProviderDescriptor(provider, provider, "test", probeCapabilities)
            def complete(request: ChatRequest)        = ZIO.dieMessage("stream required")
            override def stream(request: ChatRequest) = ZStream.fromIterable(events)
          ModelAdminLive.probeWith(adapter, adapter.provider, "model", config)
        }
      yield assertTrue(
        truncated.forall(result =>
          !result.succeeded && result.failureCode.contains("tool-round-trip-incomplete")
        ),
        malformed.forall(!_.succeeded)
      )
    },
    test(
      "verifies both streamed turns with real call id and opaque state, without executing a business tool"
    ) {
      for
        requests <- Ref.make(Chunk.empty[ChatRequest])
        adapter = model(requests, request => ZIO.succeed(if request.messages.size == 1 then first else last))
        result <- ModelAdminLive.probeWith(adapter, adapter.provider, "model", config)
        sent   <- requests.get
      yield assertTrue(
        result.succeeded,
        result.inputTokens == 7,
        result.outputTokens == 10,
        sent.size == 2,
        sent.head.settings.toolChoice == ToolChoice.Auto,
        sent.head.settings.temperature.isEmpty,
        sent.last.messages(1).metadata.get("opaque-protocol-state").contains("preserve-me"),
        sent.last.messages.last.toolCallId.contains("actual-call-id")
      )
    },
    test("text-only success and complete wrapped as a stream do not certify tool streaming") {
      for
        requests <- Ref.make(Chunk.empty[ChatRequest])
        text     <- ModelAdminLive.probeWith(
          model(requests, _ => ZIO.succeed(last)),
          "probe-provider",
          "model",
          config
        )
        wrapped <- ModelAdminLive.probeWith(
          model(requests, _ => ZIO.succeed(first), started = false),
          "probe-provider",
          "model",
          config
        )
      yield assertTrue(
        !text.succeeded,
        text.failureCode.contains("tool-not-observed"),
        !wrapped.succeeded,
        wrapped.failureCode.contains("stream-not-observed")
      )
    },
    test("invalid arguments and an incomplete final turn fail without leaking protocol state") {
      for
        requests <- Ref.make(Chunk.empty[ChatRequest])
        wrong = first.copy(message =
          AgentMessage.assistantToolCalls(
            Chunk(ToolCall("call", "probe_echo", Json.Obj("value" -> Json.Str("wrong-private-value"))))
          )
        )
        invalid <- ModelAdminLive.probeWith(
          model(requests, _ => ZIO.succeed(wrong)),
          "probe-provider",
          "model",
          config
        )
        incomplete <- ModelAdminLive.probeWith(
          model(requests, _ => ZIO.succeed(first)),
          "probe-provider",
          "model",
          config
        )
      yield assertTrue(
        !invalid.succeeded,
        invalid.failureCode.contains("invalid-probe-tool-call"),
        !incomplete.succeeded,
        incomplete.failureCode.contains("tool-round-trip-incomplete")
      )
    },
    test("one timeout budget covers both turns and cancels the pending second request") {
      for
        requests    <- Ref.make(Chunk.empty[ChatRequest])
        entered     <- Promise.make[Nothing, Unit]
        interrupted <- Promise.make[Nothing, Unit]
        adapter = model(
          requests,
          request =>
            if request.messages.size == 1 then ZIO.succeed(first)
            else (entered.succeed(()).unit *> ZIO.never).onInterrupt(interrupted.succeed(()).unit)
        )
        fiber     <- ModelAdminLive.probeWith(adapter, "probe-provider", "model", config).fork
        _         <- entered.await
        _         <- TestClock.adjust(3.seconds)
        result    <- fiber.join
        cancelled <- interrupted.isDone
      yield assertTrue(!result.succeeded, result.failureCode.contains(ModelAdminLive.Timeout), cancelled)
    }
  )
