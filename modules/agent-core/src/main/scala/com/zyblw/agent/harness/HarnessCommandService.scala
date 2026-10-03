package com.zyblw.agent.harness

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.context.ContextSourceResolver
import com.zyblw.agent.core.*
import com.zyblw.agent.extension.RuntimeExtensions
import com.zyblw.agent.memory.{RunCommandRecord, RunSubmissionStore}
import com.zyblw.agent.model.{ModelRoleCatalog, ModelRoleSource}
import com.zyblw.agent.runtime.RunInitialization
import com.zyblw.agent.tools.ToolPolicySource
import zio.*

/** Harness 异步启动的唯一安全入口。
  *
  * 它只准备现有 Runtime 的 Created 状态和 Start 命令，不执行模型循环。生产 Adapter 会把 Goal 预算预留、AgentState、首事件、 Start 命令与 dispatcher
  * 放在同一数据库事务中，提交后仍由普通 WorkerHost/AgentRuntime 推进。
  */
trait HarnessCommandService:
  def submitStart(
      goalId: GoalId,
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String
  ): IO[AgentError, RunCommandRecord]

final class HarnessCommandServiceLive(
    submissions: RunSubmissionStore,
    toolPolicies: ToolPolicySource,
    profile: RuntimeProfile = RuntimeProfile.default,
    modelPolicies: ModelPolicySource = ModelPolicySource.default,
    contextSources: ContextSourceResolver = ContextSourceResolver.emptyValue,
    extensions: RuntimeExtensions = RuntimeExtensions.empty,
    roleCatalog: ModelRoleCatalog = ModelRoleCatalog.empty,
    roleSource: Option[ModelRoleSource] = None
) extends HarnessCommandService:
  private val roles: ModelRoleSource = roleSource.getOrElse(ModelRoleSource.static(roleCatalog))

  def submitStart(
      goalId: GoalId,
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String
  ): IO[AgentError, RunCommandRecord] =
    roles.current
      .flatMap(_.applyTo(agent.modelSettings))
      .map(settings => agent.copy(modelSettings = settings))
      .flatMap { resolved =>
        RunInitialization.prepareForGoal(
          goalId,
          resolved,
          request,
          idempotencyKey,
          toolPolicies.current().maxCallsPerRun,
          RuntimeComposition.freeze(
            profile,
            resolved,
            modelPolicies,
            contextSources.sourceIds,
            extensions.sourceIds,
            extensions.environment.id.value,
            extensions.environment.permissions.fingerprint
          )
        )
      }
      .flatMap(submissions.submitStart)

object HarnessCommandServiceLive:
  val layer: URLayer[RunSubmissionStore & ToolPolicySource, HarnessCommandService] =
    ZLayer.fromFunction((submissions: RunSubmissionStore, policies: ToolPolicySource) =>
      HarnessCommandServiceLive(submissions, policies)
    )

  def configured(
      profile: RuntimeProfile,
      roleCatalog: ModelRoleCatalog = ModelRoleCatalog.empty
  ): URLayer[
    RunSubmissionStore & ToolPolicySource & ModelPolicySource & ContextSourceResolver & RuntimeExtensions,
    HarnessCommandService
  ] = configuredWithRoles(profile, ModelRoleSource.static(roleCatalog))

  /** 与 `configured` 相同，但角色目录在每次 `submitStart` 时从 `roles` 读取。 */
  def configuredWithRoles(
      profile: RuntimeProfile,
      roles: ModelRoleSource
  ): URLayer[
    RunSubmissionStore & ToolPolicySource & ModelPolicySource & ContextSourceResolver & RuntimeExtensions,
    HarnessCommandService
  ] =
    ZLayer.fromZIO {
      for
        submissions   <- ZIO.service[RunSubmissionStore]
        toolPolicies  <- ZIO.service[ToolPolicySource]
        modelPolicies <- ZIO.service[ModelPolicySource]
        context       <- ZIO.service[ContextSourceResolver]
        extensions    <- ZIO.service[RuntimeExtensions]
      yield HarnessCommandServiceLive(
        submissions,
        toolPolicies,
        profile,
        modelPolicies,
        context,
        extensions,
        roleSource = Some(roles)
      )
    }
