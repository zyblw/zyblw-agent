package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

opaque type NodeId = String
object NodeId:
  /** 构造非空节点 ID；节点 ID 同时是 checkpoint 恢复游标的一部分。 */
  def apply(value: String): NodeId =
    require(value.trim.nonEmpty, "NodeId 不能为空")
    value.trim

  /** 取出节点字符串用于 Map 查找、事件和诊断。 */
  extension (id: NodeId) def value: String = id

final case class WorkflowContext(
    runId: RunId,
    sessionId: SessionId,
    attributes: Map[String, String] = Map.empty
)

enum NodeResult[S]:
  case Next(state: S, node: NodeId)
  case Complete(state: S)
  case Suspend(state: S, reason: String)

  /** 并行执行一组单步 worker，归并后从明确的 join 节点继续，避免把最后一个 worker 错当成 join。 */
  case FanOut(state: S, nodes: NonEmptyChunk[NodeId], join: NodeId)

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
  def execute(state: S, context: WorkflowContext): ZIO[R, WorkflowError, NodeResult[S]]

trait WorkflowCheckpointStore[S]:
  /** 保存下一恢复节点、状态和步骤。 */
  def save(runId: RunId, node: NodeId, state: S, step: Int): IO[StoreError, Unit]

  /** 加载最近恢复游标；不存在返回 None。 */
  def load(runId: RunId): IO[StoreError, Option[(NodeId, S, Int)]]

object WorkflowCheckpointStore:
  def inMemory[S: Tag]: ULayer[WorkflowCheckpointStore[S]] = ZLayer.fromZIO {
    Ref.Synchronized.make(Map.empty[RunId, (NodeId, S, Int)]).map { ref =>
      new WorkflowCheckpointStore[S]:
        def save(runId: RunId, node: NodeId, state: S, step: Int): UIO[Unit] =
          ref.update(_.updated(runId, (node, state, step)))
        def load(runId: RunId): UIO[Option[(NodeId, S, Int)]] = ref.get.map(_.get(runId))
    }
  }

trait StateReducer[S]:
  /** 合并 fan-out 分支结果。
    * @param base
    *   分支共同输入
    * @param branches
    *   每个 worker 的结果，顺序与 targets 一致
    */
  def merge(base: S, branches: Chunk[S]): IO[WorkflowError, S]

enum WorkflowEvent[S]:
  case NodeStarted(node: NodeId, step: Int)
  case NodeCompleted(node: NodeId, step: Int, state: S)
  case Suspended(node: NodeId, reason: String, state: S)
  case Completed(state: S)

/** 轻量 StateGraph：支持显式跳转、暂停、检查点和有界 fan-out，不复制完整 LangGraph reducer 系统。
  */
final class WorkflowEngine[R, S](
    nodes: Map[NodeId, WorkflowNode[R, S]],
    store: WorkflowCheckpointStore[S],
    reducer: StateReducer[S],
    maxSteps: Int = 100,
    maxParallelism: Int = 4
):
  require(maxSteps > 0 && maxParallelism > 0)

  def run(entry: NodeId, initial: S, context: WorkflowContext): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    execute(entry, initial, 0, context)

  /** 从最近 checkpoint 恢复。暂停节点会从节点入口重新执行，因此节点在返回 `Suspend` 前的副作用必须幂等； 这与耐久工作流常见的 replay 语义一致。
    */
  def resume(context: WorkflowContext): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    ZStream.unwrap(
      store
        .load(context.runId)
        .mapError(error => AgentError.WorkflowFailed("resume", error.message))
        .flatMap {
          case Some((nodeId, state, step)) => ZIO.succeed(execute(nodeId, state, step, context))
          case None                        =>
            ZIO.fail(AgentError.WorkflowFailed("resume", s"Run ${context.runId.asString} 没有 checkpoint"))
        }
    )

  /** 递归构造惰性节点流；只有下游拉取时才执行当前节点。 */
  private def execute(
      nodeId: NodeId,
      state: S,
      step: Int,
      context: WorkflowContext
  ): ZStream[R, WorkflowError, WorkflowEvent[S]] =
    if step >= maxSteps then ZStream.fail(AgentError.WorkflowFailed(nodeId.value, s"超过最大节点数 $maxSteps"))
    else
      ZStream.succeed(WorkflowEvent.NodeStarted(nodeId, step)) ++
        ZStream.fromZIO(node(nodeId).flatMap(_.execute(state, context))).flatMap {
          case NodeResult.Next(nextState, next) =>
            persist(context.runId, next, nextState, step + 1, nodeId) ++
              (ZStream.succeed(WorkflowEvent.NodeCompleted(nodeId, step, nextState)) ++
                execute(next, nextState, step + 1, context))

          case NodeResult.Complete(finalState) =>
            ZStream.succeed(WorkflowEvent.Completed(finalState))

          case NodeResult.Suspend(suspended, reason) =>
            persist(context.runId, nodeId, suspended, step, nodeId) ++
              ZStream.succeed(WorkflowEvent.Suspended(nodeId, reason, suspended))

          case NodeResult.FanOut(base, targets, join) =>
            val branches = ZIO
              .foreachPar(targets)(target => node(target).flatMap(_.execute(base, context)))
              .withParallelism(maxParallelism)
            ZStream
              .fromZIO(branches.flatMap { results =>
                val states = Chunk.fromIterable(results.map {
                  case NodeResult.Next(branch, _)      => branch
                  case NodeResult.Complete(branch)     => branch
                  case NodeResult.Suspend(branch, _)   => branch
                  case NodeResult.FanOut(branch, _, _) => branch
                })
                reducer.merge(base, states)
              })
              .flatMap { merged =>
                persist(context.runId, join, merged, step + 1, nodeId) ++
                  (ZStream.succeed(WorkflowEvent.NodeCompleted(nodeId, step, merged)) ++
                    execute(join, merged, step + 1, context))
              }
        }

  /** 把 checkpoint 写 effect 转成不产出元素的流，以便用 `++` 保序衔接事件。 */
  private def persist(
      runId: RunId,
      next: NodeId,
      state: S,
      step: Int,
      current: NodeId
  ): ZStream[Any, WorkflowError, Nothing] =
    ZStream
      .fromZIO(
        store
          .save(runId, next, state, step)
          .mapError(error => AgentError.WorkflowFailed(current.value, error.message))
      )
      .drain

  /** 从节点表查找实现；缺失节点转为包含 ID 的 WorkflowFailed。 */
  private def node(id: NodeId): IO[WorkflowError, WorkflowNode[R, S]] =
    ZIO.fromOption(nodes.get(id)).orElseFail(AgentError.WorkflowFailed(id.value, "节点不存在"))

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
