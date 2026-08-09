package com.zyblw.agent.rag

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.observability.AgentOperationTelemetry
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Memory/RAG 动态来源的选择策略。
  *
  * @param memoryLimit
  *   每个 scope 最多读取多少条记忆；零表示关闭记忆
  * @param retrievalLimit
  *   最多注入多少个检索块；零表示关闭检索
  * @param includeSessionMemory
  *   是否读取当前 session 的短期提炼记忆
  * @param includeUserMemory
  *   是否在 tenant+user scope 读取长期用户记忆
  * @param minimumRetrievalScore
  *   低于阈值的候选不会进入模型上下文
  */
final case class MemoryRagContextPolicy(
    memoryLimit: Int = 8,
    retrievalLimit: Int = 6,
    includeSessionMemory: Boolean = true,
    includeUserMemory: Boolean = true,
    minimumRetrievalScore: Double = 0.0
):
  require(memoryLimit >= 0, "memoryLimit 不能为负数")
  require(retrievalLimit >= 0, "retrievalLimit 不能为负数")
  require(minimumRetrievalScore >= -1.0 && minimumRetrievalScore <= 1.0, "minimumRetrievalScore 必须位于 [-1, 1]")

/** 把隔离的长期记忆和带引用的 Retriever 结果转换成 ContextSources。
  *
  * 可信 tenant/user/scopes 只从 `AgentState.runContext` 读取，模型消息不能覆盖。没有 tenant 时检索 fail-closed 为“不注入
  * RAG”，不会退化为跨租户全局搜索。
  *
  * @param memories
  *   长期记忆存储，可由 PostgreSQL 或其他生产实现提供
  * @param retriever
  *   已负责 tenant/permission 前置过滤和重排的检索器
  * @param policy
  *   数量、scope 和最低分数策略
  * @param operationTelemetry
  *   可选语义观测器；存在时记录 Memory/RAG duration、hit 和 Langfuse retriever observation
  */
final class MemoryRagContextSourceResolver(
    memories: MemoryStore,
    retriever: Retriever,
    policy: MemoryRagContextPolicy,
    operationTelemetry: Option[AgentOperationTelemetry] = None
) extends ContextSourceResolver:

  /** 为当前回合解析来源。检索 query 使用最近一条非空 User 消息，避免把工具输出误当用户意图。
    */
  def resolve(state: AgentState, definition: AgentDefinition): IO[ContextError, ContextSources] =
    val query = state.messages.reverse
      .collectFirst {
        case message if message.role == MessageRole.User && message.text.trim.nonEmpty => message.text.trim
      }
      .getOrElse("")
    for
      now      <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      selected <- resolveMemories(state, query, now)
      result   <- resolveRetrieval(state, query)
    yield ContextSources(
      memories = selected,
      retrieval = result.map { case (hit, citation) =>
        ContextDocument(citation.id, hit.chunk.text, citation.sourceUri, Some(hit.score))
      }
    )

  /** 依次读取 session 与 user scope，过滤过期项，再按 importance/key 稳定排序去重。 */
  private def resolveMemories(
      state: AgentState,
      query: String,
      nowEpochMilli: Long
  ): IO[ContextError, Chunk[ContextMemory]] =
    if policy.memoryLimit == 0 || query.isEmpty then ZIO.succeed(Chunk.empty)
    else
      val scopes = Chunk.fromIterable(
        Option.when(policy.includeSessionMemory)(MemoryScope.Session(state.sessionId)) ++
          (for
            tenant <- state.runContext.tenantId
            user   <- state.runContext.userId
            if policy.includeUserMemory
          yield MemoryScope.User(TenantId(tenant), UserId(user)))
      )
      ZIO
        .foreach(scopes) { scope =>
          val search = memories.search(scope, query, policy.memoryLimit)
          operationTelemetry.fold(search)(_.memory(state.runId, "search")(search)).mapError(memoryError)
        }
        .map { groups =>
          val active = groups.flatten.filter(entry => entry.expiresAtEpochMilli.forall(_ > nowEpochMilli))
          val unique = active.foldLeft(Map.empty[String, MemoryEntry]) { (acc, entry) =>
            acc.get(entry.key) match
              case Some(existing) if existing.importance >= entry.importance => acc
              case _                                                         => acc.updated(entry.key, entry)
          }
          Chunk
            .fromIterable(
              unique.values.toList.sortBy(entry => (-entry.importance, entry.key)).take(policy.memoryLimit)
            )
            .map(entry => ContextMemory(entry.key, renderMemory(entry.value), entry.importance))
        }

  /** 有 tenant、非空 query 且 limit>0 时执行权限检索，否则安全地返回空来源。 */
  private def resolveRetrieval(
      state: AgentState,
      query: String
  ): IO[ContextError, Chunk[(RetrievalHit, Citation)]] =
    (state.runContext.tenantId, query.nonEmpty, policy.retrievalLimit > 0) match
      case (Some(tenant), true, true) =>
        val retrieval = retriever
          .retrieve(query, RetrievalScope(TenantId(tenant), state.runContext.scopes), policy.retrievalLimit)
        operationTelemetry
          .fold(retrieval)(_.retrieval(state.runId, "retrieve")(retrieval)(_.hits.length.toLong))
          .mapError(retrievalError)
          .map { result =>
            if result.evidence.supportsGroundedAnswer then
              result.hits.zip(result.citations).filter(_._1.score >= policy.minimumRetrievalScore)
            else Chunk.empty
          }
      case _ => ZIO.succeed(Chunk.empty)

  /** JSON string 记忆展示其值；对象/数组保留 JSON 结构，避免 Scala AST 调试表示进入 prompt。 */
  private def renderMemory(value: Json): String = value match
    case Json.Str(text) => text
    case other          => other.toJson

  /** Memory Store 的持久化错误转换到 Context 错误边界，同时保留安全诊断。 */
  private def memoryError(error: StoreError): ContextError =
    AgentError.ContextBuildFailed(s"长期记忆解析失败: ${error.message}")

  /** Retriever 错误转换到 Context 错误边界；Runtime 会据此终止而不是悄悄遗漏知识依据。 */
  private def retrievalError(error: RetrievalError): ContextError =
    AgentError.ContextBuildFailed(s"知识检索失败: ${error.message}")

object MemoryRagContextSourceResolver:
  /** 从 MemoryStore、Retriever 和策略装配生产 resolver。 */
  val layer: URLayer[MemoryStore & Retriever & MemoryRagContextPolicy, ContextSourceResolver] =
    ZLayer.fromFunction((memories: MemoryStore, retriever: Retriever, policy: MemoryRagContextPolicy) =>
      MemoryRagContextSourceResolver(memories, retriever, policy)
    )

  /** 使用显式策略，减少业务 ZLayer 图中的配置样板。 */
  def configured(policy: MemoryRagContextPolicy): URLayer[MemoryStore & Retriever, ContextSourceResolver] =
    ZLayer.succeed(policy) >>> layer

  /** 生产推荐 Layer：在不记录 query/文档/记忆正文的前提下，把真实 ContextSources 接入统一 Trace 和 Metrics。
    */
  val observedLayer: URLayer[
    MemoryStore & Retriever & MemoryRagContextPolicy & AgentOperationTelemetry,
    ContextSourceResolver
  ] =
    ZLayer.fromFunction(
      (
          memories: MemoryStore,
          retriever: Retriever,
          policy: MemoryRagContextPolicy,
          telemetry: AgentOperationTelemetry
      ) => MemoryRagContextSourceResolver(memories, retriever, policy, Some(telemetry))
    )

  /** 使用显式策略装配带观测的 ContextSources。 */
  def observed(policy: MemoryRagContextPolicy): URLayer[
    MemoryStore & Retriever & AgentOperationTelemetry,
    ContextSourceResolver
  ] = ZLayer.succeed(policy) >>> observedLayer
