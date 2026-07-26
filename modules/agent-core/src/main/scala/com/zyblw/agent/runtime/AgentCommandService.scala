package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.tools.ToolPolicyConfig
import java.time.Instant
import zio.*

/** 面向 HTTP/业务服务的耐久控制面。
  *
  * 调用方不能直接构造租约或调用 `LeaseAwareAgentRuntime`。该服务先读取 AgentState 验证状态与资源归属，再生成稳定 幂等键提交命令；真正状态推进只会在 WorkerHost
  * claim 后发生。
  */
trait AgentCommandService:
  /** 原子创建初始 Run 并提交 Start 命令；调用返回只表示耐久接收，不等待模型或工具执行。
    *
    * @param agent
    *   从受信任 AgentRegistry 取得的声明式定义
    * @param request
    *   首条输入、线程、认证上下文和预算
    * @param idempotencyKey
    *   客户端稳定创建键；网络重试必须复用
    */
  def submitStart(
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String
  ): IO[AgentError, RunCommandRecord]

  /** 为当前 pendingApproval 提交批准或拒绝决定。 */
  def submitApproval(
      runId: RunId,
      decision: ApprovalDecision,
      actor: RunContext
  ): IO[AgentError, RunCommandRecord]

  /** 提交高优先级取消命令；存储层会抢占该 Run 当前 dispatcher 租约。 */
  def submitCancel(runId: RunId, reason: Option[String], actor: RunContext): IO[AgentError, RunCommandRecord]

  /** 请求从当前耐久状态/工具账本恢复。 */
  def submitRecover(runId: RunId, actor: RunContext): IO[AgentError, RunCommandRecord]

  /** 提交一条带外部请求 ID 的显式恢复命令，用于运维或业务人工干预。 */
  def submitRetry(
      runId: RunId,
      requestId: String,
      reason: String,
      actor: RunContext
  ): IO[AgentError, RunCommandRecord]

  /** 把自动尝试耗尽的命令从 DeadLetter 重新排队。 */
  def retryDeadLetter(
      commandId: CommandId,
      actor: RunContext,
      availableAt: Instant = Instant.EPOCH
  ): IO[AgentError, RunCommandRecord]

  /** 查询命令状态；仍执行目标 Run 的资源归属校验。 */
  def inspect(commandId: CommandId, actor: RunContext): IO[AgentError, RunCommandRecord]

  /** 查询一个 Run 的完整控制命令审计序列。 */
  def list(runId: RunId, actor: RunContext): IO[AgentError, Chunk[RunCommandRecord]]

/** 默认控制面实现。
  *
  * @param runs
  *   AgentState 事实来源，用于状态、approvalId 与资源归属验证
  * @param commands
  *   已存在 Run 的耐久命令队列
  * @param submissions
  *   新 Run 原子创建 Adapter
  * @param toolPolicy
  *   工具治理硬上限；初始 BudgetState 与 Runtime 使用同一份配置
  */
final class AgentCommandServiceLive(
    runs: RunStore,
    commands: RunCommandStore,
    submissions: RunSubmissionStore,
    toolPolicy: ToolPolicyConfig
) extends AgentCommandService:
  /** 先在内存中准备不可变初始事实，再由 Adapter 用一个事务落库。 这里不调用 `runs.createWithEvents`，否则会重新引入“状态成功、Start 命令失败”的双写窗口。
    */
  def submitStart(
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String
  ): IO[AgentError, RunCommandRecord] =
    RunInitialization
      .prepare(agent, request, idempotencyKey, toolPolicy.maxCallsPerRun)
      .flatMap(submissions.submitStart)

  /** 审批幂等键只绑定 approvalId，保证相反决定不能各自创建成功。 */
  def submitApproval(
      runId: RunId,
      decision: ApprovalDecision,
      actor: RunContext
  ): IO[AgentError, RunCommandRecord] =
    for
      state    <- authorizedState(runId, actor)
      approval <- ZIO.fromOption(state.pendingApproval).orElseFail(AgentError.InvalidResume(runId, "缺少待审批请求"))
      _        <- ZIO
        .fail(AgentError.InvalidResume(runId, s"状态为 ${state.status}"))
        .unless(state.status == RunStatus.WaitingForApproval)
      record <- commands.submit(
        runId,
        RunCommandPayload.ResumeApproval(approval.id, decision),
        idempotencyKey = s"approval:${approval.id}",
        priority = 100
      )
    yield record

  /** Cancel 使用固定 Run 级幂等键；重复取消返回第一条记录。 */
  def submitCancel(
      runId: RunId,
      reason: Option[String],
      actor: RunContext
  ): IO[AgentError, RunCommandRecord] =
    for
      _ <- authorizedState(runId, actor)
      safeReason = reason.map(_.trim.take(256)).filter(_.nonEmpty)
      record <- commands.submit(
        runId,
        RunCommandPayload.Cancel(safeReason),
        idempotencyKey = "cancel",
        priority = Int.MaxValue
      )
    yield record

  /** 同一 AgentState 版本只产生一条自动恢复命令；版本推进后可再次提交。 */
  def submitRecover(runId: RunId, actor: RunContext): IO[AgentError, RunCommandRecord] =
    for
      state  <- authorizedState(runId, actor)
      record <- commands.submit(
        runId,
        RunCommandPayload.Recover,
        idempotencyKey = s"recover:${state.version.value}",
        priority = 0
      )
    yield record

  /** 显式 retry 依赖调用方稳定 requestId；同一请求网络重放不会创建多条命令。 */
  def submitRetry(
      runId: RunId,
      requestId: String,
      reason: String,
      actor: RunContext
  ): IO[AgentError, RunCommandRecord] =
    for
      _                <- authorizedState(runId, actor)
      normalizedId     <- nonBlank("requestId", requestId)
      normalizedReason <- nonBlank("reason", reason)
      record           <- commands.submit(
        runId,
        RunCommandPayload.Retry(normalizedReason.take(256)),
        idempotencyKey = s"retry:$normalizedId",
        priority = 10
      )
    yield record

  /** 人工重新排队前先通过命令找到所属 Run，再执行相同资源归属校验。 */
  def retryDeadLetter(
      commandId: CommandId,
      actor: RunContext,
      availableAt: Instant
  ): IO[AgentError, RunCommandRecord] =
    for
      existing <- commands.get(commandId)
      _        <- authorizedState(existing.runId, actor)
      retried  <- commands.retry(commandId, availableAt)
    yield retried

  def inspect(commandId: CommandId, actor: RunContext): IO[AgentError, RunCommandRecord] =
    for
      record <- commands.get(commandId)
      _      <- authorizedState(record.runId, actor)
    yield record

  def list(runId: RunId, actor: RunContext): IO[AgentError, Chunk[RunCommandRecord]] =
    authorizedState(runId, actor) *> commands.list(runId)

  /** 资源归属规则：显式管理员 scope 可跨用户；否则目标 Run 已记录的 tenantId/userId 必须与认证上下文一致。 匿名 Run 仍可由匿名上下文控制，便于公开 Agent 和测试；身份来自
    * HTTP resolver，不能来自请求 JSON。
    */
  private def authorizedState(runId: RunId, actor: RunContext): IO[AgentError, AgentState] =
    runs.load(runId).flatMap(state => RunAuthorization.command(state, actor))

  /** 对外部自由文本执行统一非空校验。 */
  private def nonBlank(label: String, value: String): IO[AgentError, String] =
    val normalized = value.trim
    if normalized.nonEmpty then ZIO.succeed(normalized)
    else ZIO.fail(AgentError.InvalidConfiguration(s"$label 不能为空"))

object AgentCommandServiceLive:
  /** 通过 ZLayer 将状态、命令、原子提交和工具预算组装为控制面。 */
  val layer
      : URLayer[RunStore & RunCommandStore & RunSubmissionStore & ToolPolicyConfig, AgentCommandService] =
    ZLayer.fromFunction(AgentCommandServiceLive.apply)
