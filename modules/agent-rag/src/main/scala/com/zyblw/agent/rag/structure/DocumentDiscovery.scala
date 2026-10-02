package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 跨书目录元数据项，描述可供发现的书籍/文档标识与特征。 */
final case class DiscoverableDocument(
    documentId: String,
    title: String,
    summary: Option[String] = None,
    aliases: Set[String] = Set.empty,
    metadata: Map[String, String] = Map.empty
)

/** 跨书/跨文档发现器（Document Discovery）。
  *
  * 对于用户提出的跨书问题（例如“《伤寒论》与《金匮要略》对某证候的论述”）， 提取问题中显式或隐式命中的书目，避免在全库无序粗查，并在选定书目内分别执行结构化精查。
  */
object DocumentDiscovery:
  def discover(
      query: String,
      catalog: Chunk[DiscoverableDocument],
      maxSelect: Int = 4,
      explicitOnly: Boolean = false
  ): Set[String] =
    require(maxSelect > 0 && maxSelect <= 32, "discovery select limit")
    val normalized  = query.trim.toLowerCase
    val queryTokens = SimpleChineseLexicalProcessor.query(normalized).split("\\s+").filter(_.nonEmpty).toSet
    val scored      = catalog.flatMap { doc =>
      val titles        = doc.title.toLowerCase +: doc.aliases.map(_.toLowerCase).toSeq
      val hasExactTitle = titles.exists(t => t.nonEmpty && normalized.contains(t))
      val tokenScore    = titles
        .map(t => queryTokens.count(SimpleChineseLexicalProcessor.document(t).split("\\s+").toSet.contains))
        .maxOption
        .getOrElse(0)
      val summaryScore = doc.summary.fold(0)(s =>
        queryTokens.count(SimpleChineseLexicalProcessor.document(s).split("\\s+").toSet.contains)
      )
      val total = (if hasExactTitle then 10 else 0) + tokenScore * 2 + summaryScore
      Option.when(hasExactTitle || (!explicitOnly && total >= 4))(doc.documentId -> total)
    }
    scored.sortBy(x => (-x._2, x._1)).take(maxSelect).map(_._1).toSet

object AuthorizedDocumentDiscovery:
  def discover(
      query: String,
      directory: KnowledgeIndexDirectory,
      scope: RetrievalScope
  ): IO[RetrievalError, Set[String]] =
    def scan(
        cursor: Option[KnowledgeIndexCursor],
        remaining: Int,
        acc: Chunk[DiscoverableDocument]
    ): IO[RetrievalError, Chunk[DiscoverableDocument]] =
      directory.list(Some(scope.tenantId), KnowledgeIndexDirectory.MaxLimit, cursor).flatMap { page =>
        val permitted = page.items.filter(m =>
          m.active && m.status == KnowledgeIndexStatus.Ready &&
            m.build.key.tenantId == scope.tenantId && m.build.knowledgeSpaceId == scope.spaceId &&
            scope.pinnedProfileId.contains(m.build.profileId) && m.permissions.nonEmpty && m.permissions
              .subsetOf(scope.permissions)
        )
        val next = acc ++ permitted.map(m =>
          DiscoverableDocument(
            m.build.key.documentId,
            m.metadata.getOrElse("title", m.build.key.documentId),
            aliases =
              m.metadata.get("aliases").toSet.flatMap(_.split("[;,，；]").map(_.trim).filter(_.nonEmpty))
          )
        )
        if page.hasMore && remaining > 1 then scan(page.nextCursor, remaining - 1, next)
        else if page.hasMore then ZIO.succeed(Chunk.empty)
        else ZIO.succeed(next)
      }
    // ponytail: bounded metadata scan (1000 manifests); replace with indexed authorized catalog for larger corpora.
    scan(None, 5, Chunk.empty).map(catalog =>
      DocumentDiscovery.discover(query, catalog, maxSelect = 32, explicitOnly = true)
    )

/** 跨文档检索协调器。
  *
  * 当确定多本书籍时，并发对各书执行结构检索，并按照来源多样性预算公平融合，避免单本书吞没全部配额。
  */
object CrossDocumentCoordinator:
  def coordinate(
      retriever: Retriever,
      request: RetrievalRequest,
      targetDocumentIds: Set[String],
      maxDocsParallel: Int = 4
  ): IO[RetrievalError, RetrievalResult] =
    if maxDocsParallel <= 0 || maxDocsParallel > 32 || targetDocumentIds.isEmpty || targetDocumentIds.size > 32
    then ZIO.fail(AgentError.RetrievalFailed("cross-document limits invalid"))
    else
      val ids =
        if request.filter.documentIds.isEmpty then targetDocumentIds
        else targetDocumentIds.intersect(request.filter.documentIds)
      if ids.isEmpty then
        ZIO.succeed(
          RetrievalResult(Chunk.empty, Chunk.empty, RetrievalEvidence(RetrievalEvidenceStatus.NoCandidates))
        )
      else
        for
          scope <-
            if request.scope.pinnedProfileId.nonEmpty then ZIO.succeed(request.scope)
            else retriever.pinScope(request.scope)
          session <- retriever.querySession
          results <- ZIO
            .foreachPar(ids.toList.sorted) { docId =>
              val filter = request.filter.copy(documentIds = Set(docId))
              session.retrieve(request.copy(scope = scope, filter = filter)).flatMap { result =>
                val valid = result.hits.forall(h =>
                  StructuralRetrieval.authorized(h.chunk, scope) && filter.matches(h.chunk) &&
                    java.lang.Double.isFinite(h.score) && h.signals.values.forall(java.lang.Double.isFinite)
                )
                ZIO
                  .fail(AgentError.RetrievalFailed("cross-document scope violation"))
                  .unless(valid)
                  .as(result)
              }
            }
            .withParallelism(maxDocsParallel)
          groups = Chunk.fromIterable(
            results.map(_.hits.filterNot(_.signals.get(RetrievalSignals.ContextExpanded).contains(1.0)))
          )
          maxRank = groups.map(_.length).foldLeft(0)(_ max _)
          fair    = Chunk
            .fromIterable((0 until maxRank).flatMap(rank => groups.flatMap(_.lift(rank))))
            .distinctBy(h => h.chunk.documentId -> h.chunk.id)
            .take(request.limit)
          evidence = RetrievalEvidence(
            if fair.nonEmpty then RetrievalEvidenceStatus.Supported
            else RetrievalEvidenceStatus.NoAcceptedHits,
            results.map(_.evidence.candidateCount).sum.max(fair.length),
            fair.length,
            fair.map(DefaultRetriever.relevanceScore).maxOption
          )
          expanded = Chunk.fromIterable(
            results.flatMap(_.hits.filter(_.signals.get(RetrievalSignals.ContextExpanded).contains(1.0)))
          )
          recipeBudgets = request.recipe.fold(CandidateBudgets())(r => RetrievalRecipe.resolve(r)._2)
          bundle        = ContextAssembler.assemble(
            fair,
            expanded,
            evidence,
            recipeBudgets.copy(rerankSeeds = request.limit.max(1), maxChunksPerSource = request.limit.max(1)),
            profileId = scope.pinnedProfileId,
            knowledgeSpaceId = Some(scope.spaceId),
            degradedStages = Chunk.fromIterable(results.flatMap(_.diagnostics.degradedStages).distinct)
          )
        yield bundle
          .copy(
            structureSelections = Chunk.fromIterable(results.flatMap(_.diagnostics.structureSelections)),
            recipe = request.recipe.map(_.toString).orElse(results.headOption.flatMap(_.diagnostics.recipe)),
            strategy = results.headOption.flatMap(_.diagnostics.strategy)
          )
          .toRetrievalResult
