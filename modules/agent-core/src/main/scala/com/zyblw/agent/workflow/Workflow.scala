package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

opaque type WorkflowId = String
object WorkflowId:
  private val Valid = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}".r

  /** 从配置、数据库或协议边界安全构造稳定 Workflow ID。 */
  def fromString(value: String): Either[String, WorkflowId] =
    val normalized = value.trim
    Either.cond(
      Valid.matches(normalized),
      normalized,
      "WorkflowId 必须是 1..160 位字母、数字、点、下划线或连字符，且以字母或数字开头"
    )

  /** 为源码中已知常量提供便捷构造；配置、数据库和协议输入应优先调用 `fromString` 处理错误。 */
  def apply(value: String): WorkflowId =
    fromString(value).fold(message => throw new IllegalArgumentException(message), identity)

  /** 取得可安全用于持久化 identity、日志标签与定义比较的规范字符串。 */
  extension (id: WorkflowId) def value: String = id

/** 正整数 Workflow 定义版本；一次耐久 Run 恢复时必须与创建时版本一致。 */
opaque type WorkflowVersion = Int
object WorkflowVersion:
  /** 从不可信配置或持久化边界校验正整数版本。 */
  def fromInt(value: Int): Either[String, WorkflowVersion] =
    Either.cond(value > 0, value, "WorkflowVersion 必须大于零")

  /** 为源码中的已知正整数版本提供便捷构造。 */
  def apply(value: Int): WorkflowVersion =
    fromInt(value).fold(message => throw new IllegalArgumentException(message), identity)

  /** 取得用于 schema、数据库和诊断的整数版本。 */
  extension (version: WorkflowVersion) def value: Int = version

/** Workflow 定义内部稳定节点身份；同时参与 checkpoint 游标、execution key 与 timeline 分页。 */
opaque type NodeId = String
object NodeId:
  private val Valid = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}".r

  /** 从配置、数据库或协议边界安全构造节点 ID。 */
  def fromString(value: String): Either[String, NodeId] =
    val normalized = value.trim
    Either.cond(
      Valid.matches(normalized),
      normalized,
      "NodeId 必须是 1..160 位字母、数字、点、下划线或连字符，且以字母或数字开头"
    )

  /** 构造非空节点 ID；节点 ID 同时是 checkpoint 恢复游标的一部分。 */
  def apply(value: String): NodeId =
    fromString(value).fold(message => throw new IllegalArgumentException(message), identity)

  /** 取出节点字符串用于 Map 查找、事件和诊断。 */
  extension (id: NodeId) def value: String = id

/** 节点执行期间只读的可信上下文。
  *
  * `runId/sessionId` 由宿主控制面创建，不能由模型或 Workflow 状态自报；`attributes` 只适合低敏、有限的执行元数据， 不应承载凭据、正文或大型 payload。
  *
  * @param runId
  *   当前 Workflow Run 的稳定身份
  * @param sessionId
  *   与 checkpoint 绑定的业务会话身份
  * @param attributes
  *   宿主提供的附加只读属性
  */
final case class WorkflowContext(
    runId: RunId,
    sessionId: SessionId,
    attributes: Map[String, String] = Map.empty,
    wakeup: Option[WorkflowWakeup] = None
)

/** 一个节点只负责产生新状态或显式暂停；下一条边由图定义而不是节点实现决定。
  *
  * 这种分离让 Runtime 能在执行前检查所有可能路径，并阻止节点把未声明的动态跳转藏在业务代码中。
  */
enum NodeOutcome[S]:
  /** 节点已完成本次访问，Runtime 可以根据声明式 transition 推进。 */
  case Succeeded(state: S)

  /** 节点主动暂停并保存新状态；`reason` 用于低敏控制诊断，不应包含业务正文或凭据。 */
  case Suspended(state: S, reason: String)

  /** 原子提交节点结果、checkpoint 与耐久等待；仅 durable engine 支持。 */
  case Awaiting(state: S, request: WorkflowWaitRequest)

/** 确定性工作流节点；模型驱动的 Agent 只能作为一种节点实现。 */
trait WorkflowNode[-R, S]:
  /** 节点稳定 ID。 */
  def id: NodeId

  /** 执行一个确定性状态转换。
    * @param state
    *   节点输入状态
    * @param context
    *   不随节点修改的 Run/Session 上下文
    */
  def execute(state: S, context: WorkflowContext): ZIO[R, WorkflowError, NodeOutcome[S]]

/** fan-out 的汇合语义必须显式声明，不能由 `foreachPar` 的偶然行为决定。 */
enum FanInPolicy:
  /** 所有分支都成功后才合并；任一分支失败会中断同一 fan-out 中仍在运行的其它 Fiber。 */
  case AllSucceeded

/** 节点完成后的声明式控制边。
  *
  * `Route` 的选择函数必须是纯、快速且无外部副作用的状态判定。需要模型判断时，应先建立一个普通 Agent 节点，把分类结果写入状态，再由纯 Route 选择已经声明的目标。
  */
enum WorkflowTransition[S]:
  /** 成功后进入唯一确定目标。 */
  case Next(node: NodeId)

  /** 从预先声明的目标集合中用纯函数选择一个目标。 */
  case Route(
      targets: NonEmptyChunk[NodeId],
      select: S => Either[WorkflowError, NodeId]
  )

  /** 并行执行独立分支，按显式 policy 归并后进入 join。 */
  case FanOut(
      branches: NonEmptyChunk[NodeId],
      join: NodeId,
      policy: FanInPolicy
  )

  /** 当前节点成功后完成整个 Workflow。 */
  case Complete[S]() extends WorkflowTransition[S]

  /** 返回静态可见的全部可能目标，供定义校验、图投影和未来版本比较使用。 */
  def declaredTargets: Chunk[NodeId] = this match
    case WorkflowTransition.Next(node)                => Chunk(node)
    case WorkflowTransition.Route(targets, _)         => Chunk.fromIterable(targets)
    case WorkflowTransition.FanOut(branches, join, _) => Chunk.fromIterable(branches) :+ join
    case WorkflowTransition.Complete()                => Chunk.empty

/** 工作流定义问题是稳定 ADT，测试和未来 Graph Inspector 不需要解析错误字符串。 */
enum WorkflowValidationIssue:
  case EntryNodeMissing(entry: NodeId)
  case TransitionSourceMissing(source: NodeId)
  case TransitionMissing(node: NodeId)
  case TransitionTargetMissing(source: NodeId, target: NodeId)
  case NodeUnreachable(node: NodeId)
  case DuplicateTarget(source: NodeId, target: NodeId)
  case FanOutBranchMustComplete(source: NodeId, branch: NodeId)
  case InvalidVisitLimit(node: NodeId, limit: Int)
  case VisitLimitNodeMissing(node: NodeId)
  case CycleVisitLimitMissing(node: NodeId)

  /** 不包含状态正文、路由输入或节点输出的安全诊断。 */
  def message: String = this match
    case WorkflowValidationIssue.EntryNodeMissing(entry) =>
      s"入口节点不存在: ${entry.value}"
    case WorkflowValidationIssue.TransitionSourceMissing(source) =>
      s"边的源节点不存在: ${source.value}"
    case WorkflowValidationIssue.TransitionMissing(node) =>
      s"节点没有声明出边: ${node.value}"
    case WorkflowValidationIssue.TransitionTargetMissing(source, target) =>
      s"边指向不存在节点: ${source.value} -> ${target.value}"
    case WorkflowValidationIssue.NodeUnreachable(node) =>
      s"节点从入口不可达: ${node.value}"
    case WorkflowValidationIssue.DuplicateTarget(source, target) =>
      s"节点重复声明目标: ${source.value} -> ${target.value}"
    case WorkflowValidationIssue.FanOutBranchMustComplete(source, branch) =>
      s"fan-out 分支必须是单步 Complete 节点: ${source.value} -> ${branch.value}"
    case WorkflowValidationIssue.InvalidVisitLimit(node, limit) =>
      s"节点访问上限必须大于零: ${node.value}=$limit"
    case WorkflowValidationIssue.VisitLimitNodeMissing(node) =>
      s"访问上限引用不存在节点: ${node.value}"
    case WorkflowValidationIssue.CycleVisitLimitMissing(node) =>
      s"循环节点缺少访问上限: ${node.value}"

/** 已通过静态校验的不可变工作流定义。
  *
  * 构造器私有，调用方只能通过 `WorkflowDefinition.make` 得到实例，避免把缺失边、不可达节点或无界循环带入运行期。
  *
  * @param id
  *   跨部署稳定的 Workflow 名称
  * @param version
  *   与 checkpoint 恢复严格绑定的正整数定义版本
  * @param entry
  *   新 Run 的入口节点
  * @param nodes
  *   节点 ID 到执行实现的不可变映射
  * @param transitions
  *   每个节点完成后的声明式控制边
  * @param visitLimits
  *   循环节点的单 Run 最大访问次数
  */
final case class WorkflowDefinition[R, S] private (
    id: WorkflowId,
    version: WorkflowVersion,
    entry: NodeId,
    nodes: Map[NodeId, WorkflowNode[R, S]],
    transitions: Map[NodeId, WorkflowTransition[S]],
    visitLimits: Map[NodeId, Int]
)

object WorkflowDefinition:
  /** 校验后创建定义；问题按类型和节点 ID 稳定排序，避免 Map 装配顺序影响测试与启动诊断。 */
  def make[R, S](
      id: WorkflowId,
      version: WorkflowVersion,
      entry: NodeId,
      nodes: Map[NodeId, WorkflowNode[R, S]],
      transitions: Map[NodeId, WorkflowTransition[S]],
      visitLimits: Map[NodeId, Int] = Map.empty
  ): Either[Chunk[WorkflowValidationIssue], WorkflowDefinition[R, S]] =
    val issues = validate(entry, nodes, transitions, visitLimits)
    Either.cond(
      issues.isEmpty,
      WorkflowDefinition(id, version, entry, nodes, transitions, visitLimits),
      issues
    )

  /** 在不执行节点或路由函数的情况下检查图结构。 */
  def validate[R, S](
      entry: NodeId,
      nodes: Map[NodeId, WorkflowNode[R, S]],
      transitions: Map[NodeId, WorkflowTransition[S]],
      visitLimits: Map[NodeId, Int] = Map.empty
  ): Chunk[WorkflowValidationIssue] =
    val nodeIds            = nodes.keySet
    val orderedNodes       = nodeIds.toList.sortBy(_.value)
    val orderedTransitions = transitions.toList.sortBy(_._1.value)
    val adjacency          = transitions.view.mapValues(_.declaredTargets.filter(nodeIds.contains)).toMap

    val entryIssues =
      if nodeIds.contains(entry) then List.empty
      else List(WorkflowValidationIssue.EntryNodeMissing(entry))
    val sourceIssues = orderedTransitions.collect {
      case (source, _) if !nodeIds.contains(source) =>
        WorkflowValidationIssue.TransitionSourceMissing(source)
    }
    val missingTransitions = orderedNodes.collect {
      case node if !transitions.contains(node) => WorkflowValidationIssue.TransitionMissing(node)
    }
    val targetIssues = orderedTransitions.flatMap { case (source, transition) =>
      transition.declaredTargets.toList.collect {
        case target if !nodeIds.contains(target) =>
          WorkflowValidationIssue.TransitionTargetMissing(source, target)
      }
    }
    val duplicateTargets = orderedTransitions.flatMap { case (source, transition) =>
      transition.declaredTargets.toList
        .groupBy(identity)
        .collect {
          case (target, values) if values.size > 1 =>
            WorkflowValidationIssue.DuplicateTarget(source, target)
        }
        .toList
        .sortBy {
          case WorkflowValidationIssue.DuplicateTarget(_, target) => target.value
          case _                                                  => ""
        }
    }
    val fanOutIssues = orderedTransitions.flatMap {
      case (source, WorkflowTransition.FanOut(branches, _, _)) =>
        branches.toList.collect {
          case branch if transitions.get(branch).exists {
                case WorkflowTransition.Complete() => false
                case _                             => true
              } =>
            WorkflowValidationIssue.FanOutBranchMustComplete(source, branch)
        }
      case _ => List.empty
    }
    val visitLimitIssues = visitLimits.toList.sortBy(_._1.value).flatMap { case (node, limit) =>
      val missing = Option.when(!nodeIds.contains(node))(WorkflowValidationIssue.VisitLimitNodeMissing(node))
      val invalid = Option.when(limit <= 0)(WorkflowValidationIssue.InvalidVisitLimit(node, limit))
      List(missing, invalid).flatten
    }
    val reachable =
      if nodeIds.contains(entry) then traverse(entry, adjacency)
      else Set.empty[NodeId]
    val unreachable = orderedNodes.collect {
      case node if !reachable.contains(node) => WorkflowValidationIssue.NodeUnreachable(node)
    }
    val cyclic      = orderedNodes.filter(node => returnsTo(node, node, adjacency, Set(node)))
    val cycleIssues = cyclic.collect {
      case node if !visitLimits.get(node).exists(_ > 0) =>
        WorkflowValidationIssue.CycleVisitLimitMissing(node)
    }

    Chunk.fromIterable(
      entryIssues ++
        sourceIssues ++
        missingTransitions ++
        targetIssues ++
        duplicateTargets ++
        fanOutIssues ++
        visitLimitIssues ++
        unreachable ++
        cycleIssues
    )

  private def traverse(entry: NodeId, adjacency: Map[NodeId, Chunk[NodeId]]): Set[NodeId] =
    @annotation.tailrec
    def loop(pending: List[NodeId], visited: Set[NodeId]): Set[NodeId] = pending match
      case Nil                                          => visited
      case current :: rest if visited.contains(current) => loop(rest, visited)
      case current :: rest                              =>
        loop(adjacency.getOrElse(current, Chunk.empty).toList ++ rest, visited + current)
    loop(List(entry), Set.empty)

  private def returnsTo(
      start: NodeId,
      current: NodeId,
      adjacency: Map[NodeId, Chunk[NodeId]],
      visited: Set[NodeId]
  ): Boolean =
    adjacency.getOrElse(current, Chunk.empty).exists { next =>
      next == start || (!visited.contains(next) && returnsTo(start, next, adjacency, visited + next))
    }

/** checkpoint 中保存的下一恢复位置；`Completed` 是不可重新打开的终态。 */
enum WorkflowCursor:
  case At(node: NodeId)
  case Completed

/** 每个节点边界的完整恢复快照；访问次数必须与状态一起保存，否则恢复会重置循环预算。
  *
  * @param workflowId
  *   创建 Run 时冻结的 Workflow identity
  * @param definitionVersion
  *   创建 Run 时冻结的定义版本
  * @param sessionId
  *   防止已知 runId 被另一 Session 误恢复的身份边界
  * @param cursor
  *   下一节点或已完成终态
  * @param state
  *   应用定义的不可变业务状态；外部协议不应默认暴露
  * @param step
  *   已访问节点总数，也是全局执行预算
  * @param visits
  *   各节点累计访问次数，用于有界循环恢复
  */
final case class WorkflowCheckpoint[S](
    workflowId: WorkflowId,
    definitionVersion: WorkflowVersion,
    sessionId: SessionId,
    cursor: WorkflowCursor,
    state: S,
    step: Int,
    visits: Map[NodeId, Int]
):
  require(step >= 0, "Workflow checkpoint step 不能为负数")
  require(visits.values.forall(_ > 0), "Workflow checkpoint visit 必须大于零")
  require(
    visits.valuesIterator.map(_.toLong).sum == step.toLong,
    "Workflow checkpoint step 必须等于累计节点访问次数"
  )

trait WorkflowCheckpointStore[S]:
  /** 原子保存当前恢复游标、状态、步骤与访问预算。
    *
    * 同一 Run 的相同快照必须幂等；更大的 step 可以推进；陈旧、同 step 不同内容或 identity 不同的写入必须返回
    * `WorkflowCheckpointConflict`，不能覆盖已提交事实。
    */
  def save(runId: RunId, checkpoint: WorkflowCheckpoint[S]): IO[StoreError, Unit]

  /** 加载最近恢复快照；不存在返回 None。 */
  def load(runId: RunId): IO[StoreError, Option[WorkflowCheckpoint[S]]]

object WorkflowCheckpointStore:
  def inMemory[S: Tag]: ULayer[WorkflowCheckpointStore[S]] = ZLayer.fromZIO {
    Ref.Synchronized.make(Map.empty[RunId, WorkflowCheckpoint[S]]).map { ref =>
      new WorkflowCheckpointStore[S]:
        def save(runId: RunId, checkpoint: WorkflowCheckpoint[S]): IO[StoreError, Unit] =
          ref.modifyZIO { checkpoints =>
            checkpoints.get(runId) match
              case None =>
                ZIO.succeed(((), checkpoints.updated(runId, checkpoint)))
              case Some(existing) if existing == checkpoint =>
                ZIO.succeed(((), checkpoints))
              case Some(existing)
                  if sameIdentity(existing, checkpoint) &&
                    existing.cursor != WorkflowCursor.Completed &&
                    checkpoint.step > existing.step =>
                ZIO.succeed(((), checkpoints.updated(runId, checkpoint)))
              case Some(_) =>
                ZIO.fail(AgentError.WorkflowCheckpointConflict(runId, "non-monotonic-write"))
          }
        def load(runId: RunId): UIO[Option[WorkflowCheckpoint[S]]] = ref.get.map(_.get(runId))
    }
  }

  private def sameIdentity[S](
      left: WorkflowCheckpoint[S],
      right: WorkflowCheckpoint[S]
  ): Boolean =
    left.workflowId == right.workflowId &&
      left.definitionVersion == right.definitionVersion &&
      left.sessionId == right.sessionId

/** fan-out 分支状态的应用级确定性归并器。 */
trait StateReducer[S]:
  /** 合并 fan-out 分支结果。
    * @param base
    *   分支共同输入
    * @param branches
    *   每个 worker 的结果，顺序与 targets 一致
    */
  def merge(base: S, branches: Chunk[S]): IO[WorkflowError, S]

/** 单次 Engine 拉取过程中产生的结构化事件。
  *
  * 事件可能包含应用状态，不能未经低敏投影直接进入 HTTP、Trace 或日志；跨进程耐久诊断优先读取 checkpoint、ledger 和 `WorkflowExecutionStore.timeline`。
  */
enum WorkflowEvent[S]:
  case NodeStarted(node: NodeId, step: Int, visit: Int)
  case NodeCompleted(node: NodeId, step: Int, state: S)
  case FanOutStarted(node: NodeId, branches: Chunk[NodeId], join: NodeId, policy: FanInPolicy, step: Int)
  case FanOutCompleted(node: NodeId, branchCount: Int, step: Int)
  case Suspended(node: NodeId, reason: String, state: S)
  case Waiting(
      node: NodeId,
      key: WorkflowWaitKey,
      condition: WorkflowWaitCondition,
      deadline: java.time.Instant,
      state: S
  )
  case Completed(state: S)

/** 轻量、声明式 StateGraph：支持启动前校验、显式边、暂停、每节点 checkpoint 和有界 fan-out。
  *
  * 第一阶段的 fan-out 分支仍是单步节点并采用 `AllSucceeded`；节点级 pending writes、quorum/race join、耐久 timer 和分布式调度属于后续
  * 纵向切片，不在这个内存 Runtime 中伪装完成。
  */
final class WorkflowEngine[R, S] private (
    definition: WorkflowDefinition[R, S],
    store: WorkflowCheckpointStore[S],
    reducer: StateReducer[S],
    maxSteps: Int,
    maxParallelism: Int,
    durableExecution: Option[(WorkflowExecutionStore[S], WorkflowExecutionPolicy)]
):
  require(maxSteps > 0 && maxParallelism > 0)

  /** 从定义入口启动一个新 Workflow。
    *
    * @param initial
    *   业务提供的初始不可变状态
    * @param context
    *   可信 Run/Session 上下文
    * @return
    *   惰性事件流；只有下游拉取时才执行节点，失败和取消遵循 ZIO Stream 语义
    */
  def run(initial: S, context: WorkflowContext): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    execute(definition.entry, initial, 0, Map.empty, context.copy(wakeup = None), None)

  /** 当前 Engine 绑定的稳定 Workflow ID，供 wake worker 限定自己的 claim 范围。 */
  def workflowId: WorkflowId = definition.id

  /** 当前 Engine 绑定的定义版本；wake worker 不得领取其他版本的等待。 */
  def definitionVersion: WorkflowVersion = definition.version

  /** Wake worker 用于避免在失败节点 execution 仍持有租约时过早重领；不作为业务定义 API 暴露。 */
  private[workflow] def executionLeaseDuration: Option[Duration] =
    durableExecution.map(_._2.leaseDuration)

  /** 从最近 checkpoint 恢复。
    *
    * 暂停节点会从节点入口重新执行；durable 模式会复用相同 execution 的 Prepared outcome，但新 step/visit 仍代表一次新 节点访问。节点内部外部写必须使用业务幂等键或
    * outbox/inbox。
    *
    * @param context
    *   必须与 checkpoint 的 Run/Session identity 匹配
    */
  def resume(context: WorkflowContext): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    resumeInternal(context, None)

  /** 使用 Store 签发的完整 wakeup lease 恢复已决议等待。
    *
    * 普通 [[resume]] 只能恢复没有 durable wait 的显式 Suspended checkpoint。Signaled/TimedOut wait 必须先由
    * `WorkflowExecutionStore.claimWakeups` 排他领取，再通过本入口恢复；消费 wait 与下一 checkpoint 在同一 fenced commit 中完成。
    */
  def resumeClaimed(
      context: WorkflowContext,
      lease: WorkflowWakeupLease
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    resumeInternal(context, Some(lease))

  private def resumeInternal(
      context: WorkflowContext,
      claimedWakeup: Option[WorkflowWakeupLease]
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    ZStream.unwrap(
      store
        .load(context.runId)
        .mapError(error => workflowStoreError("resume", error))
        .flatMap {
          case Some(checkpoint) =>
            validateResumeIdentity(checkpoint, context) *>
              (checkpoint.cursor match
                case WorkflowCursor.At(nodeId) =>
                  durableExecution match
                    case Some((executionStore, _)) =>
                      executionStore
                        .currentWait(context.runId)
                        .mapError(error => workflowStoreError("resume-wait", error))
                        .flatMap {
                          case Some(record) =>
                            validateWaitIdentity(checkpoint, nodeId, record, context) *>
                              (claimedWakeup match
                                case None if record.status == WorkflowWaitStatus.Pending =>
                                  ZIO.fail(AgentError.WorkflowFailed(nodeId.value, "workflow-wait-pending"))
                                case None =>
                                  ZIO.fail(
                                    AgentError.WorkflowFailed(
                                      nodeId.value,
                                      "workflow-wakeup-claim-required"
                                    )
                                  )
                                case Some(lease) if lease.record != record =>
                                  ZIO.fail(
                                    AgentError.WorkflowFailed(
                                      nodeId.value,
                                      "workflow-wakeup-lease-mismatch"
                                    )
                                  )
                                case Some(lease) =>
                                  ZIO
                                    .fromOption(WorkflowWakeup.fromRecord(record))
                                    .orElseFail(
                                      AgentError.WorkflowFailed(
                                        nodeId.value,
                                        "workflow-wait-not-resumable"
                                      )
                                    )
                                    .map(wakeup =>
                                      execute(
                                        nodeId,
                                        checkpoint.state,
                                        checkpoint.step,
                                        checkpoint.visits,
                                        context.copy(wakeup = Some(wakeup)),
                                        Some(lease)
                                      )
                                    ))
                          case None =>
                            claimedWakeup match
                              case Some(_) =>
                                ZIO.fail(
                                  AgentError.WorkflowFailed(nodeId.value, "workflow-wakeup-not-found")
                                )
                              case None =>
                                ZIO.succeed(
                                  execute(
                                    nodeId,
                                    checkpoint.state,
                                    checkpoint.step,
                                    checkpoint.visits,
                                    context.copy(wakeup = None),
                                    None
                                  )
                                )
                        }
                    case None =>
                      claimedWakeup match
                        case Some(_) =>
                          ZIO.fail(
                            AgentError.WorkflowFailed(nodeId.value, "workflow-wakeup-requires-durable-engine")
                          )
                        case None =>
                          ZIO.succeed(
                            execute(
                              nodeId,
                              checkpoint.state,
                              checkpoint.step,
                              checkpoint.visits,
                              context.copy(wakeup = None),
                              None
                            )
                          )
                case WorkflowCursor.Completed =>
                  claimedWakeup match
                    case Some(_) =>
                      ZIO.fail(AgentError.WorkflowFailed("resume", "completed-workflow-has-wakeup"))
                    case None => ZIO.succeed(ZStream.succeed(WorkflowEvent.Completed(checkpoint.state))))
          case None =>
            ZIO.fail(AgentError.WorkflowFailed("resume", s"Run ${context.runId.asString} 没有 checkpoint"))
        }
    )

  /** 递归构造惰性节点流；只有下游拉取时才执行当前节点。 */
  private def execute(
      nodeId: NodeId,
      state: S,
      step: Int,
      visits: Map[NodeId, Int],
      context: WorkflowContext,
      activeWait: Option[WorkflowWakeupLease]
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    if step >= maxSteps then ZStream.fail(AgentError.WorkflowFailed(nodeId.value, s"超过最大节点数 $maxSteps"))
    else
      val visit = visits.getOrElse(nodeId, 0) + 1
      definition.visitLimits.get(nodeId) match
        case Some(limit) if visit > limit =>
          ZStream.fail(AgentError.WorkflowFailed(nodeId.value, s"超过节点访问上限 $limit"))
        case _ =>
          val nextVisits = visits.updated(nodeId, visit)
          ZStream.succeed(WorkflowEvent.NodeStarted(nodeId, step, visit)) ++
            ZStream.fromZIO(executeNode(nodeId, state, step, visit, context)).flatMap { executed =>
              executed.outcome match
                case NodeOutcome.Suspended(suspended, reason) =>
                  persist(
                    context.runId,
                    checkpoint(WorkflowCursor.At(nodeId), suspended, step + 1, nextVisits, context),
                    nodeId,
                    executed.leases,
                    WorkflowWaitCommit(activeWait, None)
                  ) ++ ZStream.succeed(WorkflowEvent.Suspended(nodeId, reason, suspended))

                case NodeOutcome.Awaiting(waiting, request) =>
                  durableExecution match
                    case None =>
                      ZStream.fail(
                        AgentError.WorkflowFailed(nodeId.value, "durable wait 只能由 makeDurable engine 执行")
                      )
                    case Some(_) =>
                      val execution = executed.leases.head.key
                      val waitKey   = WorkflowWaitKey(context.runId, execution.step, nodeId)
                      persist(
                        context.runId,
                        checkpoint(WorkflowCursor.At(nodeId), waiting, step + 1, nextVisits, context),
                        nodeId,
                        executed.leases,
                        WorkflowWaitCommit(activeWait, Some(execution -> request))
                      ) ++ ZStream.succeed(
                        WorkflowEvent.Waiting(
                          nodeId,
                          waitKey,
                          request.condition,
                          request.deadline,
                          waiting
                        )
                      )

                case NodeOutcome.Succeeded(nextState) =>
                  advance(nodeId, nextState, step, nextVisits, context, executed.leases, activeWait)
            }

  private def advance(
      current: NodeId,
      state: S,
      step: Int,
      visits: Map[NodeId, Int],
      context: WorkflowContext,
      leases: Chunk[WorkflowExecutionLease],
      activeWait: Option[WorkflowWakeupLease]
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    definition.transitions(current) match
      case WorkflowTransition.Next(next) =>
        continue(current, next, state, step, visits, context, leases, activeWait)

      case WorkflowTransition.Route(targets, select) =>
        ZStream
          .fromZIO(
            ZIO
              .fromEither(select(state))
              .flatMap { selected =>
                if targets.exists(_ == selected) then ZIO.succeed(selected)
                else
                  ZIO.fail(
                    AgentError.WorkflowFailed(
                      current.value,
                      s"路由选择了未声明目标 ${selected.value}"
                    )
                  )
              }
          )
          .flatMap(next => continue(current, next, state, step, visits, context, leases, activeWait))

      case WorkflowTransition.FanOut(branches, join, policy) =>
        executeFanOut(current, state, branches, join, policy, step, visits, context, leases, activeWait)

      case WorkflowTransition.Complete() =>
        persist(
          context.runId,
          checkpoint(WorkflowCursor.Completed, state, step + 1, visits, context),
          current,
          leases,
          WorkflowWaitCommit(activeWait, None)
        ) ++ ZStream(
          WorkflowEvent.NodeCompleted(current, step, state),
          WorkflowEvent.Completed(state)
        )

  private def continue(
      current: NodeId,
      next: NodeId,
      state: S,
      step: Int,
      visits: Map[NodeId, Int],
      context: WorkflowContext,
      leases: Chunk[WorkflowExecutionLease],
      activeWait: Option[WorkflowWakeupLease]
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    persist(
      context.runId,
      checkpoint(WorkflowCursor.At(next), state, step + 1, visits, context),
      current,
      leases,
      WorkflowWaitCommit(activeWait, None)
    ) ++
      ZStream.succeed(WorkflowEvent.NodeCompleted(current, step, state)) ++
      execute(next, state, step + 1, visits, context.copy(wakeup = None), None)

  private def executeFanOut(
      current: NodeId,
      base: S,
      branches: NonEmptyChunk[NodeId],
      join: NodeId,
      policy: FanInPolicy,
      step: Int,
      visits: Map[NodeId, Int],
      context: WorkflowContext,
      currentLeases: Chunk[WorkflowExecutionLease],
      activeWait: Option[WorkflowWakeupLease]
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    val branchIds = Chunk.fromIterable(branches)
    val joinStep  = step + branchIds.length + 1

    if joinStep >= maxSteps then
      ZStream.fail(
        AgentError.WorkflowFailed(
          current.value,
          s"fan-out 分支与 join 会超过最大节点数 $maxSteps"
        )
      )
    else
      val branchVisits = branchIds.foldLeft(visits) { (currentVisits, branch) =>
        currentVisits.updated(branch, currentVisits.getOrElse(branch, 0) + 1)
      }
      val visitPreflight = ZIO.foreachDiscard(branchIds) { branch =>
        definition.visitLimits.get(branch) match
          case Some(limit) if branchVisits(branch) > limit =>
            ZIO.fail(AgentError.WorkflowFailed(branch.value, s"超过节点访问上限 $limit"))
          case _ => ZIO.unit
      }
      val branchEffects = visitPreflight *> ZIO
        .foreachPar(branchIds.zipWithIndex) { case (branch, ordinal) =>
          executeNode(
            branch,
            base,
            step + ordinal + 1,
            branchVisits(branch),
            context
          ).flatMap { executed =>
            executed.outcome match
              case NodeOutcome.Succeeded(branchState) => ZIO.succeed(branchState -> executed.leases)
              case NodeOutcome.Suspended(_, _)        =>
                ZIO.fail(AgentError.WorkflowFailed(branch.value, "fan-out 单步分支不能暂停"))
              case NodeOutcome.Awaiting(_, _) =>
                ZIO.fail(AgentError.WorkflowFailed(branch.value, "fan-out 单步分支不能注册耐久等待"))
          }
        }
        .withParallelism(maxParallelism)

      ZStream.succeed(WorkflowEvent.FanOutStarted(current, branchIds, join, policy, step)) ++
        ZStream
          .fromZIO(
            branchEffects.flatMap(results =>
              reducer
                .merge(base, Chunk.fromIterable(results.map(_._1)))
                .map(merged => merged -> results.flatMap(_._2))
            )
          )
          .flatMap { case (merged, branchLeases) =>
            persist(
              context.runId,
              checkpoint(WorkflowCursor.At(join), merged, joinStep, branchVisits, context),
              current,
              currentLeases ++ branchLeases,
              WorkflowWaitCommit(activeWait, None)
            ) ++ ZStream(
              WorkflowEvent.FanOutCompleted(current, branchIds.length, step),
              WorkflowEvent.NodeCompleted(current, step, merged)
            ) ++ execute(join, merged, joinStep, branchVisits, context.copy(wakeup = None), None)
          }

  /** 把 checkpoint 写 effect 转成不产出元素的流，以便用 `++` 保序衔接事件。 */
  private def persist(
      runId: RunId,
      checkpoint: WorkflowCheckpoint[S],
      current: NodeId,
      leases: Chunk[WorkflowExecutionLease],
      waitCommit: WorkflowWaitCommit
  ): ZStream[Any, WorkflowError, Nothing] =
    ZStream
      .fromZIO(
        (durableExecution match
          case Some((executionStore, _)) if leases.nonEmpty =>
            executionStore.commit(NonEmptyChunk(leases.head, leases.drop(1)*), checkpoint, waitCommit)
          case _ => store.save(runId, checkpoint)
        )
          .mapError(error => workflowStoreError(s"save:${current.value}", error))
      )
      .drain

  final private case class ExecutedNode(
      outcome: NodeOutcome[S],
      leases: Chunk[WorkflowExecutionLease]
  )

  /** Durable 模式先 claim，再执行并保存 pending outcome；恢复 owner 可直接复用 Prepared 结果。 */
  private def executeNode(
      nodeId: NodeId,
      state: S,
      step: Int,
      visit: Int,
      context: WorkflowContext
  ): ZIO[R, WorkflowError, ExecutedNode] =
    durableExecution match
      case None => node(nodeId).flatMap(_.execute(state, context)).map(ExecutedNode(_, Chunk.empty))
      case Some((executionStore, policy)) =>
        val key = WorkflowExecutionKey(
          context.runId,
          definition.id,
          definition.version,
          context.sessionId,
          nodeId,
          step,
          visit
        )
        executionStore
          .claim(key, policy.owner, policy.leaseDuration)
          .mapError(error => workflowStoreError(s"claim:${nodeId.value}", error))
          .flatMap {
            case WorkflowExecutionClaim.Acquired(lease, Some(prepared)) =>
              ZIO.succeed(ExecutedNode(prepared, Chunk(lease)))
            case WorkflowExecutionClaim.Acquired(lease, None) =>
              val heartbeat =
                (ZIO.sleep(policy.heartbeatInterval) *>
                  executionStore
                    .heartbeat(lease, policy.leaseDuration)
                    .mapError(error => workflowStoreError(s"heartbeat:${nodeId.value}", error))).forever
              node(nodeId)
                .flatMap(_.execute(state, context))
                .raceFirst(heartbeat)
                .flatMap(outcome =>
                  executionStore
                    .prepare(lease, outcome)
                    .mapError(error => workflowStoreError(s"prepare:${nodeId.value}", error))
                    .as(ExecutedNode(outcome, Chunk(lease)))
                )
            case WorkflowExecutionClaim.Busy(owner, _, _) =>
              ZIO.fail(
                workflowStoreError(
                  s"claim:${nodeId.value}",
                  AgentError.WorkflowCheckpointConflict(
                    key.runId,
                    s"execution:${key.nodeId.value}:${key.step}:active-owner:${owner.value}"
                  )
                )
              )
            case WorkflowExecutionClaim.Committed(_, _) =>
              ZIO.fail(
                workflowStoreError(
                  s"claim:${nodeId.value}",
                  AgentError.WorkflowCheckpointConflict(
                    key.runId,
                    s"execution:${key.nodeId.value}:${key.step}:committed-ledger-behind-checkpoint"
                  )
                )
              )
          }

  private def checkpoint(
      cursor: WorkflowCursor,
      state: S,
      step: Int,
      visits: Map[NodeId, Int],
      context: WorkflowContext
  ): WorkflowCheckpoint[S] =
    WorkflowCheckpoint(
      definition.id,
      definition.version,
      context.sessionId,
      cursor,
      state,
      step,
      visits
    )

  private def validateResumeIdentity(
      checkpoint: WorkflowCheckpoint[S],
      context: WorkflowContext
  ): IO[WorkflowError, Unit] =
    if checkpoint.workflowId != definition.id then
      ZIO.fail(AgentError.WorkflowFailed("resume", "checkpoint-workflow-mismatch"))
    else if checkpoint.definitionVersion != definition.version then
      ZIO.fail(AgentError.WorkflowFailed("resume", "checkpoint-definition-version-mismatch"))
    else if checkpoint.sessionId != context.sessionId then
      ZIO.fail(AgentError.WorkflowFailed("resume", "checkpoint-session-mismatch"))
    else if checkpoint.cursor match
        case WorkflowCursor.At(node)  => !definition.nodes.contains(node)
        case WorkflowCursor.Completed => false
    then ZIO.fail(AgentError.WorkflowFailed("resume", "checkpoint-cursor-node-missing"))
    else if !checkpoint.visits.keySet.subsetOf(definition.nodes.keySet) then
      ZIO.fail(AgentError.WorkflowFailed("resume", "checkpoint-visit-node-missing"))
    else ZIO.unit

  /** 第三方 Store 或损坏数据不能把另一节点、Session 或 Workflow 的 wakeup 注入当前恢复节点。 */
  private def validateWaitIdentity(
      checkpoint: WorkflowCheckpoint[S],
      nodeId: NodeId,
      record: WorkflowWaitRecord,
      context: WorkflowContext
  ): IO[WorkflowError, Unit] =
    val matches = record.key.runId == context.runId &&
      record.key.nodeId == nodeId &&
      record.key.step + 1 == checkpoint.step &&
      record.workflowId == checkpoint.workflowId &&
      record.definitionVersion == checkpoint.definitionVersion &&
      record.sessionId == checkpoint.sessionId
    ZIO
      .fail(AgentError.WorkflowFailed(nodeId.value, "workflow-wait-identity-mismatch"))
      .unless(matches)
      .unit

  private def workflowStoreError(operation: String, error: StoreError): WorkflowError =
    AgentError.WorkflowPersistenceFailed(
      operation,
      error.message,
      error.category,
      error.retryable
    )

  /** 从节点表查找实现；缺失节点转为包含 ID 的 WorkflowFailed。 */
  private def node(id: NodeId): IO[WorkflowError, WorkflowNode[R, S]] =
    ZIO.fromOption(definition.nodes.get(id)).orElseFail(AgentError.WorkflowFailed(id.value, "节点不存在"))

object WorkflowEngine:
  /** Definition 已由 `WorkflowDefinition.make` 校验，因此 Engine 构造不会把启动配置错误推迟到首个请求。 */
  def make[R, S](
      definition: WorkflowDefinition[R, S],
      store: WorkflowCheckpointStore[S],
      reducer: StateReducer[S],
      maxSteps: Int = 100,
      maxParallelism: Int = 4
  ): WorkflowEngine[R, S] =
    new WorkflowEngine(definition, store, reducer, maxSteps, maxParallelism, None)

  /** 启用节点 execution ledger、pending result、lease heartbeat 与 fenced checkpoint commit。
    *
    * 这是现有 `make` 的增量耐久模式；同一 Store 同时是 checkpoint 事实源，避免跨 Adapter 的伪事务。
    */
  def makeDurable[R, S](
      definition: WorkflowDefinition[R, S],
      store: WorkflowExecutionStore[S],
      reducer: StateReducer[S],
      policy: WorkflowExecutionPolicy,
      maxSteps: Int = 100,
      maxParallelism: Int = 4
  ): WorkflowEngine[R, S] =
    new WorkflowEngine(
      definition,
      store,
      reducer,
      maxSteps,
      maxParallelism,
      Some(store -> policy)
    )

enum HandoffContextPolicy:
  case SummaryOnly
  case RecentMessages(max: Int)
  case ExplicitKeys(keys: Set[String])

final case class HandoffDefinition(
    targetAgent: AgentId,
    description: String,
    contextPolicy: HandoffContextPolicy,
    allowedTools: Set[ToolName],
    maxDepth: Int = 3
):
  require(maxDepth > 0)

final case class HandoffRequest(
    source: AgentId,
    definition: HandoffDefinition,
    depth: Int,
    visited: Set[AgentId]
)

object HandoffValidator:
  /** 校验委派深度和已访问 Agent 集合，防止无限 A→B→A 循环。 */
  def validate(request: HandoffRequest): IO[WorkflowError, Unit] =
    if request.depth >= request.definition.maxDepth then
      ZIO.fail(AgentError.WorkflowFailed("handoff", "超过最大委派深度"))
    else if request.visited.contains(request.definition.targetAgent) then
      ZIO.fail(AgentError.WorkflowFailed("handoff", "检测到 Agent 委派循环"))
    else ZIO.unit
