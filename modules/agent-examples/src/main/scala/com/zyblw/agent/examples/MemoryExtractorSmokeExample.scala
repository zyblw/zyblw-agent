package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.memory.llm.*
import java.io.IOException
import zio.*
import zio.http.*
import zio.json.*

/** 使用真实 Provider 验证 LLM MemoryExtractor 的工具调用、逐字证据和安全治理边界。
  *
  * 输入是仓库内固定的非医疗学习偏好，不读取真实用户数据。报告只包含候选数量和布尔门禁，不输出 key、value、quote、 prompt、Provider body 或 API Key。该 smoke 与
  * `ProviderSmokeExample` 分开，避免每次连通性检查都额外产生工具调用费用。
  */
object MemoryExtractorSmokeExample extends ZIOAppDefault:

  /** 可作为 CI artifact 保存的低敏结果。 */
  final private case class Report(
      provider: String,
      model: String,
      succeeded: Boolean,
      candidateCount: Int,
      allUpserts: Boolean,
      allUserStated: Boolean,
      noSensitive: Boolean,
      errorCategory: Option[String]
  ) derives JsonCodec:
    /** 提炼至少一个安全候选且全部来自用户逐字证据才通过。 */
    def passed: Boolean = succeeded && candidateCount > 0 && allUpserts && allUserStated && noSensitive

  val run: ZIO[Any, Any, Any] = program.provide(Client.default)

  private val program: ZIO[Client, AgentError | IOException, Unit] =
    for
      provider <- required("ZYBLW_SMOKE_PROVIDER").map(_.trim.toLowerCase)
      client   <- ZIO.service[Client]
      target   <- ProviderSmokeExample.loadTarget(provider, client)
      runId    <- RunId.random
      extractor = LlmMemoryExtractor(
        target.model,
        LlmMemoryExtractorConfig(
          modelSettings = ModelSettings(
            provider = Some(target.model.provider),
            model = Some(target.modelId),
            temperature = Some(0.0),
            maxOutputTokens = Some(800)
          ),
          maxMessages = 4,
          maxInputCodePoints = 2_000,
          maxCandidates = 3,
          requestTimeout = 60.seconds,
          maxSchemaRepairs = 1,
          extractorVersion = "provider-smoke-v1",
          allowExplicitSensitive = false
        )
      )
      exit <- extractor
        .extract(
          Chunk(AgentMessage.user("我明确偏好研读《伤寒论》，以后推荐学习材料时请优先考虑这一偏好。")),
          runId
        )
        .exit
      report = exit match
        case Exit.Success(candidates) => successReport(target, candidates)
        case Exit.Failure(cause)      =>
          Report(
            bounded(target.model.provider),
            target.modelId.take(200),
            succeeded = false,
            candidateCount = 0,
            allUpserts = false,
            allUserStated = false,
            noSensitive = false,
            errorCategory =
              Some(cause.failureOption.fold(ErrorCategory.Unexpected.toString)(_.category.toString))
          )
      _ <- Console.printLine(report.toJson)
      _ <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            s"Memory extractor smoke failed: provider=${report.provider}, model=${report.model}"
          )
        )
        .unless(report.passed)
    yield ()

  /** 从候选只计算安全布尔值，不让候选正文进入报告或日志。 */
  private def successReport(
      target: ProviderSmokeExample.Target,
      candidates: Chunk[MemoryCandidate]
  ): Report =
    val entries = candidates.collect { case MemoryCandidate(_, MemoryMutation.Upsert(entry)) => entry }
    Report(
      provider = bounded(target.model.provider),
      model = target.modelId.take(200),
      succeeded = true,
      candidateCount = candidates.length,
      allUpserts = entries.length == candidates.length,
      allUserStated = entries.forall(_.evidence == MemoryEvidence.UserStated),
      noSensitive = entries.forall(_.sensitivity != MemorySensitivity.Sensitive),
      errorCategory = None
    )

  /** 读取 smoke 目标；缺失错误只含变量名。 */
  private def required(name: String): IO[AgentError, String] =
    ZIO
      .fromOption(sys.env.get(name).map(_.trim).filter(_.nonEmpty))
      .orElseFail(
        AgentError.InvalidConfiguration(s"Missing environment variable: $name")
      )

  /** 报告标签只允许有限 ASCII，未知值折叠为 other。 */
  private def bounded(value: String): String =
    val normalized = value.trim.toLowerCase
    if normalized.matches("[a-z0-9._-]{1,80}") then normalized else "other"
