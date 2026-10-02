package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import com.zyblw.agent.model.ChatModel
import zio.*
import zio.json.ast.Json
import zio.test.*

object ModelStructureNavigationSpec extends ZIOSpecDefault:
  private val section = DocumentSection("a", None, 0, 1, "太阳病")
  private val node    = StructureNode(section, Chunk("block"), Chunk("chunk"))
  private def model(run: ChatRequest => IO[AgentError, ChatResponse]): ChatModel = new ChatModel:
    val provider                       = "test"
    def complete(request: ChatRequest) = run(request)
  private def response(text: String) =
    ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, TokenUsage(1, 1))

  def spec = suite("Model structure navigation")(
    test("frontier IDs are strict; malformed JSON, foreign IDs, excessive choices and tools are rejected") {
      val outputs = Chunk(
        response("{}"),
        response("{\"ids\":[\"foreign\"]}"),
        response("{\"ids\":[\"a\",\"a\"]}"),
        ChatResponse(
          AgentMessage.assistant("summary").copy(toolCalls = Chunk(ToolCall("c", "write", Json.Obj()))),
          FinishReason.ToolCalls,
          TokenUsage(1, 1)
        )
      )
      for
        bad <- ZIO.foreach(outputs)(r =>
          ModelStructureNavigation
            .navigator(model(_ => ZIO.succeed(r)))
            .selectBranches("query", Chunk.empty, Chunk(node), 1)
            .exit
        )
        good <- ModelStructureNavigation
          .navigator(model(_ => ZIO.succeed(response("{\"ids\":[\"a\"]}"))))
          .selectBranches("太阳病", Chunk.empty, Chunk(node), 1)
      yield assertTrue(bad.forall(_.isFailure), good == Chunk("a"))
    },
    test("large parent summaries are sampled, pinned, tool-free, and cached by tenant/title/mode/content") {
      for
        requests <- Ref.make(Chunk.empty[ChatRequest])
        cache    <- InMemoryNodeSummaryStore.bounded(4)
        chat       = model(r => requests.update(_ :+ r).as(response("太阳病原文导航摘要")))
        summarizer = NodeSummarizer.cached(
          ModelStructureNavigation.summarizer(chat, StructureModelConfig(maxInputChars = 2000)),
          cache
        )
        block = DocumentBlock("block", Some("a"), 0, DocumentBlockKind.Paragraph, "正文𠀀".repeat(10000))
        spec  = NodeSummarySpec(
          "test",
          "exact-model",
          mode = SummaryMode.Full,
          cacheNamespace = "tenant-a:space"
        )
        a     <- summarizer.summarize(section, Chunk(block), false, spec)
        again <- summarizer.summarize(section, Chunk(block), false, spec)
        _     <- summarizer.summarize(section.copy(title = "新标题"), Chunk(block), false, spec)
        _ <- summarizer.summarize(section, Chunk(block), false, spec.copy(cacheNamespace = "tenant-b:space"))
        off      <- summarizer.summarize(section, Chunk(block), false, spec.copy(mode = SummaryMode.Off))
        captured <- requests.get
      yield assertTrue(
        a == again,
        a.nonEmpty,
        off.isEmpty,
        captured.length == 3,
        captured.forall(r =>
          r.tools.isEmpty && r.settings.toolChoice == ToolChoice.None && r.settings.selectionAuthority == ModelSelectionAuthority.RunPinned
        ),
        captured.forall(_.messages.last.text.length <= 2000),
        captured.forall(_.settings.model.contains("exact-model"))
      )
    },
    test("timeout and caller cancellation interrupt the underlying model fiber") {
      for
        cancelled <- Ref.make(0)
        started   <- Promise.make[Nothing, Unit]
        chat      = model(_ => (started.succeed(()) *> ZIO.never).onInterrupt(cancelled.update(_ + 1)))
        navigator = ModelStructureNavigation.navigator(chat, StructureModelConfig(timeout = 50.millis))
        timed         <- navigator.selectBranches("query", Chunk.empty, Chunk(node), 1).exit
        secondStarted <- Promise.make[Nothing, Unit]
        cancellable = ModelStructureNavigation.navigator(
          model(_ => (secondStarted.succeed(()) *> ZIO.never).onInterrupt(cancelled.update(_ + 1)))
        )
        fiber <- cancellable.selectBranches("query", Chunk.empty, Chunk(node), 1).fork
        _     <- secondStarted.await
        _     <- fiber.interrupt
        count <- cancelled.get
      yield assertTrue(timed.isFailure, count == 2)
    } @@ TestAspect.withLiveClock
  )
