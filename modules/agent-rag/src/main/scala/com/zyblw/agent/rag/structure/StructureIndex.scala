package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 与 Embedding 构建规格独立；只改变结构规格可以重建树而不调用 Embedding。 */
final case class StructureBuildSpec(
    builderVersion: String = "canonical-sections-v1",
    summary: Option[NodeSummarySpec] = None
) derives JsonCodec:
  require(builderVersion.matches("[A-Za-z0-9._-]{1,100}"), "structure builder version 无效")
  lazy val profileId: String = KnowledgeDigest.sha256(this.toJson)

/** 一个结构产物只对应一个精确的 Chunk 集合，不能仅凭 sourceRevisionId 混用版本。 */
final case class StructureBinding(
    tenantId: String,
    spaceId: String,
    documentId: String,
    chunkProfileId: String,
    indexVersion: Long,
    ingestionId: String,
    sourceRevisionId: String,
    structureSha256: String,
    chunkSetSha256: String
) derives JsonCodec:
  require(
    Chunk(tenantId, spaceId, documentId, chunkProfileId, ingestionId, sourceRevisionId).forall(
      _.trim.nonEmpty
    ),
    "structure binding 身份不能为空"
  )
  require(
    indexVersion > 0 && Chunk(structureSha256, chunkSetSha256).forall(_.matches("[0-9a-f]{64}")),
    "structure binding 版本/摘要无效"
  )
  def key: KnowledgeDocumentKey =
    KnowledgeDocumentKey(TenantId(tenantId), documentId, KnowledgeSpaceId(spaceId))
  def matches(chunk: DocumentChunk): Boolean =
    chunk.tenantId.value == tenantId && chunk.documentId == documentId &&
      chunk.knowledgeSpaceId.exists(_.value == spaceId) && chunk.profileId.exists(
        _.value == chunkProfileId
      ) &&
      chunk.catalogVersion == indexVersion && chunk.sourceRevisionId.contains(sourceRevisionId)
  def matches(manifest: KnowledgeIndexManifest): Boolean =
    val build = manifest.build
    build.key == key && build.profileId.value == chunkProfileId && build.version == indexVersion &&
    build.ingestionId == ingestionId && build.lineage.sourceRevisionId == sourceRevisionId &&
    build.lineage.structureSha256 == structureSha256 &&
    manifest.chunkSetSha256.forall(_ == chunkSetSha256)

final case class StructureNode(
    section: DocumentSection,
    blockIds: Chunk[String],
    chunkIds: Chunk[String],
    summary: Option[String] = None
) derives JsonCodec

/** 不含原文、向量或模型推理内容。节点映射只包含本章节直接拥有的块，避免祖先复制整个子树。 */
final case class StructureSnapshot(
    binding: StructureBinding,
    spec: StructureBuildSpec,
    nodes: Chunk[StructureNode]
) derives JsonCodec:
  lazy val generation: String                    = KnowledgeDigest.sha256(this.toJson)
  lazy val nodesById: Map[String, StructureNode] = nodes.map(n => n.section.id -> n).toMap

  def rootNodes: Chunk[StructureNode] =
    nodes.filter(_.section.parentId.isEmpty)

  private lazy val childrenById                      = nodes.groupBy(_.section.parentId)
  def children(nodeId: String): Chunk[StructureNode] =
    childrenById.getOrElse(Some(nodeId), Chunk.empty)

  def ancestors(nodeId: String): Chunk[StructureNode] =
    def loop(currentId: Option[String], acc: List[StructureNode]): List[StructureNode] =
      currentId.flatMap(nodesById.get) match
        case Some(node) => loop(node.section.parentId, node :: acc)
        case None       => acc
    Chunk.fromIterable(loop(nodesById.get(nodeId).flatMap(_.section.parentId), Nil))

  def breadcrumbs(nodeId: String): Chunk[String] =
    val path = ancestors(nodeId) ++ Chunk.fromIterable(nodesById.get(nodeId))
    path.map(_.section.title)

  def subtreeChunkIds(nodeId: String): Set[String] =
    def collect(id: String): Set[String] =
      nodesById.get(id) match
        case Some(node) =>
          val childChunks = children(id).flatMap(c => collect(c.section.id))
          node.chunkIds.toSet ++ childChunks
        case None => Set.empty
    collect(nodeId)

  /** 同时验证反序列化产物；不能把 persisted JSON 当作已验证对象。 */
  def validate: Either[String, Unit] =
    val sections     = nodes.map(_.section)
    val byId         = sections.map(s => s.id -> s).toMap
    val mappingCount = nodes.foldLeft(0L)((count, node) => count + node.chunkIds.length)
    val blockCount   = nodes.foldLeft(0L)((count, node) => count + node.blockIds.length)
    if nodes.isEmpty || nodes.length > StructureBuilder.MaxNodes then Left("structure node limit")
    else if byId.size != nodes.length || sections.map(_.ordinal).distinct.length != nodes.length then
      Left("structure duplicate identity/order")
    else if nodes.exists(n =>
        n.chunkIds.distinct.length != n.chunkIds.length || n.blockIds.distinct.length != n.blockIds.length
      )
    then Left("structure duplicate mapping")
    else if mappingCount == 0 || mappingCount > StructureBuilder.MaxMappings || blockCount > StructureBuilder.MaxMappings ||
      nodes.exists(_.chunkIds.exists(id => id.length > 1200 || !ChunkSetDigest.validChunkId(id))) ||
      nodes.exists(_.blockIds.exists(id => id.trim.isEmpty || id.length > 1000))
    then Left("structure mapping limit/identity")
    else if nodes.flatMap(_.blockIds).distinct.length != blockCount then
      Left("structure block has multiple owners")
    else if sections.exists(s => s.parentId.exists(id => !byId.contains(id))) then
      Left("structure orphan parent")
    else if sections.exists(s =>
        s.parentId.exists { id =>
          val parent = byId(id)
          parent.level >= s.level || parent.ordinal >= s.ordinal ||
          parent.pageStart.exists(p => s.pageStart.exists(_ < p)) ||
          parent.pageEnd.exists(p => s.pageEnd.exists(_ > p))
        }
      )
    then Left("structure parent order/level/page integrity")
    else if nodes.exists(_.summary.exists(s => s.trim.isEmpty || s.length > 4000)) then
      Left("structure summary limit")
    else Right(())

object StructureBuilder:
  val MaxNodes    = 20000
  val MaxMappings = 200000

  /** 严格使用 Block 身份建立多对多映射；标题同名或 chunk overlap 不会合并原书节点。 */
  def build(
      document: SourceDocument,
      build: KnowledgeIndexBuild,
      chunks: Chunk[DocumentChunk],
      spec: StructureBuildSpec = StructureBuildSpec()
  ): Either[String, StructureSnapshot] =
    for
      structure <- document.structure.toRight("structure unavailable")
      _         <- Either.cond(
        document.id == build.key.documentId && DocumentLineage.structureSha256(
          document
        ) == build.lineage.structureSha256,
        (),
        "structure source mismatch"
      )
      _ <- Either.cond(
        structure.sections.nonEmpty && structure.sections.length <= MaxNodes,
        (),
        "structure sections unavailable/limit"
      )
      _ <- Either.cond(
        structure.blocks.nonEmpty && structure.blocks.length <= MaxMappings && chunks.nonEmpty && chunks.length <= MaxMappings && chunks
          .map(_.id)
          .distinct
          .length == chunks.length,
        (),
        "structure blocks/chunks limit"
      )
      digest  = ChunkSetDigest.of(chunks)
      binding = StructureBinding(
        build.key.tenantId.value,
        build.knowledgeSpaceId.value,
        document.id,
        build.profileId.value,
        build.version,
        build.ingestionId,
        build.lineage.sourceRevisionId,
        build.lineage.structureSha256,
        digest.sha256
      )
      _ <- Either.cond(chunks.forall(binding.matches), (), "structure chunk binding mismatch")
      sectionIds = structure.sections.map(_.id).toSet
      blocks     = structure.blocks.map(b => b.id -> b).toMap
      _ <- Either.cond(
        structure.blocks.forall(_.parentId.exists(sectionIds.contains)),
        (),
        "structure block coverage incomplete"
      )
      _ <- Either.cond(
        chunks.forall(c => c.lineage.exists(l => l.blockIds.nonEmpty && l.blockIds.forall(blocks.contains))),
        (),
        "structure chunk mapping incomplete"
      )
      _ <- Either.cond(
        chunks.foldLeft(0L)((count, chunk) =>
          count + chunk.lineage.fold(0)(_.blockIds.length)
        ) <= MaxMappings,
        (),
        "structure mapping limit"
      )
      mappedBlocks = chunks.flatMap(_.lineage.toSeq.flatMap(_.blockIds)).toSet
      _ <- Either.cond(
        structure.blocks.forall(b => mappedBlocks.contains(b.id)),
        (),
        "structure unmapped block"
      )
      mappings = chunks.flatMap(c =>
        c.lineage.toSeq.flatMap(_.blockIds).map(id => (blocks(id).parentId.get, c.id))
      )
      _ <- Either.cond(mappings.length <= MaxMappings, (), "structure mapping limit")
      bySection       = mappings.groupBy(_._1)
      blocksBySection = structure.blocks.groupBy(_.parentId.get)
      nodes           = structure.sections
        .sortBy(_.ordinal)
        .map(s =>
          StructureNode(
            s,
            blocksBySection.getOrElse(s.id, Chunk.empty).sortBy(_.ordinal).map(_.id),
            bySection.getOrElse(s.id, Chunk.empty).map(_._2).distinct
          )
        )
      snapshot = StructureSnapshot(binding, spec, nodes)
      _ <- snapshot.validate
    yield snapshot

  def buildWithSummaries(
      document: SourceDocument,
      build: KnowledgeIndexBuild,
      chunks: Chunk[DocumentChunk],
      spec: StructureBuildSpec = StructureBuildSpec(),
      summarizer: NodeSummarizer,
      summarySpec: NodeSummarySpec = NodeSummarySpec("internal", "rule-v1")
  ): IO[RetrievalError, StructureSnapshot] =
    for
      baseSnapshot <- ZIO
        .fromEither(this.build(document, build, chunks, spec))
        .mapError(AgentError.RetrievalFailed(_))
      structure         = document.structure.get
      nonLeafSectionIds = structure.sections.flatMap(_.parentId).toSet
      byId              = structure.blocks.map(b => b.id -> b).toMap
      nodesWithSummaries <- ZIO
        .foreachPar(baseSnapshot.nodes) { node =>
          val isLeaf                             = !nonLeafSectionIds.contains(node.section.id)
          def collect(id: String): Chunk[String] =
            baseSnapshot
              .nodesById(id)
              .blockIds ++ baseSnapshot.children(id).flatMap(child => collect(child.section.id))
          val allBlocks = collect(node.section.id).distinct.flatMap(byId.get).sortBy(_.ordinal)
          // ponytail: cap summaries at 128 representative blocks; retrieval still indexes every original block.
          val sectionBlocks =
            if allBlocks.length <= 128 then allBlocks
            else
              Chunk.fromIterable(
                (0 until 128).map(i => allBlocks((i.toLong * (allBlocks.length - 1) / 127).toInt))
              )
          summarizer
            .summarize(
              node.section,
              sectionBlocks,
              isLeaf,
              summarySpec
                .copy(cacheNamespace = baseSnapshot.binding.tenantId + ":" + baseSnapshot.binding.spaceId)
            )
            .map { summaryOpt =>
              node.copy(summary = summaryOpt)
            }
        }
        .withParallelism(4)
      snapshotWithSummaries = baseSnapshot.copy(nodes = nodesWithSummaries)
      _ <- ZIO.fromEither(snapshotWithSummaries.validate).mapError(AgentError.RetrievalFailed(_))
    yield snapshotWithSummaries

/** 幂等保存完整不可变产物；读者只可读取 Ready/active 且 ACL 允许的精确 binding。 */
trait StructureStore:
  def put(snapshot: StructureSnapshot): IO[RetrievalError, Unit]
  def get(
      documentId: String,
      indexVersion: Long,
      spec: StructureBuildSpec,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]]

  /** Ingestion-only resume lookup; retrieval must use get/getActive (Ready/active only). */
  def getForBuild(
      base: StructureSnapshot,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    get(base.binding.documentId, base.binding.indexVersion, base.spec, scope)

  /** Optional authorized active lookup; adapters without catalog support return unavailable. */
  def getActive(
      documentId: String,
      spec: StructureBuildSpec,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    val _ = (documentId, spec, scope)
    ZIO.succeed(None)

object StructureStore:
  def authorized(binding: StructureBinding, scope: RetrievalScope): Boolean =
    binding.tenantId == scope.tenantId.value && binding.spaceId == scope.spaceId.value &&
      scope.pinnedProfileId.exists(_.value == binding.chunkProfileId)

  def readable(manifest: KnowledgeIndexManifest, binding: StructureBinding, scope: RetrievalScope): Boolean =
    authorized(binding, scope) && binding.matches(manifest) && manifest.active &&
      manifest.status == KnowledgeIndexStatus.Ready && manifest.chunkSetSha256.contains(
        binding.chunkSetSha256
      ) &&
      manifest.permissions.subsetOf(scope.permissions)

/** 测试用存储；原 manifest 仍是权限、发布与撤回的唯一事实源。 */
final class InMemoryStructureStore private (
    index: KnowledgeIndexStore,
    state: Ref[Map[(KnowledgeDocumentKey, Long, String), StructureSnapshot]]
) extends StructureStore:
  def put(snapshot: StructureSnapshot): IO[RetrievalError, Unit] =
    for
      _        <- ZIO.fromEither(snapshot.validate).mapError(AgentError.RetrievalFailed(_))
      manifest <- index.find(snapshot.binding.key, snapshot.binding.ingestionId)
      _        <- ZIO
        .fail(AgentError.RetrievalFailed("structure manifest mismatch"))
        .unless(
          manifest.exists(m =>
            snapshot.binding.matches(
              m
            ) && (m.status == KnowledgeIndexStatus.Building || (m.status == KnowledgeIndexStatus.Ready && m.active))
          )
        )
      result <- state.modify { current =>
        val key = (snapshot.binding.key, snapshot.binding.indexVersion, snapshot.spec.profileId)
        current.get(key) match
          case Some(existing) if existing != snapshot => (false, current)
          case _                                      => (true, current.updated(key, snapshot))
      }
      _ <- ZIO.fail(AgentError.RetrievalFailed("structure immutable generation conflict")).unless(result)
    yield ()

  def get(
      documentId: String,
      indexVersion: Long,
      spec: StructureBuildSpec,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    read(documentId, indexVersion, spec, scope, false)

  override def getForBuild(
      base: StructureSnapshot,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    read(base.binding.documentId, base.binding.indexVersion, base.spec, scope, true)

  private def read(
      documentId: String,
      indexVersion: Long,
      spec: StructureBuildSpec,
      scope: RetrievalScope,
      allowBuilding: Boolean
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    state.get.flatMap { current =>
      current.get(
        (KnowledgeDocumentKey(scope.tenantId, documentId, scope.spaceId), indexVersion, spec.profileId)
      ) match
        case Some(snapshot) if StructureStore.authorized(snapshot.binding, scope) =>
          index.find(snapshot.binding.key, snapshot.binding.ingestionId).map {
            case Some(manifest)
                if StructureStore.readable(manifest, snapshot.binding, scope) ||
                  (allowBuilding && manifest.status == KnowledgeIndexStatus.Building && snapshot.binding
                    .matches(manifest) &&
                    manifest.permissions.nonEmpty && manifest.permissions.subsetOf(scope.permissions)) =>
              Some(snapshot)
            case _ => None
          }
        case _ => ZIO.succeed(None)
    }

  override def getActive(
      documentId: String,
      spec: StructureBuildSpec,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    index.active(KnowledgeDocumentKey(scope.tenantId, documentId, scope.spaceId)).flatMap {
      case Some(manifest) => get(documentId, manifest.build.version, spec, scope)
      case None           => ZIO.succeed(None)
    }

object InMemoryStructureStore:
  def make(index: KnowledgeIndexStore): UIO[InMemoryStructureStore] =
    Ref
      .make(Map.empty[(KnowledgeDocumentKey, Long, String), StructureSnapshot])
      .map(new InMemoryStructureStore(index, _))

enum StructurePublicationPolicy:
  case Disabled, BestEffort, Required

enum StructurePublicationStatus:
  case Disabled, Ready, Unavailable

final case class StructureIndexing(
    store: StructureStore,
    policy: StructurePublicationPolicy = StructurePublicationPolicy.Required,
    spec: StructureBuildSpec = StructureBuildSpec(),
    summarizer: Option[NodeSummarizer] = None
):
  def stage(
      document: SourceDocument,
      build: KnowledgeIndexBuild,
      chunks: Chunk[DocumentChunk]
  ): IO[RetrievalError, StructurePublicationStatus] =
    if policy == StructurePublicationPolicy.Disabled then ZIO.succeed(StructurePublicationStatus.Disabled)
    else
      val action = for
        base <- ZIO
          .attempt(StructureBuilder.build(document, build, chunks, spec))
          .mapError(_ => AgentError.RetrievalFailed("structure build invalid"))
          .flatMap(value => ZIO.fromEither(value).mapError(AgentError.RetrievalFailed(_)))
        scope = RetrievalScope(
          build.key.tenantId,
          chunks.flatMap(_.permissions).toSet,
          knowledgeSpaceId = Some(build.knowledgeSpaceId)
        ).withPinnedProfile(build.profileId)
        existing <- store.getForBuild(base, scope)
        snapshot <- existing match
          case Some(saved) =>
            ZIO.fromEither(saved.validate).mapError(AgentError.RetrievalFailed(_)) *>
              ZIO
                .fail(AgentError.RetrievalFailed("structure resume identity mismatch"))
                .unless(
                  saved.binding == base.binding && saved.spec == base.spec && saved.nodes
                    .map(_.copy(summary = None)) == base.nodes
                )
                .as(saved)
          case None =>
            spec.summary match
              case Some(summarySpec) =>
                val selected = summarizer.orElse(
                  Option.when(summarySpec.provider == "internal")(
                    NodeSummarizer.deterministic(summarySpec.mode)
                  )
                )
                ZIO
                  .fromOption(selected)
                  .orElseFail(AgentError.RetrievalFailed("summary model not wired"))
                  .flatMap(s =>
                    StructureBuilder.buildWithSummaries(document, build, chunks, spec, s, summarySpec)
                  )
              case None => ZIO.succeed(base)
        _ <- store.put(snapshot)
      yield StructurePublicationStatus.Ready
      if policy == StructurePublicationPolicy.BestEffort then
        action.catchAll(_ => ZIO.succeed(StructurePublicationStatus.Unavailable))
      else action

/** 有界树推理导航预算（Reasoned Tree Search 契约规范）。 */
final case class TreeSearchBudget(
    maxDepth: Int = 4,
    beamWidth: Int = 2,
    maxVisitedNodes: Int = 16,
    maxModelCalls: Int = 4,
    timeout: Duration = 5.seconds
) derives JsonCodec:
  require(maxDepth > 0 && maxDepth <= 10, "Tree search maxDepth 必须在 1..10 之间")
  require(beamWidth > 0 && beamWidth <= 8, "Tree search beamWidth 必须在 1..8 之间")
  require(maxVisitedNodes > 0 && maxVisitedNodes <= 64, "Tree search maxVisitedNodes 必须在 1..64 之间")
  require(maxModelCalls > 0 && maxModelCalls <= 16, "Tree search maxModelCalls 必须在 1..16 之间")
  require(timeout > Duration.Zero, "Tree search timeout 必须大于 0")

/** 节点摘要构建规格（Node Summary 规格契约）。 */
final case class NodeSummarySpec(
    provider: String,
    model: String,
    promptVersion: String = "v1",
    mode: SummaryMode = SummaryMode.Selective,
    cacheNamespace: String = "local"
) derives JsonCodec:
  require(provider.trim.nonEmpty && model.trim.nonEmpty, "NodeSummarySpec provider/model 不能为空")
