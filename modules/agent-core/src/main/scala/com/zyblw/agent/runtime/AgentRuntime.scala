package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunCommandLease
import zio.*
import zio.stream.*

/** 生产运行时的唯一公开契约。
  *
  * 同步调用、SSE 流、崩溃恢复、审批恢复、取消、状态查询和持久事件查询最终都指向同一个 `AgentState`/`RunStore` 状态机。框架不再维护第二套 checkpoint 或 trace 投影，因而
  * HTTP、Worker 与业务服务看到的状态具有相同的版本和恢复语义。
  *
  * 普通方法保留给单进程嵌入、测试和本地同步调用。生产集群的审批、取消、恢复与显式重试应经 `AgentCommandService` 耐久入队，并由
  * `LeaseAwareAgentRuntime.executeLeased` 在有效 fencing lease 下执行；不能把本 trait 的直接调用当成跨 worker 调度协议。
  */
trait AgentRuntime:
  /** 启动新 Run 并等待最终完成或暂停。
    * @param agent
    *   声明式 Agent 定义
    * @param request
    *   当前用户输入、线程、权限上下文和预算
    */
  def run(agent: AgentDefinition, request: RunRequest): IO[AgentError, RunOutcome]

  /** 恢复等待审批的 Run。
    * @param runId
    *   要恢复的持久 Run
    * @param decision
    *   审批通过或带原因拒绝
    */
  def resume(runId: RunId, decision: ApprovalDecision): IO[AgentError, RunOutcome]

  /** 以有界背压流创建并执行 Run。
    *
    * @param agent
    *   要冻结到耐久状态中的 Agent 定义
    * @param request
    *   用户输入、可信权限上下文和预算
    * @return
    *   `AgentEvent` 流；消费者关闭流时，Scope 会中断其执行 Fiber 并持久化取消状态
    */
  def runEvents(agent: AgentDefinition, request: RunRequest): ZStream[Any, AgentError, AgentEvent]

  /** 恢复 Run 并把恢复后的模型、工具和终止过程作为背压流输出。
    * @param runId
    *   当前应处于 WaitingForApproval 的运行
    * @param decision
    *   人工或业务审批系统给出的批准/拒绝决定
    */
  def resumeEvents(runId: RunId, decision: ApprovalDecision): ZStream[Any, AgentError, AgentEvent]

  /** 从耐久状态与工具执行账本恢复，并等待完成或再次暂停。
    * @param runId
    *   被崩溃、部署或 worker 转移打断的运行
    */
  def recover(runId: RunId): IO[AgentError, RunOutcome]

  /** 以事件流恢复被部署、崩溃或租约转移打断的 Run。
    * @param runId
    *   要恢复并订阅实时进度的运行
    */
  def recoverEvents(runId: RunId): ZStream[Any, AgentError, AgentEvent]

  /** 中断活跃 Fiber 并持久化 Cancelled；未知 Run 返回 RunNotFound。
    * @param runId
    *   要取消的运行；终态调用保持幂等
    */
  def cancel(runId: RunId): IO[AgentError, Unit]

  /** 查询最新完整耐久状态，用于状态页、排障、审批和恢复决策。
    * @param runId
    *   目标运行
    */
  def inspect(runId: RunId): IO[AgentError, AgentState]

  /** 按单调序号查询已经永久保存的精选领域事件。
    * @param runId
    *   目标运行
    * @param afterSequence
    *   只返回严格大于该序号的事件；`-1` 表示从头读取
    * @param limit
    *   单次读取硬上限；长连接应分页而不是一次加载全部历史
    */
  def persistedEvents(
      runId: RunId,
      afterSequence: Long = -1L,
      limit: Int = 512
  ): IO[AgentError, Chunk[PersistedAgentEvent]]

/** 仅供分布式 WorkerHost 使用的租约感知执行入口。
  *
  * 普通 HTTP/API 调用不应伪造 RunCommandLease；只有 `RunCommandStore.claim` 返回的 command/owner/token/generation 才能进入该接口。
  * 实现必须让执行 Fiber 及其子 Fiber 的每次 AgentState 提交都调用 `RunStore.commitFenced`。
  */
trait LeaseAwareAgentRuntime:
  /** 在指定租约所有权下执行一条类型化控制命令。
    *
    * @param lease
    *   当前 worker 已 claim 的有效命令租约；其中包含 Start/Recover/ResumeApproval/Cancel/Retry 正文
    * @return
    *   命令对应状态推进完成后返回 Unit；租约丢失时以 `LeaseLost` 中断
    */
  def executeLeased(lease: RunCommandLease): IO[AgentError, Unit]

object AgentRuntime:
  /** ZIO 服务访问器，对应实例方法 `run`。
    * @param agent
    *   声明式 Agent 定义
    * @param request
    *   本次运行请求
    */
  def run(agent: AgentDefinition, request: RunRequest): ZIO[AgentRuntime, AgentError, RunOutcome] =
    ZIO.serviceWithZIO[AgentRuntime](_.run(agent, request))

  /** ZIO 服务访问器，对应实例方法 `resume`。
    * @param runId
    *   等待审批的运行
    * @param decision
    *   审批决定
    */
  def resume(runId: RunId, decision: ApprovalDecision): ZIO[AgentRuntime, AgentError, RunOutcome] =
    ZIO.serviceWithZIO[AgentRuntime](_.resume(runId, decision))

  /** 从环境取得 Runtime 并保持其事件流环境需求。
    * @param agent
    *   声明式 Agent 定义
    * @param request
    *   本次运行请求
    */
  def runEvents(agent: AgentDefinition, request: RunRequest): ZStream[AgentRuntime, AgentError, AgentEvent] =
    ZStream.serviceWithStream[AgentRuntime](_.runEvents(agent, request))

  /** 从环境取得 Runtime 并恢复审批流程的事件流。
    * @param runId
    *   等待审批的运行
    * @param decision
    *   审批决定
    */
  def resumeEvents(runId: RunId, decision: ApprovalDecision): ZStream[AgentRuntime, AgentError, AgentEvent] =
    ZStream.serviceWithStream[AgentRuntime](_.resumeEvents(runId, decision))

  /** 从环境取得 Runtime 并执行崩溃恢复。
    * @param runId
    *   被中断的耐久运行
    */
  def recover(runId: RunId): ZIO[AgentRuntime, AgentError, RunOutcome] =
    ZIO.serviceWithZIO[AgentRuntime](_.recover(runId))

  /** 从环境取得 Runtime 并返回崩溃恢复事件流。
    * @param runId
    *   被中断的耐久运行
    */
  def recoverEvents(runId: RunId): ZStream[AgentRuntime, AgentError, AgentEvent] =
    ZStream.serviceWithStream[AgentRuntime](_.recoverEvents(runId))

  /** 从环境取得 Runtime 并发送取消请求。
    * @param runId
    *   要取消的运行
    */
  def cancel(runId: RunId): ZIO[AgentRuntime, AgentError, Unit] =
    ZIO.serviceWithZIO[AgentRuntime](_.cancel(runId))

  /** 从环境取得 Runtime 并查询最新完整状态。
    * @param runId
    *   要查询的运行
    */
  def inspect(runId: RunId): ZIO[AgentRuntime, AgentError, AgentState] =
    ZIO.serviceWithZIO[AgentRuntime](_.inspect(runId))

  /** 从环境取得 Runtime 并增量读取耐久领域事件。
    * @param runId
    *   目标运行
    * @param afterSequence
    *   已确认的事件序号游标
    */
  def persistedEvents(
      runId: RunId,
      afterSequence: Long = -1L,
      limit: Int = 512
  ): ZIO[AgentRuntime, AgentError, Chunk[PersistedAgentEvent]] =
    ZIO.serviceWithZIO[AgentRuntime](_.persistedEvents(runId, afterSequence, limit))
