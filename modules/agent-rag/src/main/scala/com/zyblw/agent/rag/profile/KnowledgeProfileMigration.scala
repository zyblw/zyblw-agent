package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 一次 Profile 蓝绿迁移的输入。
  *
  * @param target
  *   新构建规格对应的 Profile；通常为 `IndexProfileIds.fromSpec(indexer.buildSpec)`
  * @param reason
  *   写入切换审计的原因，必须匹配 `[A-Za-z0-9._:-]{1,160}`
  */
final case class KnowledgeProfileMigrationRequest(
    tenantId: TenantId,
    target: IndexProfileId,
    knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId.Default,
    reason: String = "profile-migration",
    pageSize: Int = 32,
    maxPages: Int = 10000
):
  require(pageSize > 0 && pageSize <= 200, "migration pageSize 必须位于 1..200")
  require(maxPages > 0, "migration maxPages 必须为正数")
  require(reason.matches("[A-Za-z0-9._:-]{1,160}"), "migration reason 不合法")

/** 宿主对目标 Profile 的离线评测结论；`id` 进入发布审计。 */
final case class ProfileEvaluation(id: String, passed: Boolean, detail: String = ""):
  require(id.matches("[A-Za-z0-9._:-]{1,200}"), "evaluation id 不合法")

enum KnowledgeProfileMigrationOutcome:
  /** 空间尚无 active Profile；首份文档发布时走 bootstrap，不需要迁移。 */
  case NoActiveProfile

  case AlreadyActive(profileId: IndexProfileId)

  case Activated(from: IndexProfileId, to: IndexProfileId, revision: Long, documents: Int)

  /** 未切换，旧 Profile 继续服务；修复原因后可重复执行。 */
  case Blocked(
      reason: String,
      failures: Chunk[KnowledgeReindexItem] = Chunk.empty,
      diff: Option[ProfileCorpusDiff] = None
  )

/** 把知识空间从当前 active Profile 迁移到目标构建规格。
  *
  * 顺序固定为：按 active 语料分页重建到目标 Profile → 校验两侧逻辑语料一致 → 宿主评测 → 带普查的 CAS 切换。任何一步不通过都不改指针，旧 Profile 持续对外服务。
  * 重建按摄取幂等键复用已完成的文档，因此中断或重复执行只补齐缺口，不会重复计费。
  */
final class KnowledgeProfileMigration(store: KnowledgeIndexStore, reindex: KnowledgeReindexService):

  def migrate(
      request: KnowledgeProfileMigrationRequest,
      evaluate: Chunk[KnowledgeIndexManifest] => IO[RetrievalError, ProfileEvaluation]
  ): IO[RetrievalError, KnowledgeProfileMigrationOutcome] =
    store.spaceProfileState(request.tenantId, request.knowledgeSpaceId).flatMap {
      case None | Some(KnowledgeSpaceProfileState(None, _)) =>
        ZIO.succeed(KnowledgeProfileMigrationOutcome.NoActiveProfile)
      case Some(KnowledgeSpaceProfileState(Some(active), _)) if active == request.target =>
        ZIO.succeed(KnowledgeProfileMigrationOutcome.AlreadyActive(active))
      case Some(KnowledgeSpaceProfileState(Some(active), _)) =>
        for
          failures <- rebuild(request)
          diff     <- store.profileCorpusDiff(request.tenantId, request.knowledgeSpaceId, request.target)
          outcome  <-
            if failures.nonEmpty then
              ZIO.succeed(
                KnowledgeProfileMigrationOutcome.Blocked("rebuild-incomplete", failures, Some(diff))
              )
            else if !diff.isEmpty then
              ZIO.succeed(KnowledgeProfileMigrationOutcome.Blocked("corpus-mismatch", diff = Some(diff)))
            else publish(request, active, evaluate)
        yield outcome
    }

  private def rebuild(
      request: KnowledgeProfileMigrationRequest
  ): IO[RetrievalError, Chunk[KnowledgeReindexItem]] =
    def page(
        cursor: Option[String],
        remaining: Int,
        failed: Chunk[KnowledgeReindexItem]
    ): IO[RetrievalError, Chunk[KnowledgeReindexItem]] =
      reindex
        .reindex(
          KnowledgeReindexRequest(
            request.tenantId,
            Set.empty,
            request.pageSize,
            cursor,
            request.knowledgeSpaceId,
            Some(request.target)
          )
        )
        .flatMap { report =>
          val next = failed ++ report.items.filter(_.status != KnowledgeReindexStatus.Reindexed)
          if report.hasMore && report.nextDocumentId.nonEmpty && remaining > 1 then
            page(report.nextDocumentId, remaining - 1, next)
          else if report.hasMore then
            ZIO.succeed(next :+ KnowledgeReindexItem("*", KnowledgeReindexStatus.Failed, Some("page-limit")))
          else ZIO.succeed(next)
        }
    page(None, request.maxPages, Chunk.empty)

  private def publish(
      request: KnowledgeProfileMigrationRequest,
      active: IndexProfileId,
      evaluate: Chunk[KnowledgeIndexManifest] => IO[RetrievalError, ProfileEvaluation]
  ): IO[RetrievalError, KnowledgeProfileMigrationOutcome] =
    for
      manifests <- store.profileManifests(request.tenantId, request.knowledgeSpaceId, request.target)
      current = ProfilePublication.latestByDocument(manifests)
      evaluation <- evaluate(current)
      outcome    <-
        if !evaluation.passed then
          ZIO.succeed(KnowledgeProfileMigrationOutcome.Blocked(s"evaluation-failed: ${evaluation.detail}"))
        else
          val documents = current.map(ProfileDocument.fromManifest)
          for
            state <- store
              .spaceProfileState(request.tenantId, request.knowledgeSpaceId)
              .someOrFail(AgentError.RetrievalFailed("knowledge space does not exist"))
            revision <- store.activateProfile(
              request.tenantId,
              request.knowledgeSpaceId,
              request.target,
              state.revision,
              request.reason,
              Some(ProfilePublication(documents, evaluation.id, ProfilePublication.digest(documents), true))
            )
          yield KnowledgeProfileMigrationOutcome.Activated(active, request.target, revision, documents.length)
    yield outcome

object KnowledgeProfileMigration:
  val layer: URLayer[KnowledgeIndexStore & KnowledgeReindexService, KnowledgeProfileMigration] =
    ZLayer.fromFunction(KnowledgeProfileMigration(_, _))
