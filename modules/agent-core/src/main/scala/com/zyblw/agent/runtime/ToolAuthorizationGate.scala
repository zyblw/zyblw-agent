package com.zyblw.agent.runtime

import com.zyblw.agent.composition.*
import com.zyblw.agent.core.*
import com.zyblw.agent.extension.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.ast.Json

/** 工具批次在副作用发生前必须满足的全部授权判定。
  *
  * 门禁顺序是固定的安全语义，不能重排：未知工具 → 缺少 scope → 扩展拒绝 → 需要人工审批。前三项都会降级为单调用错误 结果，最后一项会让 Run 暂停。
  *
  * 审批判定在这里重新计算现场 [[ApprovalSubject]]：规划时冻结的审批要求不会因为策略事后放宽而消失，而历史批准只有在 主体逐字段相同时才继续有效。
  */
final private[agent] class ToolAuthorizationGate(
    registry: RegisteredToolRegistry,
    toolPolicies: ToolPolicySource,
    extensions: RuntimeExtensions
):
  import ToolAuthorizationGate.*

  /** 计算整批的门禁结论。
    *
    * @param resolved
    *   批次内每个调用与注册表解析结果的配对
    * @param grant
    *   本次人工操作授予的放行范围；只影响当前 resume
    */
  def gate(
      state: AgentState,
      plan: DurableToolPlan,
      batch: DurableToolBatch,
      resolved: Chunk[(DurableToolPlanItem, Either[AgentError.ToolNotFound, RegisteredTool])],
      grant: ResumeGrant
  ): IO[AgentError, GateDecision] =
    resolved.collectFirst { case (item, Left(error)) => item -> error } match
      case Some((item, error)) =>
        singleton(state.runId, batch, "未知工具").as(rejected(item, error.message))
      case None =>
        val tools = resolved.map { case (item, value) => item -> value.toOption.get }
        missingScope(state, tools) match
          case Some((item, reason)) =>
            singleton(state.runId, batch, "权限不足工具").as(rejected(item, reason))
          case None =>
            val policy = toolPolicies.current()
            deniedByExtension(state, plan, tools, policy).flatMap {
              case Some((item, reason)) =>
                singleton(state.runId, batch, "扩展拒绝的工具").as(rejected(item, reason))
              case None =>
                needsApproval(state, plan, tools, policy, grant) match
                  case Some((item, tool, subject)) =>
                    val reason = driftReason(plan, subject)
                      .orElse(AgentKernel.approvalReason(policy, tool.metadata.risk))
                      .getOrElse("该工具调用在规划时已冻结为需要人工审批")
                    singleton(state.runId, batch, "需要审批的工具")
                      .as(GateDecision.NeedsApproval(item, tool.metadata.risk, reason, subject))
                  case None => ZIO.succeed(GateDecision.Executable(tools))
            }

  /** 在写入批准决定之前，确认人工看到的那个副作用此刻仍然成立。
    *
    * 从暂停到批准之间可以经过任意长时间：工具实现、审批策略或调用者权限都可能已经变化。主体不一致时带新主体重新请求 审批，而不是直接失败——失败会让暂停停留在旧主体上，形成永远无法通过的死锁。
    *
    * @return
    *   Left 表示主体已变化，附带需要重新审批的新主体与原因；Right 表示可以按人工决定放行
    */
  def revalidate(
      state: AgentState,
      approval: ApprovalRequest,
      call: ToolCall
  ): UIO[Either[Revalidation, ResumeGrant]] =
    val replay = ResumeGrant(forceRetryCallIds = Set(call.id))
    approval.subject match
      // 崩溃恢复类的"是否允许重放"请求没有主体，沿用原有的 callId 授权语义。
      case None         => ZIO.succeed(Right(replay))
      case Some(frozen) =>
        val policy = toolPolicies.current()
        registry.get(ToolName(call.name)).either.map {
          // 工具已从注册表消失：交给既有的契约校验与 ToolNotFound 门禁处理，这里不重复判定。
          case Left(_)     => Right(replay)
          case Right(tool) =>
            val live = liveSubject(state, policy, call, tool)
            if live == frozen then Right(replay.copy(approvedSubjects = Set(frozen)))
            else
              Left(
                Revalidation(
                  tool.metadata.risk,
                  s"审批主体在批准前已变化（${frozen.driftFrom(live).mkString("、")}），原批准不再适用，请重新确认",
                  live
                )
              )
        }

  /** 用给定策略快照、工具契约与 Run 的可信授权上下文构造该调用的审批主体。
    *
    * 主体不依赖任何 Runtime 可变状态，因此规划时冻结的值与执行前重新计算的值只在"确实发生了安全相关变化"时才不同。
    */
  def subjectOf(
      state: AgentState,
      policy: ToolPolicyConfig,
      call: ToolCall,
      metadata: ToolMetadata,
      contract: ToolContractFingerprint
  ): ApprovalSubject =
    ApprovalSubject.of(
      call = call,
      metadata = metadata,
      toolContract = contract,
      policy = ApprovalPolicyFingerprint.of(policy, ToolName(call.name)),
      authorization = AuthorizationFingerprint.of(state.runContext),
      environment = extensions.environment.id,
      permissions = extensions.environment.permissions
    )

  /** 为已注册工具计算执行前的现场审批主体。 */
  def liveSubject(
      state: AgentState,
      policy: ToolPolicyConfig,
      call: ToolCall,
      tool: RegisteredTool
  ): ApprovalSubject =
    subjectOf(state, policy, call, tool.metadata, ToolContractFingerprint.registered(tool))

  /** 在门禁、审批或副作用之前核对计划创建时冻结的工具契约。
    *
    * `DurableToolPlan` 的构造约束已经保证契约指纹覆盖每个工具，因此这里只需逐个比较；不存在"指纹为空所以跳过检查"的 路径。
    */
  def validateContracts(
      state: AgentState,
      plan: DurableToolPlan,
      resolved: Chunk[(DurableToolPlanItem, Either[AgentError.ToolNotFound, RegisteredTool])]
  ): IO[AgentError, Unit] =
    ZIO.foreachDiscard(resolved) { case (item, tool) =>
      val expected = plan.toolContractFingerprints.get(item.call.name)
      val current  = tool.fold(
        _ => ToolContractFingerprint.missing(item.call.name),
        ToolContractFingerprint.registered
      )
      ZIO
        .fail(
          AgentError.CompositionIncompatible(
            state.runId,
            Chunk(CompositionDriftField("toolContractFingerprint", CapabilityKind.Tool, true)),
            s"工具 ${item.call.name} 的 Schema 或安全契约已变化，拒绝执行已冻结计划"
          )
        )
        .unless(expected.contains(current))
    }

  /** 查询状态历史中是否存在对**同一副作用**的批准；恢复不能把重启误认为批准。
    *
    * 只比较完整的 [[ApprovalSubject]]：`callId` 由 Provider 给出，模型可以在后续轮次复用同一个 ID 提出不同参数的调用， 只按 ID 匹配会让一次批准变成对该 ID
    * 的长期授权。没有主体的历史步骤（崩溃恢复类重放批准）不构成对新副作用的授权， 因此不参与匹配。
    */
  private def wasApproved(state: AgentState, subject: ApprovalSubject): Boolean =
    state.steps.exists {
      case AgentStep.ApprovalStep(_, request, Some(ApprovalDecision.Approve), _) =>
        request.subject.contains(subject)
      case _ => false
    }

  private def missingScope(
      state: AgentState,
      tools: Chunk[(DurableToolPlanItem, RegisteredTool)]
  ): Option[(DurableToolPlanItem, String)] =
    tools.collectFirst {
      case (item, tool) if !tool.metadata.requiredScopes.subsetOf(state.runContext.scopes) =>
        val missing = tool.metadata.requiredScopes -- state.runContext.scopes
        item -> s"缺少工具权限 scope: ${missing.toList.sorted.mkString(",")}"
    }

  private def needsApproval(
      state: AgentState,
      plan: DurableToolPlan,
      tools: Chunk[(DurableToolPlanItem, RegisteredTool)],
      policy: ToolPolicyConfig,
      grant: ResumeGrant
  ): Option[(DurableToolPlanItem, RegisteredTool, ApprovalSubject)] =
    tools.iterator
      .filter { case (item, tool) =>
        AgentKernel.requiresApproval(plan, policy, item.call.id, tool.metadata.risk)
      }
      .map { case (item, tool) => (item, tool, liveSubject(state, policy, item.call, tool)) }
      .find { case (_, _, subject) =>
        !grant.approvedSubjects.contains(subject) && !wasApproved(state, subject)
      }

  /** 扩展只能拒绝需要审批的副作用，不能批准。`RecommendAllow` 被忽略。 */
  private def deniedByExtension(
      state: AgentState,
      plan: DurableToolPlan,
      tools: Chunk[(DurableToolPlanItem, RegisteredTool)],
      policy: ToolPolicyConfig
  ): UIO[Option[(DurableToolPlanItem, String)]] =
    if extensions.approvalReviewers.isEmpty then ZIO.none
    else
      val input      = extensionInput(state)
      val candidates = tools.filter { case (item, tool) =>
        AgentKernel.requiresApproval(plan, policy, item.call.id, tool.metadata.risk)
      }
      ZIO.foldLeft(candidates)(Option.empty[(DurableToolPlanItem, String)]) { (denied, pair) =>
        denied match
          case Some(_) => ZIO.succeed(denied)
          case None    =>
            val (item, tool) = pair
            val subject      = liveSubject(state, policy, item.call, tool)
            ZIO
              .foldLeft(extensions.approvalReviewers)(Option.empty[String]) { (found, reviewer) =>
                found match
                  case Some(_) => ZIO.succeed(found)
                  case None    =>
                    reviewer.review(subject, input).map {
                      case ApprovalReview.Deny(reason) =>
                        Some(s"扩展 ${reviewer.descriptor.sourceId} 拒绝该副作用: ${reason.take(256)}")
                      case ApprovalReview.Abstain | ApprovalReview.RecommendAllow(_) => None
                    }
              }
              .map(_.map(item -> _))
      }

  /** 解释一次「历史批准已不再适用」的暂停原因。
    *
    * 只输出发生变化的属性名，不输出任一侧取值，因此可以安全进入面向运维的错误信息与事件流。
    */
  private def driftReason(plan: DurableToolPlan, subject: ApprovalSubject): Option[String] =
    plan.approvalSubjects
      .get(subject.callId)
      .filterNot(_ == subject)
      .map(frozen => s"审批主体已变化（${frozen.driftFrom(subject).mkString("、")}），历史批准不再适用，需要重新人工授权")

  /** 单调用错误批次是安全降级；若元数据漂移让并行批次出现动态门禁，则拒绝半批提交。 */
  private def singleton(runId: RunId, batch: DurableToolBatch, gate: String): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidResume(runId, s"$gate 出现在多调用批次，工具元数据可能已漂移"))
      .unless(batch.items.length == 1)
      .unit

  /** 扩展允许看到的 Host 输入；不含 Runtime、Store 或可变状态。 */
  private def extensionInput(state: AgentState): ExtensionInput =
    ExtensionInput(
      state.runId,
      state.agentId,
      AuthorizationFingerprint.of(state.runContext),
      Some(state.composition)
    )

  private def rejected(item: DurableToolPlanItem, message: String): GateDecision =
    GateDecision.ErrorResult(item, ToolAuthorizationGate.errorToolResult(item.call.name, message))

private[agent] object ToolAuthorizationGate:
  /** 整批授权判定的结论。 */
  enum GateDecision:
    /** 整批可执行；附带已解析的工具，避免调用方再解析一次注册表。 */
    case Executable(tools: Chunk[(DurableToolPlanItem, RegisteredTool)])

    /** 单个调用应以结构化错误提交，不发生副作用。 */
    case ErrorResult(item: DurableToolPlanItem, result: ToolResult)

    /** 需要人工授权；调用方应据此暂停 Run。 */
    case NeedsApproval(
        item: DurableToolPlanItem,
        risk: ToolRisk,
        reason: String,
        subject: ApprovalSubject
    )

  /** 批准前主体漂移的处置指令：带新主体重新请求审批。 */
  final case class Revalidation(risk: ToolRisk, reason: String, subject: ApprovalSubject)

  /** 一次人工决定在本次 resume 中授予的放行范围。
    *
    * 它只在内存中沿调用链传递，不写入耐久状态，也不扩大其他调用的权限。两个字段回答不同问题：`approvedSubjects` 是 "这个具体副作用被授权发生"，`forceRetryCallIds`
    * 是"这个结果未知的副作用被授权重放"。
    */
  final case class ResumeGrant(
      approvedSubjects: Set[ApprovalSubject] = Set.empty,
      forceRetryCallIds: Set[String] = Set.empty
  )

  object ResumeGrant:
    /** 普通批次推进：不携带任何人工授权。 */
    val none: ResumeGrant = ResumeGrant()

  /** 将可公开的工具门禁失败转换成模型可消费的稳定 JSON 结果。 */
  def errorToolResult(toolName: String, message: String): ToolResult =
    ToolResult(Json.Obj("tool" -> Json.Str(toolName), "error" -> Json.Str(message)), isError = true)
