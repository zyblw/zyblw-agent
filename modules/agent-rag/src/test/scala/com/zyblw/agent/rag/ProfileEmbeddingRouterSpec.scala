package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object ProfileEmbeddingRouterSpec extends ZIOSpecDefault:
  private val tenant = TenantId("profile-router")

  final private class Counting(val inner: EmbeddingModel, calls: Ref[Int]) extends EmbeddingModel:
    def capabilities                                                            = inner.capabilities
    def descriptor                                                              = inner.descriptor
    def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
      calls.update(_ + 1) *> inner.embed(request)

  private val reranker = new Reranker:
    def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): UIO[Chunk[RetrievalHit]] =
      val _ = query
      ZIO.succeed(hits.take(limit))

  private val buildModel = HashEmbedding(2)
  private val oldModel   = HashEmbedding(3)
  private val buildSpec  = IndexBuildSpec.of(buildModel.descriptor.denseDescriptor, "structure-v1")

  def spec = suite("ProfileEmbeddingRouter")(
    test("active 旧 Profile 用旧模型,构建 Profile 与未知/空 Profile 用构建模型") {
      val router     = ProfileEmbeddingRouter.make(buildModel, buildSpec, Chunk(oldModel))
      val oldProfile = ProfileEmbeddingRouter.profileOf(oldModel, buildSpec)
      for
        old     <- router.forProfile(Some(oldProfile))
        current <- router.forProfile(Some(IndexProfileIds.fromSpec(buildSpec)))
        unknown <- router.forProfile(Some(IndexProfileId("unknown")))
        none    <- router.forProfile(None)
      yield assertTrue(
        old eq oldModel,
        current eq buildModel,
        unknown eq buildModel,
        none eq buildModel,
        oldProfile != IndexProfileIds.fromSpec(buildSpec)
      )
    },
    test("DefaultRetriever 在解析 Profile 之后按 Profile 选择查询模型,query session 也一样") {
      for
        buildCalls <- Ref.make(0)
        oldCalls   <- Ref.make(0)
        build      = Counting(buildModel, buildCalls)
        old        = Counting(oldModel, oldCalls)
        router     = ProfileEmbeddingRouter.make(build, buildSpec, Chunk(old))
        oldProfile = ProfileEmbeddingRouter.profileOf(old, buildSpec)
        store <- InMemoryKnowledgeIndexStore.make
        retriever = DefaultRetriever(build, store, reranker, profileEmbeddings = Some(router))
        pinned    = RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(oldProfile))
        _          <- retriever.retrieve(RetrievalRequest("问题", pinned, 3)).either
        afterOld   <- oldCalls.get
        session    <- retriever.querySession
        _          <- session.retrieve(RetrievalRequest("问题", pinned, 3)).either
        _          <- session.retrieve(RetrievalRequest("问题", pinned, 3)).either
        afterShare <- oldCalls.get
        _         <- retriever.retrieve(RetrievalRequest("问题", RetrievalScope(tenant, Set("read")), 3)).either
        buildUsed <- buildCalls.get
      yield assertTrue(afterOld == 1, afterShare == 2, buildUsed == 1)
    },
    test("historicalSpecs 与 explicitBindings 支持跨切块策略/enricher 变更的蓝绿 Profile 路由") {
      val legacySpec    = IndexBuildSpec.of(oldModel.descriptor.denseDescriptor, "legacy-chunker-v0")
      val legacyProfile = IndexProfileIds.fromSpec(legacySpec)
      val explicitId    = IndexProfileId("custom-legacy-profile")
      val router        = ProfileEmbeddingRouter.make(
        buildModel,
        buildSpec,
        candidates = Nil,
        historicalSpecs = Chunk(legacySpec -> oldModel),
        explicitBindings = Map(explicitId -> oldModel)
      )
      for
        fromSpec     <- router.forProfile(Some(legacyProfile))
        fromExplicit <- router.forProfile(Some(explicitId))
        current      <- router.forProfile(Some(IndexProfileIds.fromSpec(buildSpec)))
      yield assertTrue(
        fromSpec eq oldModel,
        fromExplicit eq oldModel,
        current eq buildModel
      )
    },
    test("R8：从 KnowledgeIndexStore 已持久化 Profile 规格动态绑定旧模型（含同时变更模型与切块策略）并识别缺失模型") {
      val legacySpec  = IndexBuildSpec.of(oldModel.descriptor.denseDescriptor, "legacy-chunker-v0")
      val missingSpec = IndexBuildSpec.of(
        EmbeddingProviderDescriptor("missing-provider", "missing-model", 1024, 32, false),
        "legacy-chunker-v0"
      )
      val docKey  = KnowledgeDocumentKey(tenant, "doc-1", KnowledgeSpaceId("space-1"))
      val lineage = DocumentLineage(
        sourceId = "src-1",
        sourceRevisionId = "rev-1",
        sourceSha256 = "a" * 64,
        sourceMediaType = "text/plain",
        parserId = "plain",
        artifactSha256 = "b" * 64,
        structureSha256 = "c" * 64,
        textSha256 = "d" * 64
      )
      for
        store <- InMemoryKnowledgeIndexStore.make
        build <- store.begin(
          BeginKnowledgeIndex(
            key = docKey,
            ingestionId = "ing-legacy",
            sourceUri = "memory://doc-1",
            lineage = lineage,
            permissions = Set("read"),
            metadata = Map.empty,
            buildSpec = legacySpec
          )
        )
        persisted <- store.registeredProfiles
        (bound, missing) = ProfileEmbeddingRouter.bindPersistedSpecs(
          persisted :+ (IndexProfileIds.fromSpec(missingSpec) -> missingSpec),
          Chunk(buildModel, oldModel)
        )
        router = ProfileEmbeddingRouter.make(
          buildModel,
          buildSpec,
          candidates = Chunk(oldModel),
          explicitBindings = bound,
          store = Some(store)
        )
        resolvedOld     <- router.forProfile(Some(build.profileId))
        resolvedCurrent <- router.forProfile(Some(IndexProfileIds.fromSpec(buildSpec)))
      yield assertTrue(
        persisted.contains(build.profileId -> legacySpec),
        resolvedOld eq oldModel,
        resolvedCurrent eq buildModel,
        missing.map(_._2) == Chunk(missingSpec)
      )
    }
  )
