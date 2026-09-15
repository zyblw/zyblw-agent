package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import java.time.{ZoneId, ZonedDateTime}
import zio.*
import zio.json.*
import zio.json.ast.Json

enum ArithmeticOperation derives JsonCodec:
  case Add, Subtract, Multiply, Divide

final case class CalculatorInput(left: BigDecimal, operation: ArithmeticOperation, right: BigDecimal)
    derives JsonCodec
final case class CalculatorOutput(value: BigDecimal) derives JsonCodec

/** 只允许显式四则运算，不执行模型生成的表达式或脚本。 */
object CalculatorTool:
  val schema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "left"      -> Json.Obj("type" -> Json.Str("number")),
      "operation" -> Json.Obj(
        "type" -> Json.Str("string"),
        "enum" -> Json.Arr(Json.Str("Add"), Json.Str("Subtract"), Json.Str("Multiply"), Json.Str("Divide"))
      ),
      "right" -> Json.Obj("type" -> Json.Str("number"))
    ),
    "required"             -> Json.Arr(Json.Str("left"), Json.Str("operation"), Json.Str("right")),
    "additionalProperties" -> Json.Bool(false)
  )

  val live: Tool[Any, CalculatorInput, AgentError, CalculatorOutput] = Tool.json(
    ToolName("calculator"),
    "执行安全的四则运算",
    schema,
    None,
    ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
  ) { (input, _) =>
    input.operation match
      case ArithmeticOperation.Add      => ZIO.succeed(CalculatorOutput(input.left + input.right))
      case ArithmeticOperation.Subtract => ZIO.succeed(CalculatorOutput(input.left - input.right))
      case ArithmeticOperation.Multiply => ZIO.succeed(CalculatorOutput(input.left * input.right))
      case ArithmeticOperation.Divide if input.right == 0 =>
        ZIO.fail(AgentError.ToolInputInvalid("calculator", "除数不能为零"))
      case ArithmeticOperation.Divide => ZIO.succeed(CalculatorOutput(input.left / input.right))
  }

final case class CurrentTimeInput(zoneId: String) derives JsonCodec
final case class CurrentTimeOutput(iso8601: String, epochMilli: Long) derives JsonCodec

object CurrentTimeTool:
  val live: Tool[Any, CurrentTimeInput, AgentError, CurrentTimeOutput] = Tool.json(
    ToolName("current_time"),
    "返回指定 IANA 时区的当前时间",
    Json.Obj(
      "type"                 -> Json.Str("object"),
      "properties"           -> Json.Obj("zoneId" -> Json.Obj("type" -> Json.Str("string"))),
      "required"             -> Json.Arr(Json.Str("zoneId")),
      "additionalProperties" -> Json.Bool(false)
    ),
    None,
    ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
  ) { (input, _) =>
    for
      zone <- ZIO
        .attempt(ZoneId.of(input.zoneId))
        .mapError(error => AgentError.ToolInputInvalid("current_time", error.getMessage))
      now <- Clock.instant
    yield CurrentTimeOutput(ZonedDateTime.ofInstant(now, zone).toString, now.toEpochMilli)
  }

final case class DangerousActionInput(target: String, confirmationNote: String) derives JsonCodec
final case class DangerousActionOutput(executed: Boolean) derives JsonCodec

/** 教程用危险工具：风险元数据保证安全策略会进入人工审批。 */
object DangerousActionTool:
  val simulated: Tool[Any, DangerousActionInput, AgentError, DangerousActionOutput] = Tool.json(
    ToolName("dangerous_action"),
    "模拟需要人工审批的危险操作；不执行真实副作用",
    Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "target"           -> Json.Obj("type" -> Json.Str("string")),
        "confirmationNote" -> Json.Obj("type" -> Json.Str("string"))
      ),
      "required"             -> Json.Arr(Json.Str("target"), Json.Str("confirmationNote")),
      "additionalProperties" -> Json.Bool(false)
    ),
    None,
    ToolMetadata(ToolRisk.AdminApproval, SideEffect.Destructive)
  )((_, _) => ZIO.succeed(DangerousActionOutput(executed = true)))

final case class ReadArtifactInput(name: String, version: Option[Long] = None) derives JsonCodec

/** 读取当前 Run 外置的工具结果。scope 只从执行上下文派生，模型不能自报隔离域。 */
object ReadArtifactTool:
  val Name: ToolName = ToolName("read_artifact")

  val schema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "name"    -> Json.Obj("type" -> Json.Str("string")),
      "version" -> Json.Obj("type" -> Json.Str("integer"))
    ),
    "required"             -> Json.Arr(Json.Str("name")),
    "additionalProperties" -> Json.Bool(false)
  )

  def live(store: com.zyblw.agent.artifacts.ArtifactStore): Tool[Any, ReadArtifactInput, AgentError, Json] =
    Tool.json(
      Name,
      "读取当前 Run 已外置的工具结果正文",
      schema,
      None,
      ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
    ) { (input, context) =>
      val scope = ArtifactScope.of(context)
      for
        name <- ZIO
          .fromEither(ArtifactName.fromString(input.name))
          .mapError(message => AgentError.ToolInputInvalid(Name.value, message))
        loaded <- store
          .read(scope, name, input.version)
          .mapError(error =>
            AgentError.ToolExecutionFailed(
              Name.value,
              if error.safeToExpose then error.message else "读取外置结果失败",
              retryable = false
            )
          )
        artifact <- ZIO
          .fromOption(loaded)
          .orElseFail(AgentError.ToolInputInvalid(Name.value, "artifact-not-found"))
        reference = artifact.descriptor.reference
        _ <- ZIO
          .fromEither(com.zyblw.agent.artifacts.ArtifactAccessGrant.authorize(reference, context))
          .mapError(reason => AgentError.PermissionDenied(Name.value, reason))
        json <- ZIO
          .fromEither(
            new String(artifact.bytes.toArray, java.nio.charset.StandardCharsets.UTF_8).fromJson[Json]
          )
          .orElseSucceed(
            Json.Obj(
              "bytes"     -> Json.Num(BigDecimal(artifact.bytes.length)),
              "mediaType" -> Json.Str(artifact.descriptor.mediaType)
            )
          )
      yield json
    }

  def registered(store: com.zyblw.agent.artifacts.ArtifactStore): UIO[RegisteredTool] =
    RegisteredTool.make(live(store))
