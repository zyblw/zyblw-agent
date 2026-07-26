package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.observability.*
import java.time.Instant
import zio.*
import zio.json.ast.Json
import zio.test.*

/** Memory/RAG 来源解析器的租户隔离、过期过滤、引用映射和 fail-closed 测试。 */
object MemoryRagContextSourceResolverSpec extends ZIOSpecDefault:
  /** 创建包含可信用户/租户和最新用户问题的最小耐久状态。 */
  private def state(runId: RunId, sessionId: SessionId, tenant: Option[String]): AgentState =
    AgentState(
      runId = runId,
      sessionId = sessionId,
      agentId = AgentId("context-source-test"),
      status = RunStatus.Running,
      messages = Chunk(AgentMessage.user("阴阳是什么")),
      steps = Chunk.empty,
      usage = UsageSummary(),
      budget = BudgetState(RunLimits(), UsageSummary(), 0),
      pendingApproval = None,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
      version = Version.initial,
      definition = Some(AgentDefinition(AgentId("context-source-test"), "测试", "只根据资料回答")),
      runContext = RunContext(Some("user-a"), tenant, Set("knowledge:read"))
    )

  def spec = suite("MemoryRagContextSourceResolver")(
    test("只注入未过期记忆，并把可信 tenant/scope 传给 Retriever") {
      for
        store     <- ZIO.service[MemoryStore]
        runId     <- RunId.random
        sessionId <- SessionId.random
        captured  <- Ref.make(Option.empty[(String, RetrievalScope, Int)])
        // TestClock 默认从 Unix epoch 开始；先推进时间，才能构造“已创建且刚过期”的合法领域对象。
        _   <- TestClock.adjust(20.seconds)
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        _   <- store.put(
          MemoryScope.Session(sessionId),
          MemoryEntry("阴阳是什么-学习偏好", Json.Str("用户正在学习阴阳基础"), 0.9, Some(runId), now, None)
        )
        _ <- store.put(
          MemoryScope.User(TenantId("tenant-a"), UserId("user-a")),
          MemoryEntry("阴阳旧记录", Json.Str("过期资料"), 1.0, Some(runId), now - 10_000L, Some(now - 1L))
        )
        retriever = new Retriever:
          def retrieve(
              query: String,
              scope: RetrievalScope,
              limit: Int
          ): IO[RetrievalError, RetrievalResult] =
            val chunk = DocumentChunk(
              "chunk-1",
              "doc-1",
              "阴阳是描述相反相成关系的范畴。",
              "book://basic/yinyang",
              scope.tenantId,
              Set("knowledge:read")
            )
            captured
              .set(Some((query, scope, limit)))
              .as(
                RetrievalResult(
                  Chunk(RetrievalHit(chunk, 0.88)),
                  Chunk(Citation("cite-1", chunk.sourceUri, chunk.text, 0.88))
                )
              )
        resolver = MemoryRagContextSourceResolver(
          store,
          retriever,
          MemoryRagContextPolicy(memoryLimit = 4, retrievalLimit = 3)
        )
        sources <- resolver.resolve(
          state(runId, sessionId, Some("tenant-a")),
          AgentDefinition(AgentId("a"), "a", "i")
        )
        invocation <- captured.get
      yield assertTrue(
        sources.memories.map(_.key) == Chunk("阴阳是什么-学习偏好"),
        sources.retrieval.map(_.id) == Chunk("cite-1"),
        sources.retrieval.head.source == "book://basic/yinyang",
        invocation.exists(_._1 == "阴阳是什么"),
        invocation.exists(_._2.tenantId == TenantId("tenant-a")),
        invocation.exists(_._2.permissions == Set("knowledge:read")),
        invocation.exists(_._3 == 3)
      )
    }.provide(MemoryStore.inMemory),
    test("没有 tenant 时不调用 Retriever，防止退化为跨租户搜索") {
      for
        store     <- ZIO.service[MemoryStore]
        runId     <- RunId.random
        sessionId <- SessionId.random
        calls     <- Ref.make(0)
        retriever = new Retriever:
          def retrieve(
              query: String,
              scope: RetrievalScope,
              limit: Int
          ): IO[RetrievalError, RetrievalResult] =
            calls.update(_ + 1).as(RetrievalResult(Chunk.empty, Chunk.empty))
        resolver = MemoryRagContextSourceResolver(store, retriever, MemoryRagContextPolicy())
        sources <- resolver.resolve(state(runId, sessionId, None), AgentDefinition(AgentId("a"), "a", "i"))
        count   <- calls.get
      yield assertTrue(count == 0, sources.retrieval.isEmpty)
    }.provide(MemoryStore.inMemory),
    test("observed resolver 把真实 Memory/RAG 来源接入统一观测但不记录 query 和正文") {
      for
        store      <- ZIO.service[MemoryStore]
        traceSink  <- ZIO.service[InMemoryTelemetry]
        metricSink <- ZIO.service[InMemoryAgentMetrics]
        runId      <- RunId.random
        sessionId  <- SessionId.random
        observer  = AgentOperationTelemetry(traceSink, metricSink)
        retriever = new Retriever:
          def retrieve(
              query: String,
              scope: RetrievalScope,
              limit: Int
          ): IO[RetrievalError, RetrievalResult] =
            val chunk = DocumentChunk(
              "observed-chunk",
              "doc",
              "中医私密正文",
              "book://observed",
              scope.tenantId,
              scope.permissions
            )
            ZIO.succeed(
              RetrievalResult(
                Chunk(RetrievalHit(chunk, 0.9)),
                Chunk(Citation("cite", chunk.sourceUri, chunk.text, 0.9))
              )
            )
        resolver = MemoryRagContextSourceResolver(store, retriever, MemoryRagContextPolicy(), Some(observer))
        _ <- resolver.resolve(
          state(runId, sessionId, Some("tenant-a")),
          AgentDefinition(AgentId("a"), "a", "i")
        )
        traces  <- traceSink.events
        metrics <- metricSink.recorded
      yield assertTrue(
        traces.exists(_.name == "agent.retrieval"),
        traces.count(_.name == "agent.memory") == 2,
        metrics.exists {
          case AgentMetric.RetrievalFinished("retrieve", MetricOutcome.Succeeded, _, 1L) => true
          case _                                                                         => false
        },
        metrics.count {
          case AgentMetric.MemoryOperationFinished("search", MetricOutcome.Succeeded, _) => true
          case _                                                                         => false
        } == 2,
        !traces.toString.contains("阴阳是什么"),
        !traces.toString.contains("中医私密正文")
      )
    }.provide(MemoryStore.inMemory, InMemoryTelemetry.layer, InMemoryAgentMetrics.layer)
  )
