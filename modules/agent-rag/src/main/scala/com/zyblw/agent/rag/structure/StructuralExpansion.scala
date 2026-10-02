package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 结构感知上下文扩展。
  *
  * 利用不可变结构快照（StructureSnapshot）的 Section 边界，对高相关性命中块进行同章节或同节点的边界扩展。 扩展块严格受限于原文档、版本、租户 ACL 与请求过滤器；分值经
  * `expandedScoreFactor` 衰减，避免将扩展块误作直接命中。
  */
object StructuralExpansion:
  val DefaultMaxChunksPerSection: Int = 4

  def expand(
      seeds: Chunk[RetrievalHit],
      scope: RetrievalScope,
      store: StructureStore,
      spec: StructureBuildSpec,
      vectors: VectorStore,
      filter: RetrievalFilter,
      expansion: RetrievalExpansionConfig,
      maxChunksPerSection: Int = DefaultMaxChunksPerSection
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    if maxChunksPerSection <= 0 || maxChunksPerSection > 128 then
      ZIO.fail(AgentError.RetrievalFailed("structure expansion budget invalid"))
    else if seeds.isEmpty || expansion.maxAdditionalChunks <= 0 then ZIO.succeed(Chunk.empty)
    else
      val seedKeys  = seeds.map(hit => hit.chunk.documentId -> hit.chunk.id).toSet
      val seedIds   = seeds.map(_.chunk.id).toSet
      val documents = seeds
        .map(h => h.chunk.documentId -> h.chunk.catalogVersion)
        .distinct

      for
        snapshots <- ZIO.foreach(documents) { case (docId, version) =>
          store.get(docId, version, spec, scope).flatMap {
            case None           => ZIO.succeed(Option.empty[StructureSnapshot])
            case Some(snapshot) =>
              ZIO.fromEither(snapshot.validate).mapError(AgentError.RetrievalFailed(_)) *>
                ZIO
                  .fail(AgentError.RetrievalFailed("structure expansion scope violation"))
                  .unless(
                    snapshot.spec == spec && StructureStore.authorized(snapshot.binding, scope) &&
                      snapshot.binding.documentId == docId && snapshot.binding.indexVersion == version
                  )
                  .as(Some(snapshot))
          }
        }
        validSnapshots: Chunk[StructureSnapshot] = snapshots.flatten
        // 为每个命中的章节寻找同节 peer chunk
        peers: Chunk[(String, RetrievalHit)] = validSnapshots.flatMap { snapshot =>
          val docSeeds = seeds.filter(h => snapshot.binding.matches(h.chunk))
          snapshot.nodes.flatMap { node =>
            val nodeSeeds = docSeeds.filter(h => node.chunkIds.contains(h.chunk.id))
            if nodeSeeds.isEmpty then Chunk.empty
            else
              val bestSeed    = nodeSeeds.maxBy(_.score)
              val center      = node.chunkIds.indexOf(bestSeed.chunk.id)
              val peersInNode = node.chunkIds.zipWithIndex
                .filterNot(pair => seedIds.contains(pair._1))
                .sortBy(pair => (math.abs(pair._2 - center), pair._2))
                .take(maxChunksPerSection)
                .map(_._1)
              peersInNode.map(chunkId => (chunkId, bestSeed))
          }
        }
        // 限制总候选数量并去重
        dedupedPeers = peers.distinctBy(_._1).take(expansion.maxAdditionalChunks)
        peerChunkIds = dedupedPeers.map(_._1).toSet
        fetched <-
          if peerChunkIds.isEmpty then ZIO.succeed(Chunk.empty)
          else vectors.fetchChunks(peerChunkIds, scope, filter)
        _ <- ZIO
          .fail(AgentError.RetrievalFailed("structure expansion fetch scope violation"))
          .unless(
            fetched.length <= peerChunkIds.size && fetched
              .map(_.id)
              .distinct
              .length == fetched.length && fetched.forall(c =>
              peerChunkIds.contains(c.id) && filter.matches(c) && StructuralRetrieval.authorized(c, scope) &&
                dedupedPeers.exists((id, seed) =>
                  id == c.id && c.documentId == seed.chunk.documentId &&
                    c.catalogVersion == seed.chunk.catalogVersion && c.sourceRevisionId == seed.chunk.sourceRevisionId &&
                    c.profileId == seed.chunk.profileId && c.knowledgeSpaceId == seed.chunk.knowledgeSpaceId
                )
            )
          )
        peerSeedMap  = dedupedPeers.toMap
        expandedHits = fetched
          .filterNot(c => seedKeys.contains(c.documentId -> c.id))
          .flatMap { chunk =>
            peerSeedMap.get(chunk.id).map { seed =>
              RetrievalHit(
                chunk = chunk.copy(lineage = chunk.lineage.map(_.stampedBy(seed.chunk.id))),
                score = seed.score * expansion.expandedScoreFactor,
                signals = Map(
                  RetrievalSignals.ContextExpanded      -> 1.0,
                  RetrievalSignals.ContextStructureNode -> 1.0
                )
              )
            }
          }
      yield expandedHits
