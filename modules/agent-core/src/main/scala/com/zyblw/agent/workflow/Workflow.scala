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

  def apply(value: String): WorkflowId =
    fromString(value).fold(message => throw new IllegalArgumentException(message), identity)

  extension (id: WorkflowId) def value: String = id

opaque type WorkflowVersion = Int
object WorkflowVersion:
  def fromInt(value: Int): Either[String, WorkflowVersion] =
    Either.cond(value > 0, value, "WorkflowVersion 必须大于零")

  def apply(value: Int): WorkflowVersion =
    fromInt(value).fold(message => throw new IllegalArgumentException(message), identity)

  extension (version: WorkflowVersion) def value: Int = version

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

final case class WorkflowContext(
    runId: RunId,
    sessionId: SessionId,
    attributes: Map[String, String] = Map.empty
)

/** 一个节点只负责产生新状态或显式暂停；下一条边由图定义而不是节点实现决定。
  *
  * 这种分离让 Runtime 能在执行前检查所有可能路径，并阻止节点把未声明的动态跳转藏在业务代码中。
  */
enum NodeOutcome[S]:
  case Succeeded(state: S)
  case Suspended(state: S, reason: String)

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
  case Next(node: NodeId)
  case Route(
      targets: NonEmptyChunk[NodeId],
      select: S => Either[WorkflowError, NodeId]
  )
  case FanOut(
      branches: NonEmptyChunk[NodeId],
      join: NodeId,
      policy: FanInPolicy
  )
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

/** 已通过静态校验的不可变工作流定义。 */
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

enum WorkflowCursor:
  case At(node: NodeId)
  case Completed

/** 每个节点边界的完整恢复快照；访问次数必须与状态一起保存，否则恢复会重置循环预算。 */
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

trait StateReducer[S]:
  /** 合并 fan-out 分支结果。
    * @param base
    *   分支共同输入
    * @param branches
    *   每个 worker 的结果，顺序与 targets 一致
    */
  def merge(base: S, branches: Chunk[S]): IO[WorkflowError, S]

enum WorkflowEvent[S]:
  case NodeStarted(node: NodeId, step: Int, visit: Int)
  case NodeCompleted(node: NodeId, step: Int, state: S)
  case FanOutStarted(node: NodeId, branches: Chunk[NodeId], join: NodeId, policy: FanInPolicy, step: Int)
  case FanOutCompleted(node: NodeId, branchCount: Int, step: Int)
  case Suspended(node: NodeId, reason: String, state: S)
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

  def run(initial: S, context: WorkflowContext): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    execute(definition.entry, initial, 0, Map.empty, context)

  /** 从最近 checkpoint 恢复。暂停节点会从节点入口重新执行，因此节点在返回 `Suspend` 前的副作用必须幂等； 这与耐久工作流常见的 replay 语义一致。
    */
  def resume(context: WorkflowContext): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    ZStream.unwrap(
      store
        .load(context.runId)
        .mapError(error => workflowStoreError("resume", error))
        .flatMap {
          case Some(checkpoint) =>
            validateResumeIdentity(checkpoint, context).as {
              checkpoint.cursor match
                case WorkflowCursor.At(nodeId) =>
                  execute(nodeId, checkpoint.state, checkpoint.step, checkpoint.visits, context)
                case WorkflowCursor.Completed =>
                  ZStream.succeed(WorkflowEvent.Completed(checkpoint.state))
            }
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
      context: WorkflowContext
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
                    executed.leases
                  ) ++ ZStream.succeed(WorkflowEvent.Suspended(nodeId, reason, suspended))

                case NodeOutcome.Succeeded(nextState) =>
                  advance(nodeId, nextState, step, nextVisits, context, executed.leases)
            }

  private def advance(
      current: NodeId,
      state: S,
      step: Int,
      visits: Map[NodeId, Int],
      context: WorkflowContext,
      leases: Chunk[WorkflowExecutionLease]
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    definition.transitions(current) match
      case WorkflowTransition.Next(next) =>
        continue(current, next, state, step, visits, context, leases)

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
          .flatMap(next => continue(current, next, state, step, visits, context, leases))

      case WorkflowTransition.FanOut(branches, join, policy) =>
        executeFanOut(current, state, branches, join, policy, step, visits, context, leases)

      case WorkflowTransition.Complete() =>
        persist(
          context.runId,
          checkpoint(WorkflowCursor.Completed, state, step + 1, visits, context),
          current,
          leases
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
      leases: Chunk[WorkflowExecutionLease]
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    persist(
      context.runId,
      checkpoint(WorkflowCursor.At(next), state, step + 1, visits, context),
      current,
      leases
    ) ++
      ZStream.succeed(WorkflowEvent.NodeCompleted(current, step, state)) ++
      execute(next, state, step + 1, visits, context)

  private def executeFanOut(
      current: NodeId,
      base: S,
      branches: NonEmptyChunk[NodeId],
      join: NodeId,
      policy: FanInPolicy,
      step: Int,
      visits: Map[NodeId, Int],
      context: WorkflowContext,
      currentLeases: Chunk[WorkflowExecutionLease]
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
              currentLeases ++ branchLeases
            ) ++ ZStream(
              WorkflowEvent.FanOutCompleted(current, branchIds.length, step),
              WorkflowEvent.NodeCompleted(current, step, merged)
            ) ++ execute(join, merged, joinStep, branchVisits, context)
          }

  /** 把 checkpoint 写 effect 转成不产出元素的流，以便用 `++` 保序衔接事件。 */
  private def persist(
      runId: RunId,
      checkpoint: WorkflowCheckpoint[S],
      current: NodeId,
      leases: Chunk[WorkflowExecutionLease]
  ): ZStream[Any, WorkflowError, Nothing] =
    ZStream
      .fromZIO(
        (durableExecution match
          case Some((executionStore, _)) if leases.nonEmpty =>
            executionStore.commit(NonEmptyChunk(leases.head, leases.drop(1)*), checkpoint)
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
