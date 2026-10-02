package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 分支只返回位置；这些排序分不进入证据接受门槛。 */
final case class StructureNodeCandidate(snapshot: StructureSnapshot, nodeId: String, value: Double)

final case class StructuralRetrievalConfig(
    maxDocuments: Int = 4,
    maxNodes: Int = 6,
    maxChunksPerNode: Int = 128,
    maxCandidates: Int = 24,
    timeout: Duration = 3.seconds,
    failOpen: Boolean = true
):
  require(
    maxDocuments > 0 && maxDocuments <= 32 && maxNodes > 0 && maxNodes <= 32,
    "structure search limits 无效"
  )
  require(
    maxChunksPerNode > 0 && maxChunksPerNode <= 1000 && maxCandidates > 0 && maxCandidates <= 200,
    "structure materialization limits 无效"
  )
  require(timeout > Duration.Zero, "structure timeout 必须为正数")

/** 低敏导航证据，不含标题、原文、权限标签或模型推理。 */
final case class StructureSelection(
    documentId: String,
    generation: String,
    nodeId: String,
    materializedCount: Int
)

final case class StructuralRetrievalResult(
    hits: Chunk[RetrievalHit],
    degraded: Boolean,
    selections: Chunk[StructureSelection] = Chunk.empty,
    budgetLimited: Boolean = false
)

/** 确定性 Value Search + 节点内原文检索；宿主显式装配才启用。 */
final class StructuralRetrieval(
    val store: StructureStore,
    val spec: StructureBuildSpec = StructureBuildSpec(),
    val config: StructuralRetrievalConfig = StructuralRetrievalConfig(),
    val navigator: TreeNavigator = TreeNavigator.deterministic,
    val treeBudget: TreeSearchBudget = TreeSearchBudget()
):
  def retrieve(
      request: RetrievalRequest,
      queryText: String,
      embedding: Embedding,
      seeds: Chunk[RetrievalHit],
      vectors: VectorStore
  ): IO[RetrievalError, StructuralRetrievalResult] =
    val action = for
      _ <- ZIO
        .fail(AgentError.RetrievalFailed("structure seed scope violation"))
        .unless(
          seeds.forall(hit =>
            StructuralRetrieval.authorized(hit.chunk, request.scope) && request.filter.matches(hit.chunk)
          )
        )
      seededDocuments = seeds.map(h => h.chunk.documentId -> h.chunk.catalogVersion).distinct
      documents       = (seededDocuments.map(_._1) ++ Chunk.fromIterable(
        request.filter.documentIds.toList.sorted
      )).distinct.take(config.maxDocuments)
      snapshots <- ZIO.foreach(documents) { id =>
        val version = seededDocuments.find(_._1 == id).map(_._2)
        val lookup  =
          version.fold(store.getActive(id, spec, request.scope))(v => store.get(id, v, spec, request.scope))
        lookup.flatMap {
          case None           => ZIO.succeed(None)
          case Some(snapshot) =>
            ZIO.fromEither(snapshot.validate).mapError(AgentError.RetrievalFailed(_)) *>
              ZIO
                .fail(AgentError.RetrievalFailed("structure snapshot scope violation"))
                .unless(
                  snapshot.spec == spec && StructureStore.authorized(snapshot.binding, request.scope) &&
                    snapshot.binding.documentId == id && version.forall(_ == snapshot.binding.indexVersion) &&
                    seeds.filter(_.chunk.documentId == id).forall(h => snapshot.binding.matches(h.chunk))
                )
                .as(Some(snapshot))
        }
      }
      valueRanked = snapshots.flatten
        .flatMap(snapshot => StructuralRetrieval.rank(snapshot, seeds))
      treeResults <-
        if request.strategy.contains(RetrievalStrategy.Reasoned) then
          ZIO
            .foreach(snapshots.flatten) { snapshot =>
              BoundedTreeSearch.search(request.text, snapshot, navigator, treeBudget).map(snapshot -> _)
            }
        else ZIO.succeed(Chunk.empty)
      reasonedCandidates = treeResults.flatMap { case (snapshot, result) =>
        result.selectedNodeIds.map(nodeId => StructureNodeCandidate(snapshot, nodeId, 1.0))
      }
      ranked = (reasonedCandidates ++ valueRanked)
        .distinctBy(c => c.snapshot.binding.documentId -> c.nodeId)
        .sortBy(c => (-c.value, c.snapshot.binding.documentId, c.nodeId))
      candidates = ranked.take(config.maxNodes)
      materialized <- ZIO.foreach(candidates) { candidate =>
        val node       = candidate.snapshot.nodesById(candidate.nodeId)
        val nodeChunks = candidate.snapshot.subtreeChunkIds(node.section.id)
        val largeLeaf  =
          nodeChunks.size > config.maxChunksPerNode && candidate.snapshot.children(node.section.id).isEmpty
        // Large leaf sections use an indexed metadata predicate, never the first N chunks.
        // Older profiles without section metadata retain their classic seeds until reindexed.
        val ids =
          if largeLeaf || nodeChunks.size <= config.maxChunksPerNode then nodeChunks
          else
            seeds
              .filter(h => candidate.snapshot.binding.matches(h.chunk) && nodeChunks.contains(h.chunk.id))
              .map(_.chunk.id)
              .toSet
        val allowed = if request.filter.chunkIds.isEmpty then ids else ids.intersect(request.filter.chunkIds)
        val sectionConflict =
          largeLeaf && request.filter.metadataEquals.get("structureSectionId").exists(_ != node.section.id)
        if allowed.isEmpty || sectionConflict then ZIO.succeed(Chunk.empty)
        else
          val filter = request.filter.copy(
            documentIds = Set(candidate.snapshot.binding.documentId),
            chunkIds = if largeLeaf && request.filter.chunkIds.isEmpty then Set.empty else allowed,
            metadataEquals =
              request.filter.metadataEquals ++ Option.when(largeLeaf)("structureSectionId" -> node.section.id)
          )
          vectors
            .searchFiltered(request.mode, queryText, embedding, request.scope, filter, config.maxCandidates)
            .flatMap { hits =>
              val valid = hits.length <= config.maxCandidates && hits
                .map(_.chunk.id)
                .distinct
                .length == hits.length && hits.forall { hit =>
                candidate.snapshot.binding
                  .matches(hit.chunk) && StructuralRetrieval.authorized(hit.chunk, request.scope) &&
                nodeChunks.contains(hit.chunk.id) && filter.matches(hit.chunk) && request.filter
                  .matches(hit.chunk) && java.lang.Double.isFinite(hit.score) &&
                hit.signals.values.forall(java.lang.Double.isFinite)
              }
              ZIO
                .fail(AgentError.RetrievalFailed("structure materializer scope/version violation"))
                .unless(valid)
                .as(hits)
            }
      }
    yield StructuralRetrievalResult(
      StructuralRetrieval.fuseGroups(materialized, config.maxCandidates),
      snapshots.exists(_.isEmpty) || treeResults.exists(_._2.degraded),
      candidates
        .zip(materialized)
        .map((candidate, hits) =>
          StructureSelection(
            candidate.snapshot.binding.documentId,
            candidate.snapshot.generation,
            candidate.nodeId,
            hits.length
          )
        ),
      treeResults.exists(_._2.budgetLimited) || (seededDocuments
        .map(_._1)
        .toSet ++ request.filter.documentIds).size > config.maxDocuments ||
        ranked.length > config.maxNodes || candidates
          .exists(c =>
            c.snapshot.subtreeChunkIds(c.nodeId).size > config.maxChunksPerNode && c.snapshot
              .children(c.nodeId)
              .nonEmpty
          )
    )
    val bounded =
      action.timeoutFail(AgentError.RetrievalFailed("structure retrieval timeout"))(config.timeout)
    if config.failOpen then bounded.catchAll(_ => ZIO.succeed(StructuralRetrievalResult(Chunk.empty, true)))
    else bounded

object StructuralRetrieval:
  /** 局部 FTS/RRF 与全库分值不具备共同尺度；跨分支只融合名次，保留原文相关性信号。 */
  def fuse(classic: Chunk[RetrievalHit], structural: Chunk[RetrievalHit], limit: Int): Chunk[RetrievalHit] =
    if structural.isEmpty then classic.take(limit)
    else fuseGroups(Chunk(classic, structural), limit)

  def fuseGroups(groups: Chunk[Chunk[RetrievalHit]], limit: Int): Chunk[RetrievalHit] =
    val ranked = groups.flatMap(_.zipWithIndex.map { case (hit, rank) =>
      (hit, 1.0 / (60.0 + rank + 1.0))
    })
    Chunk.fromIterable(
      ranked
        .groupBy((hit, _) => hit.chunk.documentId -> hit.chunk.id)
        .values
        .map { group =>
          val hit = group.head._1
          hit.copy(
            score = group.map(_._2).sum,
            signals = hit.signals + ("retrievalScore" -> hit.signals.getOrElse("retrievalScore", hit.score))
          )
        }
        .toVector
        .sortBy(h => (-h.score, h.chunk.documentId, h.chunk.id))
        .take(limit)
    )

  def authorized(chunk: DocumentChunk, scope: RetrievalScope): Boolean =
    chunk.tenantId == scope.tenantId && chunk.permissions.nonEmpty && chunk.permissions.subsetOf(
      scope.permissions
    ) &&
      chunk.knowledgeSpaceId.contains(
        scope.spaceId
      ) && chunk.profileId == scope.pinnedProfileId && scope.pinnedProfileId.nonEmpty

  /** 按命中排名聚合而不是混合 cosine、FTS、RRF 数值；大章节按映射块数归一化。 */
  def rank(snapshot: StructureSnapshot, seeds: Chunk[RetrievalHit]): Chunk[StructureNodeCandidate] =
    val ranks = seeds
      .filter(h => snapshot.binding.matches(h.chunk))
      .zipWithIndex
      .map((hit, rank) => hit.chunk.id -> (1.0 / (60.0 + rank + 1.0)))
      .toMap
    snapshot.nodes.flatMap { node =>
      val value =
        node.chunkIds.map(id => ranks.getOrElse(id, 0.0)).sum / math.sqrt(node.chunkIds.length + 1.0)
      Option.when(value > 0.0)(StructureNodeCandidate(snapshot, node.section.id, value))
    }
