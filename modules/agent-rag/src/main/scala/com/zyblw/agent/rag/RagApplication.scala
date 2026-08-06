package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

/** 业务侧 RAG 主入口的资源与请求治理。
  *
  * 低层 SPI 仍可独立使用；普通业务优先注入 `RagApplication`，避免在 Controller、Job 和 Tool 中重复拼接 Loader、Indexer、Retriever 以及查询上限。
  */
final case class RagApplicationConfig(
    defaultTopK: Int = 5,
    maxTopK: Int = 20,
    maxQueryCodePoints: Int = 4000
):
  require(defaultTopK > 0, "RAG defaultTopK 必须为正数")
  require(maxTopK >= defaultTopK && maxTopK <= 100, "RAG maxTopK 必须位于 defaultTopK..100")
  require(
    maxQueryCodePoints > 0 && maxQueryCodePoints <= 100_000,
    "RAG maxQueryCodePoints 必须位于 1..100000"
  )

/** 一次经过可信调用上下文构造的知识查询。
  *
  * `scope` 必须来自服务端认证上下文，不能接受模型或客户端直接填写 tenant。`limit=None` 使用应用默认值。
  */
final case class RagQuery(
    text: String,
    scope: RetrievalScope,
    limit: Option[Int] = None
)

/** 统一知识摄取和查询的业务门面。
  *
  * 它不建立第二套 RAG 实现：摄取完整委托给 `DocumentIngestionService`，查询完整委托给 `Retriever`。 门面只固定推荐主路径、统一输入上限，并让 ZLayer
  * 在编译期证明两侧依赖均已装配。
  */
final class RagApplication(
    ingestion: DocumentIngestionService,
    retriever: Retriever,
    config: RagApplicationConfig = RagApplicationConfig(),
    /** 可选的运行时工作点；None 表示始终使用 `config.defaultTopK`。
      *
      * 用 `Option` 而不是给一个静态默认解析器，是为了让"未接入管理面"与"接入后覆盖恰好等于默认值"在代码里 可区分：前者不该受管理台影响，后者应该。
      */
    policies: Option[RetrievalPolicySource] = None
):

  /** 单文档摄取，保留已配置的 FailFast/Continue 语义。 */
  def ingestOne(request: DocumentIngestionRequest): IO[RetrievalError, DocumentIngestionOutcome] =
    ingestion.ingestOne(request)

  /** 有背压、有界并发的批量摄取。 */
  def ingest(
      requests: ZStream[Any, RetrievalError, DocumentIngestionRequest]
  ): ZStream[Any, RetrievalError, DocumentIngestionOutcome] =
    ingestion.ingest(requests)

  /** 在进入 Embedding/数据库前验证 query 与 topK，避免错误调用消耗远程额度或创建超大候选池。
    *
    * `maxTopK` 始终取自部署基线，即使运行时覆盖把默认 topK 调到更高：覆盖层移动工作点，基线定义安全边界。 因此一个越界的覆盖会在这里被拒绝，而不是悄悄放大候选池。
    */
  def retrieve(query: RagQuery): IO[RetrievalError, RetrievalResult] =
    val normalized  = query.text.trim
    val codePoints  = normalized.codePointCount(0, normalized.length)
    val defaultTopK = policies.fold(config.defaultTopK)(_.current().topK)
    val limit       = query.limit.getOrElse(defaultTopK)
    if normalized.isEmpty then ZIO.fail(AgentError.RetrievalFailed("RAG query 不能为空"))
    else if codePoints > config.maxQueryCodePoints then
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"RAG query 长度 $codePoints 超过上限 ${config.maxQueryCodePoints}"
        )
      )
    else if limit <= 0 || limit > config.maxTopK then
      ZIO.fail(AgentError.RetrievalFailed(s"RAG topK 必须位于 1..${config.maxTopK}"))
    else retriever.retrieve(normalized, query.scope, limit)

object RagApplication:
  /** 默认业务装配；依赖缺失会在 ZLayer 图构建时暴露。 */
  val layer: URLayer[DocumentIngestionService & Retriever, RagApplication] =
    configured(RagApplicationConfig())

  def configured(
      config: RagApplicationConfig
  ): URLayer[DocumentIngestionService & Retriever, RagApplication] =
    ZLayer.fromFunction((ingestion: DocumentIngestionService, retriever: Retriever) =>
      RagApplication(ingestion, retriever, config)
    )

  /** 接入运行时覆盖的装配；默认 topK 改由管理台控制，硬上限仍由 `config` 固定。 */
  def governed(
      config: RagApplicationConfig = RagApplicationConfig()
  ): URLayer[DocumentIngestionService & Retriever & RetrievalPolicySource, RagApplication] =
    ZLayer.fromFunction(
      (ingestion: DocumentIngestionService, retriever: Retriever, policies: RetrievalPolicySource) =>
        RagApplication(ingestion, retriever, config, Some(policies))
    )
