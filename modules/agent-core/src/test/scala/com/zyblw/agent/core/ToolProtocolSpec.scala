package com.zyblw.agent.core

import zio.Chunk
import com.zyblw.agent.model.ModelRequirement
import zio.json.ast.Json
import zio.test.*

object ToolProtocolSpec extends ZIOSpecDefault:
  private val valid = ToolCall("call-1", "lookup", Json.Obj("secret" -> Json.Str("private-input")))

  def spec = suite("Tool protocol boundaries")(
    test("invalid identities, duplicate ids and non-object arguments fail without exposing payloads") {
      val invalid = Chunk(
        Chunk(valid.copy(id = "")),
        Chunk(valid.copy(id = "  ")),
        Chunk(valid.copy(name = "  ")),
        Chunk(valid, valid),
        Chunk(valid.copy(arguments = Json.Arr()))
      ).map(ToolCall.validateAll)
      assertTrue(
        ToolCall.validateAll(Chunk.empty).isRight,
        ToolCall.validateAll(Chunk(valid, valid.copy(id = "call-2"))).isRight,
        invalid.forall(_.isLeft),
        invalid.forall(_.left.toOption.exists(error => !error.message.contains("private-input")))
      )
    },
    test("nested arrays require explicit items before any provider request") {
      val malformed = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "filters" -> Json.Obj(
            "anyOf" -> Json.Arr(
              Json.Obj("type" -> Json.Str("array")),
              Json.Obj("type" -> Json.Str("null"))
            )
          )
        )
      )
      val result = ToolSchema.validate(Chunk(ToolDefinition("search", "search", malformed)))
      assertTrue(result.left.toOption.exists(_.message.contains("parameters.properties.filters.anyOf.0")))
    },
    test("strict requires closed objects and all properties required, including nested objects") {
      val schema = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "values" -> Json.Obj(
            "type"  -> Json.Str("array"),
            "items" -> Json.Obj("type" -> Json.Str("string"))
          )
        ),
        "required"             -> Json.Arr(Json.Str("values")),
        "additionalProperties" -> Json.Bool(false)
      )
      assertTrue(
        ToolSchema.validate(Chunk(ToolDefinition("search", "search", schema))).isRight,
        ToolSchema.supportsStrict(schema),
        !ToolSchema.supportsStrict(Json.Obj(schema.fields.filterNot(_._1 == "required"))),
        !ToolSchema.supportsStrict(Json.Obj(schema.fields.filterNot(_._1 == "additionalProperties"))),
        !ToolSchema.supportsStrict(Json.Obj("type" -> Json.Str("array"), "items" -> schema)),
        !ToolSchema.supportsStrict(Json.Obj(schema.fields :+ ("anyOf" -> Json.Arr(schema)))),
        !ToolSchema.supportsStrict(Json.Obj(schema.fields :+ ("allOf" -> Json.Arr(schema)))),
        ToolSchema
          .validate(
            ChatRequest(
              Chunk.empty,
              Chunk(ToolDefinition("search", "search", schema)),
              ModelSettings(requirement = Some(ModelRequirement(strictToolSchema = true)))
            )
          )
          .isRight,
        ToolSchema
          .validate(
            ChatRequest(
              Chunk.empty,
              Chunk(ToolDefinition("search", "search", Json.Obj("type" -> Json.Str("object")))),
              ModelSettings(requirement = Some(ModelRequirement(strictToolSchema = true)))
            )
          )
          .isLeft
      )
    }
  )
