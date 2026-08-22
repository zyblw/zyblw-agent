package com.zyblw.agent.harness

import com.zyblw.agent.artifacts.ArtifactReference
import com.zyblw.agent.core.*
import java.util.UUID
import zio.*
import zio.json.*

/** 长任务目标。Active 只表示任务状态，不表示进程应自动启动 Runtime。 */
enum GoalStatus derives JsonCodec:
  case Draft, Active, Completed, Cancelled, Failed

opaque type GoalId = UUID
object GoalId:
  def apply(value: UUID): GoalId                        = value
  def random: UIO[GoalId]                               = Random.nextUUID.map(GoalId(_))
  def fromString(value: String): Either[String, GoalId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 GoalId: $value")
  extension (id: GoalId) def asString: String = id.toString
  given JsonCodec[GoalId]                     = JsonCodec.string.transformOrFail(fromString, _.asString)

/** 协作/执行支架，不是权限、租约或 Workflow 图。 */
enum TodoStatus derives JsonCodec:
  case Pending, InProgress, Done, Cancelled

opaque type PlanId = UUID
object PlanId:
  def apply(value: UUID): PlanId                        = value
  def random: UIO[PlanId]                               = Random.nextUUID.map(PlanId(_))
  def fromString(value: String): Either[String, PlanId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 PlanId: $value")
  extension (id: PlanId) def asString: String = id.toString
  given JsonCodec[PlanId]                     = JsonCodec.string.transformOrFail(fromString, _.asString)

opaque type TodoId = UUID
object TodoId:
  def apply(value: UUID): TodoId                        = value
  def random: UIO[TodoId]                               = Random.nextUUID.map(TodoId(_))
  def fromString(value: String): Either[String, TodoId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 TodoId: $value")
  extension (id: TodoId) def asString: String = id.toString
  given JsonCodec[TodoId]                     = JsonCodec.string.transformOrFail(fromString, _.asString)

/** 一条任务账本项；标题不是指令，也不能授予工具。 */
final case class TodoItem(
    id: TodoId,
    title: String,
    status: TodoStatus = TodoStatus.Pending,
    artifacts: Chunk[ArtifactReference] = Chunk.empty
) derives JsonCodec:
  require(title.trim.nonEmpty && title.length <= 500, "TodoItem.title 必须为 1..500 个字符")
  require(
    artifacts.length <= 8 && artifacts.distinct.length == artifacts.length,
    "TodoItem artifacts 必须唯一且最多 8 条"
  )

/** 一次协作计划。没有工具白名单、scope 或风险覆盖字段。 */
final case class Plan(
    id: PlanId,
    goalId: GoalId,
    summary: String,
    todos: Chunk[TodoItem] = Chunk.empty,
    revision: Long = 0L,
    updatedAtEpochMilli: Long = 0L,
    artifacts: Chunk[ArtifactReference] = Chunk.empty
) derives JsonCodec:
  private val allArtifactReferences = artifacts ++ todos.flatMap(_.artifacts)

  require(summary.trim.nonEmpty && summary.length <= 4000, "Plan.summary 必须为 1..4000 个字符")
  require(todos.length <= 64, "Plan.todos 最多 64 条")
  require(revision >= 0L && updatedAtEpochMilli >= 0L, "Plan revision/时间不能为负")
  require(todos.map(_.id.asString).toSet.size == todos.length, "Plan.todos id 必须唯一")
  require(
    artifacts.distinct.length == artifacts.length && allArtifactReferences.length <= 64,
    "Plan artifacts 必须唯一，Plan 与 Todo artifacts 合计最多 64 条"
  )

object Plan:
  /** 计划不能改变 Agent 可见工具。唯一有效工具集仍来自 AgentDefinition。 */
  def effectiveTools(definition: AgentDefinition, plan: Plan): Set[String] =
    val _ = plan
    definition.allowedTools

/** 长任务目标状态。与 Run 的关联可选，激活不等于领取 Worker。 */
final case class Goal(
    id: GoalId,
    threadId: ThreadId,
    objective: String,
    status: GoalStatus = GoalStatus.Draft,
    runId: Option[RunId] = None,
    revision: Long = 0L,
    updatedAtEpochMilli: Long = 0L,
    artifacts: Chunk[ArtifactReference] = Chunk.empty
) derives JsonCodec:
  require(objective.trim.nonEmpty && objective.length <= 4000, "Goal.objective 必须为 1..4000 个字符")
  require(revision >= 0L && updatedAtEpochMilli >= 0L, "Goal revision/时间不能为负")
  require(
    artifacts.length <= 32 && artifacts.distinct.length == artifacts.length,
    "Goal artifacts 必须唯一且最多 32 条"
  )
