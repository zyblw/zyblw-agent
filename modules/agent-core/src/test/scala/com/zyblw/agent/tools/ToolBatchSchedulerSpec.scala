package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ToolBatchSchedulerSpec extends ZIOSpecDefault:
  /** 构造只用于规划的注册工具；invoke 由测试执行函数替代。 */
  private def registered(name: String, metadataValue: ToolMetadata): RegisteredTool = new RegisteredTool:
    val definition = ToolDefinition(name, name, Json.Obj(), None)
    val metadata   = metadataValue
    def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
      ZIO.succeed(ToolResult(arguments))

  /** 创建读写访问元数据。 */
  private def metadata(group: String, mode: ToolAccessMode): ToolMetadata =
    ToolMetadata(
      ToolRisk.ReadOnly,
      SideEffect.None,
      parallelism = ToolParallelism.ConflictAware,
      conflictAccesses = Set(ToolConflictAccess(group, mode))
    )

  /** 创建带序号的调用。 */
  private def invocation(ordinal: Int, name: String, access: ToolMetadata): PlannedToolInvocation =
    PlannedToolInvocation(ordinal, ToolCall(s"call-$ordinal", name, Json.Obj()), registered(name, access))

  def spec = suite("ToolBatchScheduler")(
    test("同组读取可并行，写入形成顺序边界，不同组可进入后续同批") {
      val calls = Chunk(
        invocation(0, "read-a-1", metadata("a", ToolAccessMode.Read)),
        invocation(1, "read-a-2", metadata("a", ToolAccessMode.Read)),
        invocation(2, "write-a", metadata("a", ToolAccessMode.Write)),
        invocation(3, "read-b", metadata("b", ToolAccessMode.Read))
      )
      val plan = ToolBatchPlanner.plan(calls).toOption.get
      assertTrue(
        plan.batches.length == 2,
        plan.batches(0).invocations.map(_.ordinal) == NonEmptyChunk(0, 1),
        plan.batches(1).invocations.map(_.ordinal) == NonEmptyChunk(2, 3)
      )
    },
    test("并行完成顺序不同且部分失败时，报告仍按模型序号并聚合全部失败") {
      val calls = Chunk(
        invocation(0, "slow", metadata("a", ToolAccessMode.Read)),
        invocation(1, "failed", metadata("a", ToolAccessMode.Read)),
        invocation(2, "fast", metadata("a", ToolAccessMode.Read))
      )
      for
        plan   <- ZIO.fromEither(ToolBatchPlanner.plan(calls))
        report <- ToolBatchExecutor.execute(plan, 3) { item =>
          if item.ordinal == 0 then ZIO.sleep(30.millis).as(ToolResult(Json.Str("slow")))
          else if item.ordinal == 1 then ZIO.fail(AgentError.ToolExecutionFailed(item.call.name, "expected"))
          else ZIO.succeed(ToolResult(Json.Str("fast")))
        }
      yield assertTrue(
        report.outcomes.map(_.ordinal) == Chunk(0, 1, 2),
        report.failures.map(_.ordinal) == Chunk(1),
        report.successfulResults.isLeft
      )
    } @@ TestAspect.withLiveClock
  )
