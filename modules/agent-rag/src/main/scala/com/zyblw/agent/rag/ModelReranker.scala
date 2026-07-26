package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** Reranker Provider 的静态能力描述。
  *
  * @param provider
  *   稳定厂商标识，例如 `cohere`、`jina` 或业务内 cross-encoder 服务名
  * @param model
  *   精确模型/版本；升级模型必须形成新的 eval 基线
  * @param maxCandidates
  *   单次 Provider 请求允许的最大候选数
  * @param maxQueryCodePoints
  *   Query 可发送的最大 Unicode code point 数
  * @param maxDocumentCodePoints
  *   每个候选正文可发送的最大 Unicode code point 数
  */
final case class RerankerDescriptor(
    provider: String,
    model: String,
    maxCandidates: Int,
    maxQueryCodePoints: Int,
    maxDocumentCodePoints: Int
):
  require(provider.trim.nonEmpty && provider.length <= 200, "Reranker provider 长度必须位于 1..200")
  require(model.trim.nonEmpty && model.length <= 200, "Reranker model 长度必须位于 1..200")
  require(maxCandidates > 0, "Reranker maxCandidates 必须为正数")
  require(maxQueryCodePoints > 0 && maxDocumentCodePoints > 0, "Reranker 文本上限必须为正数")

/** 发给 Provider 的单个候选。
  *
  * `candidateId` 是本次请求内由框架生成的无业务含义序号，不使用 documentId/sourceUri，减少外部 Provider 日志中的 业务标识。`originalRank/score`
  * 供某些自建服务做融合，但 Provider 不能据此返回新文档。
  */
final case class RerankCandidate(candidateId: String, text: String, originalRank: Int, originalScore: Double)

/** 一次模型重排请求；topN 是希望返回的最大结果数。 */
final case class RerankRequest(query: String, candidates: Chunk[RerankCandidate], topN: Int)

/** Provider 返回的候选分数。
  *
  * 分数契约固定为 `[0, 1]`，这样 Context 阈值、eval 和不同 Adapter 的指标含义一致。返回原始 logit 的 Adapter 必须先
  * 在自身边界按厂商文档归一化，不能把不可比较的值直接泄漏到框架。
  */
final case class RerankScore(candidateId: String, relevance: Double):
  require(candidateId.trim.nonEmpty, "Rerank candidateId 不能为空")
  require(
    java.lang.Double.isFinite(relevance) && relevance >= 0.0 && relevance <= 1.0,
    "Rerank relevance 必须位于 [0, 1]"
  )

/** Reranker 的可选 token 用量；缺失时保持 None，不能伪造为零。 */
final case class RerankUsage(inputTokens: Long, totalTokens: Long):
  require(inputTokens >= 0L && totalTokens >= inputTokens, "Rerank usage 必须非负且 total >= input")

/** 厂商按检索单元而不是 token 计费时的用量。
  *
  * `searchUnits` 不能塞进 `RerankUsage.inputTokens`：两者单位不同，混用会让成本预算和指标产生看似精确的错误数据。
  */
final case class RerankBilling(searchUnits: Long):
  require(searchUnits >= 0L, "Rerank billed search units 不能为负数")

/** 一次 Provider 调用结果。
  *
  * @param scores
  *   Provider 选中的结果；允许少于 topN，顺序不作为最终排序依据
  * @param usage
  *   厂商明确返回的用量
  * @param providerRequestId
  *   脱敏后的厂商请求 ID，仅用于排障关联
  * @param billing
  *   厂商明确返回的非 token 计费单元
  */
final case class RerankResponse(
    scores: Chunk[RerankScore],
    usage: Option[RerankUsage] = None,
    providerRequestId: Option[String] = None,
    billing: Option[RerankBilling] = None
)

/** 最窄的 Reranker Provider SPI。
  *
  * HTTP 鉴权、429/5xx 分类、响应上限和连接取消属于 Adapter；候选身份、超时、降级与输出验证属于上层 `ModelReranker`。这种分层让 TestKit 可以完全模拟
  * Provider，而不需要在 Retriever 中识别厂商。
  */
trait RerankerModel:
  /** Provider/模型和请求上限。 */
  def descriptor: RerankerDescriptor

  /** 对已截断、有界的候选执行一次重排调用。 */
  def score(request: RerankRequest): IO[RetrievalError, RerankResponse]

/** Reranker 不可用时的策略；安全敏感知识问答建议使用 FailClosed。 */
enum RerankerFailureMode:
  /** 保留 hybrid 原顺序继续服务，适合可接受质量降级的普通搜索。 */
  case FailOpen

  /** 直接失败，适合引用质量是硬门禁的问答或医疗学习场景。 */
  case FailClosed

/** 模型重排的运行策略。
  *
  * @param timeout
  *   单次 Provider 总预算；超时会中断底层 Fiber/HTTP Body
  * @param failureMode
  *   Provider typed error/超时时是否退回原 hybrid 排名
  * @param maxCandidates
  *   框架允许送给 Provider 的二次上限；运行时还会与 Provider descriptor 取较小值
  */
final case class ModelRerankerPolicy(
    timeout: Duration = 3.seconds,
    failureMode: RerankerFailureMode = RerankerFailureMode.FailClosed,
    maxCandidates: Int = 50
):
  require(timeout > Duration.Zero, "Reranker timeout 必须为正数")
  require(maxCandidates > 0, "Reranker maxCandidates 必须为正数")

/** 把 `RerankerModel` 适配为 Retriever 使用的 `Reranker`，并实施生产治理。
  *
  * 关键安全性质：
  *
  *   1. 候选只来自已经 tenant/permission 过滤的 VectorStore；Provider 只返回临时 candidateId；
  *   2. Query/正文按 Unicode code point 截断，避免切断代理对，同时限制数据外发与费用；
  *   3. 结果必须是已请求 ID 的无重复子集，分数必须归一化且有限；
  *   4. 相同分数按原始 rank 稳定排序，异步响应顺序不会造成结果漂移；
  *   5. ZIO interruption 不会被 FailOpen 吞掉，客户端取消仍传播到底层 Adapter。
  *
  * @param model
  *   可替换的真实 Provider Adapter 或测试桩
  * @param policy
  *   超时、候选上限和故障策略
  */
final class ModelReranker(model: RerankerModel, policy: ModelRerankerPolicy = ModelRerankerPolicy())
    extends Reranker:
  /** 重排候选并保留原始排名信号。
    *
    * @param query
    *   原始用户检索 query；空白 query 明确失败
    * @param hits
    *   已通过 tenant/permission 过滤并按 hybrid 分数排列的候选
    * @param limit
    *   最终最多返回数量；非正数或空候选不调用 Provider
    */
  def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): IO[RetrievalError, Chunk[RetrievalHit]] =
    if limit <= 0 || hits.isEmpty then ZIO.succeed(Chunk.empty)
    else if query.trim.isEmpty then ZIO.fail(AgentError.RetrievalFailed("Reranker query 不能为空"))
    else
      // 策略可以跨 Provider 复用；实际请求始终服从框架上限与具体 Provider 能力的较小值。
      val selected   = hits.take(Math.min(policy.maxCandidates, model.descriptor.maxCandidates))
      val candidates = selected.zipWithIndex.map { case (hit, index) =>
        RerankCandidate(
          candidateId = s"candidate-$index",
          text = truncate(hit.chunk.text, model.descriptor.maxDocumentCodePoints),
          originalRank = index + 1,
          originalScore = hit.score
        )
      }
      val request = RerankRequest(
        query = truncate(query, model.descriptor.maxQueryCodePoints),
        candidates = candidates,
        topN = Math.min(limit, selected.length)
      )
      val call = model
        .score(request)
        .timeoutFail(AgentError.RetrievalFailed("Reranker Provider 超时", retryable = true))(policy.timeout)
        .flatMap(response => validateAndMap(response, selected, request.topN))
      call.catchAll { error =>
        policy.failureMode match
          case RerankerFailureMode.FailOpen   => ZIO.succeed(hits.take(limit))
          case RerankerFailureMode.FailClosed => ZIO.fail(error)
      }

  /** 校验返回身份集合并按 relevance、原始 rank 形成确定性结果。 */
  private def validateAndMap(
      response: RerankResponse,
      selected: Chunk[RetrievalHit],
      topN: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    val parsed =
      response.scores.map(score => parseCandidateIndex(score.candidateId).map(_ -> score.relevance))
    ZIO.fromEither(sequence(parsed)).flatMap { indexed =>
      val unique  = indexed.map(_._1).distinct.length == indexed.length
      val inRange = indexed.forall((index, _) => index >= 0 && index < selected.length)
      if !unique || !inRange || indexed.length > topN then
        ZIO.fail(AgentError.RetrievalFailed("Reranker Provider 返回未知、重复或超量 candidateId"))
      else
        val ranked = indexed.sortBy { case (index, relevance) => (-relevance, index) }.zipWithIndex.map {
          case ((index, relevance), rerankIndex) =>
            val original = selected(index)
            original.copy(
              score = relevance,
              signals = original.signals ++ Map(
                "preRerankScore" -> original.score,
                "preRerankRank"  -> (index + 1).toDouble,
                "rerankScore"    -> relevance,
                "rerankRank"     -> (rerankIndex + 1).toDouble
              )
            )
        }
        ZIO.succeed(ranked)
    }

  /** 只接受本框架生成的 candidate-N 格式，不用宽松数字解析掩盖 Provider schema 漂移。 */
  private def parseCandidateIndex(value: String): Either[RetrievalError, Int] =
    value
      .stripPrefix("candidate-")
      .toIntOption
      .filter(_ => value.startsWith("candidate-"))
      .toRight(AgentError.RetrievalFailed("Reranker Provider candidateId 格式无效"))

  /** 把一组 Either 聚合成同序 Chunk；首个协议错误稳定胜出。 */
  private def sequence[A](values: Chunk[Either[RetrievalError, A]]): Either[RetrievalError, Chunk[A]] =
    values.foldLeft[Either[RetrievalError, Chunk[A]]](Right(Chunk.empty)) { (all, next) =>
      for
        accumulated <- all
        value       <- next
      yield accumulated :+ value
    }

  /** 按 Unicode code point 截断，不会在 UTF-16 surrogate pair 中间切开字符。 */
  private def truncate(value: String, maxCodePoints: Int): String =
    if value.codePointCount(0, value.length) <= maxCodePoints then value
    else value.substring(0, value.offsetByCodePoints(0, maxCodePoints))

object ModelReranker:
  /** 使用默认严格策略构造 Reranker Layer。 */
  val layer: URLayer[RerankerModel, Reranker] =
    ZLayer.fromFunction((model: RerankerModel) => ModelReranker(model): Reranker)

  /** 使用显式业务策略构造 Layer。 */
  def configured(policy: ModelRerankerPolicy): URLayer[RerankerModel, Reranker] =
    ZLayer.fromFunction((model: RerankerModel) => ModelReranker(model, policy): Reranker)
