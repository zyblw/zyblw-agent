package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 测试用的构建规格、谱系与发布协议捷径；生产代码应由 `KnowledgeIndexer` 推导这些值。 */
object KnowledgeFixtures:
  def buildSpec(
      model: String = "hash-2",
      dimension: Int = 2,
      strategy: String = "s-v1",
      provider: String = "hash"
  ): IndexBuildSpec =
    IndexBuildSpec(provider, model, dimension, IndexBuildSpec.UndeclaredTokenizer, None, strategy, "identity")

  def lineage(documentId: String, revision: String = "rev-1", text: String = "a" * 64): DocumentLineage =
    DocumentLineage(
      documentId,
      revision,
      text,
      "text/plain",
      DocumentLineage.DirectParserId,
      text,
      text,
      text
    )

  def begin(
      key: KnowledgeDocumentKey,
      ingestionId: String,
      indexSpec: IndexBuildSpec = buildSpec(),
      revision: String = "rev-1",
      text: String = "a" * 64,
      permissions: Set[String] = Set("read"),
      targetProfileId: Option[IndexProfileId] = None,
      expectation: ActiveVersionExpectation = ActiveVersionExpectation.AnyVersion
  ): BeginKnowledgeIndex =
    BeginKnowledgeIndex(
      key,
      ingestionId,
      s"book://${key.documentId}",
      lineage(key.documentId, revision, text),
      permissions,
      Map.empty,
      indexSpec,
      expectation,
      targetProfileId
    )

  def chunk(build: KnowledgeIndexBuild, id: String, text: String = ""): DocumentChunk =
    DocumentChunk(
      id,
      build.key.documentId,
      if text.isEmpty then id else text,
      s"book://${build.key.documentId}",
      build.key.tenantId,
      Set("read")
    ).copy(catalogVersion = build.version, knowledgeSpaceId = Some(build.knowledgeSpaceId))

  /** 暂存给定块并以其真实摘要发布。 */
  def publish(
      store: KnowledgeIndexStore,
      build: KnowledgeIndexBuild,
      chunks: Chunk[DocumentChunk],
      vector: Embedding = Embedding(Chunk(1.0f, 0.0f))
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    store.stage(build, chunks.map(IndexedChunk(_, vector))) *> store.activate(
      build,
      ChunkSetDigest.of(chunks)
    )

  def beginAndPublish(
      store: KnowledgeIndexStore,
      request: BeginKnowledgeIndex,
      chunkIds: String*
  ): IO[RetrievalError, KnowledgeIndexBuild] =
    for
      build <- store.begin(request)
      _     <- publish(store, build, Chunk.fromIterable(chunkIds).map(chunk(build, _)))
    yield build

  def publication(manifests: KnowledgeIndexManifest*): ProfilePublication =
    val census = Chunk.fromIterable(manifests).map(ProfileDocument.fromManifest)
    ProfilePublication(census, "contract-evaluation", ProfilePublication.digest(census), qualityPassed = true)

  /** 由目标 Profile 的真实 manifest 生成评测 census。 */
  def publicationFor(
      store: InMemoryKnowledgeIndexStore,
      profileId: IndexProfileId
  ): UIO[ProfilePublication] =
    store.manifests.map { all =>
      val target = ProfilePublication.latestByDocument(all.filter(_.build.profileId == profileId))
      publication(target*)
    }
