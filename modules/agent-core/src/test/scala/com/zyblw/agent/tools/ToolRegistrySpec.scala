package com.zyblw.agent.tools

// 验证工具发现的默认拒绝语义，防止配置遗漏时向模型暴露宿主的全部能力。

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolRegistrySpec extends ZIOSpecDefault:
  final case class EmptyInput() derives JsonCodec
  final case class TestOutput(value: String) derives JsonCodec

  private val harmless = Tool.json[Any, EmptyInput, Nothing, TestOutput](
    ToolName("harmless"),
    "只读测试工具",
    Json.Obj("type" -> Json.Str("object")),
    None,
    ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
  )((_, _) => ZIO.succeed(TestOutput("ok")))

  /** 创建前两次失败、第三次成功的工具，用调用计数验证重试只用于明确安全的副作用类型。 */
  private def flaky(sideEffect: SideEffect, calls: Ref[Int]) =
    Tool.json[Any, EmptyInput, AgentError.ToolExecutionFailed, TestOutput](
      ToolName("flaky"),
      "重试策略测试工具",
      Json.Obj("type" -> Json.Str("object")),
      None,
      ToolMetadata(ToolRisk.ReadOnly, sideEffect)
    ) { (_, _) =>
      calls.updateAndGet(_ + 1).flatMap { count =>
        if count < 3 then ZIO.fail(AgentError.ToolExecutionFailed("flaky", "injected", retryable = true))
        else ZIO.succeed(TestOutput("ok"))
      }
    }

  def spec = suite("RegisteredToolRegistry")(
    test("空白名单不暴露任何工具") {
      for
        registered <- RegisteredTool.make(harmless)
        registry   <- ZIO
          .service[RegisteredToolRegistry]
          .provide(RegisteredToolRegistry.fromTools(List(registered)))
        empty   <- registry.definitions(Set.empty)
        allowed <- registry.definitions(Set(ToolName("harmless")))
      yield assertTrue(empty.isEmpty, allowed.map(_.name) == Chunk("harmless"))
    },
    test("重复工具名称在装配阶段失败而不是静默覆盖") {
      for
        first  <- RegisteredTool.make(harmless)
        second <- RegisteredTool.make(harmless)
        exit   <- RegisteredToolRegistry.make(List(first, second)).exit
        message = exit.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
      yield assertTrue(exit.isFailure, message.contains("harmless"))
    },
    test("IdempotentOnly 只重试纯读取或幂等写入工具") {
      for
        safeCalls   <- Ref.make(0)
        unsafeCalls <- Ref.make(0)
        safeTool    <- RegisteredTool.make(flaky(SideEffect.None, safeCalls))
        unsafeTool  <- RegisteredTool.make(flaky(SideEffect.NonIdempotentWrite, unsafeCalls))
        executor    <- ToolExecutor.make(
          ToolPolicyConfig(
            allowedTools = Set(ToolName("flaky")),
            retryPolicy = ToolRetryPolicy.IdempotentOnly(
              RetryPolicy(
                maxAttempts = 3,
                initialDelay = 1.millis,
                maxDelay = 2.millis,
                jitter = 0.0,
                maxElapsed = 1.second
              )
            )
          )
        )
        context = ToolExecutionContext(
          RunId(java.util.UUID.randomUUID()),
          ThreadId("tool-test"),
          "call-1",
          RunContext()
        )
        call = ToolCall("call-1", "flaky", Json.Obj())
        safeResult  <- executor.execute(safeTool, call, context)
        unsafeExit  <- executor.execute(unsafeTool, call, context).exit
        safeCount   <- safeCalls.get
        unsafeCount <- unsafeCalls.get
      yield assertTrue(
        !safeResult.isError,
        safeCount == 3,
        unsafeExit.isFailure,
        unsafeCount == 1
      )
    } @@ TestAspect.withLiveClock
  )
