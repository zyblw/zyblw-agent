package com.zyblw.agent.core

import zio.Chunk
import zio.json.ast.Json

/** 只检查跨模型工具协议的共同边界，不猜测元素类型，也不改写作者的 Schema。 */
object ToolSchema:
  def validate(request: ChatRequest): Either[AgentError.InvalidConfiguration, Unit] =
    validate(request.tools).flatMap { _ =>
      if request.settings.requirement.exists(_.strictToolSchema) &&
        request.tools.exists(tool => !tool.strict || !supportsStrict(tool.inputSchema))
      then Left(AgentError.InvalidConfiguration("本次请求要求 strict 工具 Schema，但工具定义不满足严格结构合同"))
      else Right(())
    }

  def validate(tools: Chunk[ToolDefinition]): Either[AgentError.InvalidConfiguration, Unit] =
    tools.foldLeft[Either[AgentError.InvalidConfiguration, Unit]](Right(())) { (result, tool) =>
      result.flatMap(_ =>
        missingItems(tool.inputSchema, "parameters")
          .toLeft(())
          .left
          .map(path => AgentError.InvalidConfiguration(s"工具 ${tool.name} 的 $path 缺少数组 items Schema"))
      )
    }

  /** strict 是可用时的偏好；不把有可选字段或动态映射的 Schema 冒充成 OpenAI strict 合同。 */
  def supportsStrict(schema: Json): Boolean = schema match
    case Json.Obj(fields) =>
      val values = fields.toMap
      values.get("type").contains(Json.Str("object")) && !values.contains("anyOf") && strictNode(schema)
    case _ => false

  private val UnsupportedStrictKeywords = Set(
    "allOf",
    "oneOf",
    "not",
    "if",
    "then",
    "else",
    "dependentRequired",
    "dependentSchemas",
    "patternProperties",
    "prefixItems",
    "contains",
    "propertyNames",
    "unevaluatedProperties",
    "unevaluatedItems"
  )

  private def strictNode(schema: Json): Boolean = schema match
    case Json.Obj(fields) =>
      val values      = fields.toMap
      val objectValid =
        if hasType(values.get("type"), "object") || values.contains("properties") then
          val properties =
            values.get("properties").collect { case Json.Obj(p) => p.map(_._1).toSet }.getOrElse(Set.empty)
          val required = values
            .get("required")
            .collect { case Json.Arr(items) =>
              items.collect { case Json.Str(name) => name }.toSet
            }
            .getOrElse(Set.empty)
          values.get("additionalProperties").contains(Json.Bool(false)) && properties == required
        else true
      values.keySet.intersect(UnsupportedStrictKeywords).isEmpty && objectValid && children(values).forall {
        case (_, child) => strictNode(child)
      }
    case _ => false

  private def missingItems(schema: Json, path: String): Option[String] = schema match
    case Json.Obj(fields) =>
      val values = fields.toMap
      if hasType(values.get("type"), "array") && !values.get("items").exists(_.isInstanceOf[Json.Obj]) then
        Some(path)
      else
        children(values).iterator
          .flatMap { case (name, child) => missingItems(child, s"$path.$name") }
          .take(1)
          .toList
          .headOption
    case _ => None

  private def hasType(value: Option[Json], name: String): Boolean = value.exists {
    case Json.Str(actual) => actual == name
    case Json.Arr(types)  => types.contains(Json.Str(name))
    case _                => false
  }

  private def children(values: Map[String, Json]): List[(String, Json)] =
    values.toList.flatMap {
      case (key @ ("properties" | "$defs" | "definitions" | "patternProperties"), Json.Obj(fields)) =>
        fields.map { case (name, value) => s"$key.$name" -> value }.toList
      case (key @ ("allOf" | "anyOf" | "oneOf" | "prefixItems"), Json.Arr(items)) =>
        items.zipWithIndex.map { case (value, index) => s"$key.$index" -> value }.toList
      case (key @ ("items" | "additionalProperties" | "not" | "if" | "then" | "else"), value: Json.Obj) =>
        List(key -> value)
      case _ => Nil
    }
