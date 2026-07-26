package com.zyblw.agent.memory.llm

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import java.util.UUID
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 验证 LLM 只能通过单一工具提出有逐字证据的 upsert 候选，不能自行提升证据等级或删除记忆。 */
object LlmMemoryExtractorSpec extends ZIOSpecDefault:
  private val runId = RunId(UUID.fromString("00000000-0000-0000-0000-000000000111"))

  /** 创建一个支持 strict/specific tool choice 的测试模型。 */
  private def model(
      completeEffect: ChatRequest => IO[AgentError, ChatResponse],
      captured: Ref[Chunk[ChatRequest]]
  ): ChatModel = new ChatModel:
    val provider            = "memory-test"
    override val descriptor = ProviderDescriptor(
      provider,
      "Memory test",
      "test",
      ModelCapabilities(toolCalls = true, strictToolSchema = true, specificToolChoice = true)
    )
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      captured.update(_ :+ request) *> completeEffect(request)

  /** 生成一次指定工具调用响应。 */
  private def response(arguments: Json): ChatResponse = ChatResponse(
    AgentMessage.assistantToolCalls(Chunk(ToolCall("memory-call-1", "submit_memory_candidates", arguments))),
    FinishReason.ToolCalls,
    TokenUsage(20L, 10L)
  )

  /** 建立一条 candidate 参数；测试按需替换 quote/source/sensitivity。 */
  private def arguments(
      quote: String,
      sourceIndex: Int = 0,
      sensitivity: String = "personal",
      confidence: Double = 0.95,
      kind: String = "preference"
  ): Json = Json.Obj(
    Chunk(
      "candidates" -> Json.Arr(
        Chunk(
          Json.Obj(
            Chunk(
              "key"                -> Json.Str("learning.preferred_classic"),
              "value"              -> Json.Obj(Chunk("title" -> Json.Str("伤寒论"))),
              "kind"               -> Json.Str(kind),
              "importance"         -> Json.Num(0.8),
              "confidence"         -> Json.Num(confidence),
              "sensitivity"        -> Json.Str(sensitivity),
              "sourceMessageIndex" -> Json.Num(sourceIndex),
              "evidenceQuote"      -> Json.Str(quote)
            )
          )
        )
      )
    )
  )

  private val config = LlmMemoryExtractorConfig(
    modelSettings = ModelSettings(provider = Some("memory-test"), model = Some("memory-cheap-v1")),
    requestTimeout = 2.seconds,
    maxSchemaRepairs = 1
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LlmMemoryExtractor")(
    test("required strict tool、逐字用户证据和领域字段形成完整候选") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        extractor = LlmMemoryExtractor(
          model(_ => ZIO.succeed(response(arguments("喜欢阅读伤寒论"))), captured),
          config
        )
        candidates <- extractor.extract(
          Chunk(
            AgentMessage.user("我喜欢阅读伤寒论，并希望以后优先推荐这本书。"),
            AgentMessage.system("不应作为记忆来源的系统内容")
          ),
          runId
        )
        requests <- captured.get
        entry = candidates.head.mutation match
          case MemoryMutation.Upsert(value) => value
          case _                            => throw new AssertionError("LLM extractor 不应产生 Delete")
        request = requests.head
      yield assertTrue(
        candidates.length == 1,
        entry.key == "learning.preferred_classic",
        entry.evidence == MemoryEvidence.UserStated,
        entry.sourceRunId.contains(runId),
        entry.extractorVersion == "llm-tool-v1",
        entry.sensitivity == MemorySensitivity.Personal,
        request.tools.length == 1,
        request.tools.head.strict,
        request.settings.toolChoice == ToolChoice.Specific("submit_memory_candidates"),
        !request.messages.last.text.contains("不应作为记忆来源的系统内容")
      )
    },
    test("第一次伪造 quote 时安全 repair，第二次有效响应按确定顺序成功") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        calls    <- Ref.make(0)
        stub = model(
          _ =>
            calls.updateAndGet(_ + 1).map { count =>
              if count == 1 then response(arguments("原文中不存在"))
              else response(arguments("喜欢阅读伤寒论"))
            },
          captured
        )
        result <- LlmMemoryExtractor(stub, config).extract(
          Chunk(AgentMessage.user("我喜欢阅读伤寒论，并希望以后优先推荐这本书。")),
          runId
        )
        requests <- captured.get
      yield assertTrue(
        result.length == 1,
        requests.length == 2,
        requests(1).messages.last.text.contains("上一次工具参数未通过"),
        !requests(1).messages.last.text.contains("原文中不存在")
      )
    },
    test("持续伪造证据只返回稳定错误码，不泄漏模型参数正文") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        extractor = LlmMemoryExtractor(
          model(_ => ZIO.succeed(response(arguments("高度敏感的伪造正文"))), captured),
          config
        )
        exit <- extractor.extract(Chunk(AgentMessage.user("普通内容")), runId).exit
        message = exit.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
      yield assertTrue(
        exit.isFailure,
        message == "memory-extractor-evidence-invalid",
        !message.contains("高度敏感")
      )
    },
    test("模型不能通过 sensitivity 字段静默持久化敏感健康记忆") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        extractor = LlmMemoryExtractor(
          model(_ => ZIO.succeed(response(arguments("最近身体不适", sensitivity = "sensitive"))), captured),
          config
        )
        exit <- extractor.extract(Chunk(AgentMessage.user("我最近身体不适")), runId).exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.flatMap(_.failureOption).exists(_.message == "memory-extractor-sensitive-disabled")
      )
    },
    test("Assistant 来源只能成为 ModelInferred，并继续接受 MemoryLifecycle 低置信治理") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        extractor = LlmMemoryExtractor(
          model(
            _ =>
              ZIO.succeed(
                response(
                  arguments(
                    "推测用户喜欢伤寒论",
                    confidence = 0.5
                  )
                )
              ),
            captured
          ),
          config
        )
        candidates <- extractor.extract(Chunk(AgentMessage.assistant("推测用户喜欢伤寒论")), runId)
        store      <- ZIO.service[MemoryStore]
        report     <- MemoryLifecycle(extractor, store, MemoryGovernancePolicy())
          .applyCandidates(MemoryScope.User(TenantId("tenant-a"), UserId("user-a")), candidates)
        evidence = candidates.head.mutation match
          case MemoryMutation.Upsert(entry) => entry.evidence
          case _                            => MemoryEvidence.Imported
      yield assertTrue(
        evidence == MemoryEvidence.ModelInferred,
        report.written == 0,
        report.rejected.map(_._2) == Chunk("model-confidence-too-low")
      )
    }.provide(MemoryStore.inMemory),
    test("模型调用总超时映射为可重试 StoreError，Fiber 不会悬挂") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        timeoutConfig = config.copy(requestTimeout = 50.millis, maxSchemaRepairs = 0)
        extractor     = LlmMemoryExtractor(model(_ => ZIO.never, captured), timeoutConfig)
        exit <- extractor.extract(Chunk(AgentMessage.user("我喜欢阅读伤寒论")), runId).exit
        retryable = exit.causeOption.flatMap(_.failureOption).exists(_.retryable)
      yield assertTrue(exit.isFailure, retryable)
    }
  ) @@ TestAspect.withLiveClock
