package com.zyblw.agent.core

import zio.json.*

/** 表达一次智能体执行步骤的转移决策（Agent Transition）。
  *
  * 对齐行业最佳实践（OpenAI Agents SDK / LangGraph Handoff / Microsoft Agent Framework）：
  * 智能体不仅能输出文本或调用工具，还能作为自主节点做出拓扑转移决策：
  * - Continue: 保持当前 Agent 身份，继续下一步循环；
  * - Complete: 当前任务达成，产出最终成果与结构化元数据；
  * - Handoff: 将任务上下文、历史提炼与受控载荷类型安全地转交给目标 Agent；
  * - Suspend: 触发人机协同（HITL），等待人工审批、补充数据或外部信号。
  */
enum AgentTransition derives JsonCodec:
  /** 继续在当前 Agent 循环中推进。 */
  case Continue

  /** 当前智能体成功达成目标，输出不可变产物与元数据。 */
  case Complete(output: String, metadata: Map[String, String] = Map.empty)

  /** 智能体交接（Handoff）：将控制权转交给另一个具备特定领域技能或权限的 Agent。
    *
    * @param targetAgentId 目标智能体唯一标识
    * @param handoffReason 显式交接原因（进入审计事件，不进入不可信 Prompt）
    * @param transferPayload 结构化交接上下文（可供目标 Agent 直接消费的不可变数据）
    */
  case Handoff(
      targetAgentId: AgentId,
      handoffReason: String,
      transferPayload: Map[String, String] = Map.empty
  )

  /** 人机协同挂起：遇到不可逆或高风险操作时主动暂停等待外部干预。 */
  case Suspend(reason: Suspension)

/** 多智能体交接信封协议：保障在分布式存储和命令队列中不可变流转。 */
final case class AgentHandoffEnvelope(
    fromAgentId: AgentId,
    toAgentId: AgentId,
    reason: String,
    stateVersion: Long,
    contextSummary: String,
    payload: Map[String, String] = Map.empty
) derives JsonCodec

object AgentHandoffEnvelope:
  def create(
      from: AgentId,
      to: AgentId,
      reason: String,
      version: Long,
      summary: String,
      payload: Map[String, String] = Map.empty
  ): AgentHandoffEnvelope =
    AgentHandoffEnvelope(
      fromAgentId = from,
      toAgentId = to,
      reason = reason.trim.take(500),
      stateVersion = version,
      contextSummary = summary.trim.take(2000),
      payload = payload
    )

/** 多智能体编排与交接策略：防范死循环与未授权交接。 */
final case class HandoffPolicy(
    allowedTargets: Set[AgentId] = Set.empty,
    maxHandoffHops: Int = 5,
    preserveContextSummary: Boolean = true
) derives JsonCodec:
  /** 目标智能体是否在允许名单中（空集合表示未限制）。 */
  def isAllowed(target: AgentId): Boolean =
    allowedTargets.isEmpty || allowedTargets.contains(target)

object HandoffPolicy:
  val default: HandoffPolicy = HandoffPolicy()
