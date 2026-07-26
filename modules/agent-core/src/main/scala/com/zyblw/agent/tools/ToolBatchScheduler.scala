package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import zio.*

/** 已解析且带原始模型顺序的工具调用。
  *
  * @param ordinal
  *   同一模型响应中的零基序号；最终结果必须按此排序，而不能采用 Fiber 完成顺序
  * @param call
  *   模型生成的调用
  * @param tool
  *   注册表解析出的工具实现和并发元数据
  */
final case class PlannedToolInvocation(ordinal: Int, call: ToolCall, tool: RegisteredTool):
  require(ordinal >= 0, "工具调用 ordinal 不能为负数")

/** 一个可以并行执行的冲突无关批次；批次之间必须顺序执行。 */
final case class ToolExecutionBatch(invocations: NonEmptyChunk[PlannedToolInvocation])

/** 工具批次规划结果。
  *
  * @param batches
  *   保持模型顺序的连续批次；批次内部无读写冲突
  * @param totalCalls
  *   总调用数，便于在执行前实施预算门禁
  */
final case class ToolExecutionPlan(batches: Chunk[ToolExecutionBatch], totalCalls: Int)

/** 一次工具调用的完整结果。`Either` 保留每个失败，避免并行批次遇到首个错误就丢失其他已完成结果。
  *
  * @param ordinal
  *   原模型序号
  * @param call
  *   原始工具调用
  * @param result
  *   结构化成功值或类型化 AgentError
  */
final case class ToolInvocationOutcome(ordinal: Int, call: ToolCall, result: Either[AgentError, ToolResult])

/** 确定性执行报告。
  *
  * @param outcomes
  *   始终按 ordinal 升序，与 Fiber 完成时序无关
  */
final case class ToolBatchReport(outcomes: Chunk[ToolInvocationOutcome]):
  /** 返回所有失败而不是只返回第一个；调用方可据此实施 all-or-nothing、best-effort 或补偿策略。 */
  def failures: Chunk[ToolInvocationOutcome] = outcomes.filter(_.result.isLeft)

  /** 只有全部调用成功时才返回按模型顺序排列的 ToolResult。 */
  def successfulResults: Either[Chunk[ToolInvocationOutcome], Chunk[ToolResult]] =
    val failed = failures
    if failed.nonEmpty then Left(failed)
    else Right(outcomes.map(_.result.toOption.get))

/** 基于静态读写冲突组的确定性批次规划器。
  *
  * 算法按模型顺序贪心构造连续批次：一个调用与当前批次任一调用冲突时就关闭批次并开启下一批。 这不是全局最大并行度求解器，但输出稳定、易审计，且不会跨过写操作重排模型意图。
  */
object ToolBatchPlanner:
  /** 生成批次计划。
    * @param invocations
    *   已按模型顺序解析的调用；ordinal 必须唯一
    * @return
    *   冲突无关的连续批次；空输入返回空计划
    */
  def plan(
      invocations: Chunk[PlannedToolInvocation]
  ): Either[AgentError.ToolInputInvalid, ToolExecutionPlan] =
    val ordinals = invocations.map(_.ordinal)
    if ordinals.distinct.length != ordinals.length then
      Left(AgentError.ToolInputInvalid("tool-batch", "ordinal 必须唯一"))
    else
      val sorted               = invocations.sortBy(_.ordinal)
      val (completed, current) =
        sorted.foldLeft((Vector.empty[ToolExecutionBatch], Vector.empty[PlannedToolInvocation])) {
          case ((batches, active), invocation) =>
            if active.isEmpty then batches -> Vector(invocation)
            else if active.exists(existing => conflicts(existing, invocation)) then
              (batches :+ batch(active)) -> Vector(invocation)
            else batches                 -> (active :+ invocation)
        }
      val all = if current.nonEmpty then completed :+ batch(current) else completed
      Right(ToolExecutionPlan(Chunk.fromIterable(all), sorted.length))

  /** SequentialOnly 工具与任何调用冲突；同组访问只要一方 Write 也冲突。 */
  private def conflicts(left: PlannedToolInvocation, right: PlannedToolInvocation): Boolean =
    val leftMetadata  = left.tool.metadata
    val rightMetadata = right.tool.metadata
    if !leftMetadata.conflictAwareParallel || !rightMetadata.conflictAwareParallel then true
    else
      leftMetadata.conflictAccesses.exists { leftAccess =>
        rightMetadata.conflictAccesses.exists { rightAccess =>
          leftAccess.group == rightAccess.group &&
          (leftAccess.mode == ToolAccessMode.Write || rightAccess.mode == ToolAccessMode.Write)
        }
      }

  /** Vector 在此处由非空分支保证，集中转成 NonEmptyChunk 避免传播 Option。 */
  private def batch(values: Vector[PlannedToolInvocation]): ToolExecutionBatch =
    ToolExecutionBatch(NonEmptyChunk.fromChunk(Chunk.fromIterable(values)).get)

/** 执行已经通过冲突检查的工具计划。
  *
  * 批次之间使用 `ZIO.foreach` 保持顺序，批次内部使用 `foreachPar` 和受控 parallelism；每个调用以 `either`
  * 收集失败，因此一个调用失败不会中断同批次其他调用。最终统一按 ordinal 排序，提供确定性回放语义。
  */
object ToolBatchExecutor:
  /** 执行计划并聚合所有结果。
    * @param plan
    *   ToolBatchPlanner 生成的批次计划
    * @param maxParallelism
    *   单批最大并行 Fiber 数，必须大于零
    * @param execute
    *   单调用执行函数；通常委托给 ToolExecutor 并使用独立工具执行账本
    */
  def execute(
      plan: ToolExecutionPlan,
      maxParallelism: Int
  )(
      execute: PlannedToolInvocation => IO[AgentError, ToolResult]
  ): IO[AgentError.ToolInputInvalid, ToolBatchReport] =
    if maxParallelism <= 0 then ZIO.fail(AgentError.ToolInputInvalid("tool-batch", "maxParallelism 必须大于零"))
    else
      ZIO
        .foreach(plan.batches) { batch =>
          ZIO
            .foreachPar(batch.invocations) { invocation =>
              execute(invocation).either
                .map(result => ToolInvocationOutcome(invocation.ordinal, invocation.call, result))
            }
            .withParallelism(maxParallelism)
        }
        .map(nested => ToolBatchReport(nested.flatten.sortBy(_.ordinal)))
