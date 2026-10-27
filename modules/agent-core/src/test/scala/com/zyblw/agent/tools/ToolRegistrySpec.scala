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
        executor    <- ZIO.serviceWithZIO[com.zyblw.agent.artifacts.ArtifactStore](
          ToolExecutor.make(
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
            ),
            _
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
    } @@ TestAspect.withLiveClock,
    test("部署 Never 时即使 ReplaySafe 工具也不会在线热重试") {
      for
        calls    <- Ref.make(0)
        tool     <- RegisteredTool.make(flaky(SideEffect.None, calls))
        executor <- ZIO.serviceWithZIO[com.zyblw.agent.artifacts.ArtifactStore](
          ToolExecutor.make(
            ToolPolicyConfig(
              allowedTools = Set(ToolName("flaky")),
              retryPolicy = ToolRetryPolicy.Never
            ),
            _
          )
        )
        context = ToolExecutionContext(
          RunId(java.util.UUID.randomUUID()),
          ThreadId("tool-never-retry"),
          "call-1",
          RunContext()
        )
        exit  <- executor.execute(tool, ToolCall("call-1", "flaky", Json.Obj()), context).exit
        count <- calls.get
      yield assertTrue(exit.isFailure, count == 1)
    },
    test("IdempotentOnly 也不会在线重试 RequiresApproval 工具") {
      for
        calls    <- Ref.make(0)
        tool     <- RegisteredTool.make(flaky(SideEffect.Destructive, calls))
        executor <- ZIO.serviceWithZIO[com.zyblw.agent.artifacts.ArtifactStore](
          ToolExecutor.make(
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
            ),
            _
          )
        )
        context = ToolExecutionContext(
          RunId(java.util.UUID.randomUUID()),
          ThreadId("tool-destructive"),
          "call-1",
          RunContext()
        )
        exit  <- executor.execute(tool, ToolCall("call-1", "flaky", Json.Obj()), context).exit
        count <- calls.get
      yield assertTrue(exit.isFailure, count == 1)
    } @@ TestAspect.withLiveClock,
    test("RegisteredTool 执行期校验 JSON Schema 实例约束并按 sensitiveInputFields/sensitiveOutputFields 脱敏") {
      final case class SecretInput(mode: String, apiKey: String) derives JsonCodec
      final case class SecretOutput(status: String, token: String) derives JsonCodec
      val schema = Json.Obj(
        "type"                 -> Json.Str("object"),
        "additionalProperties" -> Json.Bool(false),
        "required"             -> Json.Arr(Json.Str("mode"), Json.Str("apiKey")),
        "properties"           -> Json.Obj(
          "mode" -> Json.Obj(
            "type" -> Json.Str("string"),
            "enum" -> Json.Arr(Json.Str("safe"), Json.Str("strict"))
          ),
          "apiKey" -> Json.Obj(
            "type"      -> Json.Str("string"),
            "maxLength" -> Json.Num(16)
          )
        )
      )
      val outSchema = Json.Obj(
        "type"       -> Json.Str("object"),
        "required"   -> Json.Arr(Json.Str("status"), Json.Str("token")),
        "properties" -> Json.Obj(
          "status" -> Json.Obj("type" -> Json.Str("string")),
          "token"  -> Json.Obj("type" -> Json.Str("string"))
        )
      )
      val toolSpec = Tool.json[Any, SecretInput, Nothing, SecretOutput](
        ToolName("secret_tool"),
        "带约束与脱敏的工具",
        schema,
        Some(outSchema),
        ToolMetadata(
          ToolRisk.ReadOnly,
          SideEffect.None,
          sensitiveInputFields = Set("apiKey"),
          sensitiveOutputFields = Set("token")
        )
      )((in, _) => ZIO.succeed(SecretOutput(in.mode, s"tok-${in.apiKey}")))
      val context = ToolExecutionContext(
        RunId(java.util.UUID.randomUUID()),
        ThreadId("tool-schema"),
        "call-sec",
        RunContext()
      )
      for
        reg     <- RegisteredTool.make(toolSpec)
        badEnum <- reg
          .invoke(Json.Obj("mode" -> Json.Str("illegal"), "apiKey" -> Json.Str("k1")), context)
          .exit
        extraField <- reg
          .invoke(
            Json.Obj("mode" -> Json.Str("safe"), "apiKey" -> Json.Str("k1"), "extra" -> Json.Str("x")),
            context
          )
          .exit
        goodOut <- reg.invoke(
          Json.Obj("mode" -> Json.Str("safe"), "apiKey" -> Json.Str("secret-123")),
          context
        )
        redactedIn = ToolMetadata.redactJson(
          Json.Obj("mode" -> Json.Str("safe"), "apiKey" -> Json.Str("secret-123")),
          reg.metadata.sensitiveInputFields
        )
      yield assertTrue(
        badEnum.isFailure,
        extraField.isFailure,
        goodOut.toJson.contains("[REDACTED]"),
        !goodOut.toJson.contains("tok-secret-123"),
        redactedIn.toJson.contains("[REDACTED]"),
        !redactedIn.toJson.contains("secret-123")
      )
    },
    test(
      "R9/R10：optional null 拒绝、anyOf/oneOf/const/$ref 约束、无 properties 的 additionalProperties、数值错误不回显原值、自定义 Tool 统一校验与脱敏"
    ) {
      val complexSchema = Json.Obj(
        "type"                 -> Json.Str("object"),
        "additionalProperties" -> Json.Bool(false),
        "required"             -> Json.Arr(Json.Str("nullableReq"), Json.Str("choice")),
        "properties"           -> Json.Obj(
          "optInt"      -> Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(10)),
          "nullableReq" -> Json.Obj(
            "anyOf" -> Json.Arr(
              Json.Obj("type" -> Json.Str("string"), "minLength" -> Json.Num(2)),
              Json.Obj("type" -> Json.Str("null"))
            )
          ),
          "choice" -> Json.Obj(
            "anyOf" -> Json.Arr(
              Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(10)),
              Json.Obj("type" -> Json.Str("integer"), "maximum" -> Json.Num(0))
            )
          )
        )
      )
      val emptyObjSchema = Json.Obj(
        "type"                 -> Json.Str("object"),
        "additionalProperties" -> Json.Bool(false)
      )
      val unsupportedRefSchema = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj("x" -> Json.Obj("$ref" -> Json.Str("#/$defs/X")))
      )
      val context = ToolExecutionContext(
        RunId(java.util.UUID.randomUUID()),
        ThreadId("tool-r9-r10"),
        "call-custom",
        RunContext()
      )
      for
        customCalls <- Ref.make(0)
        customTool = new Tool[Any, Json, AgentError, Json]:
          val name         = ToolName("custom_raw_tool")
          val description  = "不使用 Tool.json 的自定义工具"
          val inputSchema  = complexSchema
          val outputSchema = Some(
            Json.Obj(
              "type"       -> Json.Str("object"),
              "required"   -> Json.Arr(Json.Str("secretOut")),
              "properties" -> Json.Obj("secretOut" -> Json.Obj("type" -> Json.Str("string")))
            )
          )
          val metadata = ToolMetadata(
            ToolRisk.ReadOnly,
            SideEffect.None,
            sensitiveInputFields = Set("optInt"),
            sensitiveOutputFields = Set("secretOut")
          )
          def decodeInput(input: Json): Either[AgentError.ToolInputInvalid, Json]         = Right(input)
          def execute(input: Json, ctx: ToolExecutionContext): ZIO[Any, AgentError, Json] =
            customCalls.update(_ + 1).as(Json.Obj("secretOut" -> Json.Str("raw-secret-token")))
          def encodeOutput(output: Json): Either[AgentError.ToolExecutionFailed, Json] = Right(output)
        reg         <- RegisteredTool.make(customTool)
        optNullExit <- reg
          .invoke(
            Json.Obj("optInt" -> Json.Null, "nullableReq" -> Json.Null, "choice" -> Json.Num(12)),
            context
          )
          .exit
        badAnyOfExit <- reg
          .invoke(
            Json.Obj("nullableReq" -> Json.Null, "choice" -> Json.Num(5)),
            context
          )
          .exit
        secretNumExit <- reg
          .invoke(
            Json.Obj("optInt" -> Json.Num(7), "nullableReq" -> Json.Null, "choice" -> Json.Num(12)),
            context
          )
          .exit
        callsAfterInvalid <- customCalls.get
        validOut          <- reg.invoke(
          Json.Obj("optInt" -> Json.Num(15), "nullableReq" -> Json.Null, "choice" -> Json.Num(-1)),
          context
        )
        callsAfterValid <- customCalls.get
        noPropsExtra = JsonSchemaInstanceValidator.validate(
          emptyObjSchema,
          Json.Obj("unexpected" -> Json.Str("v"))
        )
        unsupportedRef = JsonSchemaInstanceValidator.validate(
          unsupportedRefSchema,
          Json.Obj("x" -> Json.Str("v"))
        )
        notUnsupportedRef = JsonSchemaInstanceValidator.validate(
          Json.Obj(
            "type" -> Json.Str("object"),
            "not"  -> unsupportedRefSchema
          ),
          Json.Obj("x" -> Json.Str("v"))
        )
        allOfNullableReq = JsonSchemaInstanceValidator.validate(
          Json.Obj(
            "type"       -> Json.Str("object"),
            "required"   -> Json.Arr(Json.Str("v")),
            "properties" -> Json.Obj(
              "v" -> Json.Obj(
                "allOf" -> Json.Arr(
                  Json.Obj(
                    "anyOf" -> Json.Arr(
                      Json.Obj("type" -> Json.Str("string")),
                      Json.Obj("type" -> Json.Str("null"))
                    )
                  ),
                  Json.Obj("description" -> Json.Str("wrapped nullable"))
                )
              )
            )
          ),
          Json.Obj("v" -> Json.Null)
        )
        invalidCustomOutTool = new Tool[Any, Json, AgentError, Json]:
          val name         = ToolName("custom_invalid_out_tool")
          val description  = "带敏感字段且返回违反 outputSchema 的自定义工具"
          val inputSchema  = Json.Obj("type" -> Json.Str("object"))
          val outputSchema = Some(
            Json.Obj(
              "type"                 -> Json.Str("object"),
              "additionalProperties" -> Json.Bool(false),
              "required"             -> Json.Arr(Json.Str("status"), Json.Str("secretOut")),
              "properties"           -> Json.Obj(
                "status"    -> Json.Obj("type" -> Json.Str("string"), "enum" -> Json.Arr(Json.Str("ok"))),
                "secretOut" -> Json.Obj("type" -> Json.Str("string"), "minLength" -> Json.Num(12))
              )
            )
          )
          val metadata = ToolMetadata(
            ToolRisk.ReadOnly,
            SideEffect.None,
            sensitiveOutputFields = Set("secretOut")
          )
          def decodeInput(input: Json): Either[AgentError.ToolInputInvalid, Json]         = Right(input)
          def execute(input: Json, ctx: ToolExecutionContext): ZIO[Any, AgentError, Json] =
            ZIO.succeed(Json.Obj("status" -> Json.Str("bad"), "secretOut" -> Json.Str("short")))
          def encodeOutput(output: Json): Either[AgentError.ToolExecutionFailed, Json] = Right(output)
        invalidOutReg  <- RegisteredTool.make(invalidCustomOutTool)
        invalidOutExit <- invalidOutReg.invoke(Json.Obj(), context).exit
        errMessage = secretNumExit.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
      yield assertTrue(
        optNullExit.isFailure,
        badAnyOfExit.isFailure,
        secretNumExit.isFailure,
        !errMessage.contains("7"),
        callsAfterInvalid == 0,
        callsAfterValid == 1,
        validOut.toJson.contains("[REDACTED]"),
        !validOut.toJson.contains("raw-secret-token"),
        noPropsExtra.isLeft,
        unsupportedRef.isLeft,
        notUnsupportedRef.isLeft,
        allOfNullableReq.isRight,
        invalidOutExit.isFailure
      )
    }
  ).provide(com.zyblw.agent.artifacts.ArtifactStore.inMemory())
