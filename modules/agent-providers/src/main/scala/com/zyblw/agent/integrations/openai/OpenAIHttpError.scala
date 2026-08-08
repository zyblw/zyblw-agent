package com.zyblw.agent.integrations.openai

import zio.json.*
import zio.json.ast.Json

/** OpenAI 风格错误 envelope 的低敏投影。
  *
  * 只读取 `error.code` / `error.type`；`message` 可能回显请求正文、组织信息或代理错误页，不能进入框架错误。 最终的字符集与长度约束由
  * `AgentError.ModelHttpFailure` 统一执行。
  */
private[openai] object OpenAIHttpError:
  def code(body: String): Option[String] =
    body
      .fromJson[Json]
      .toOption
      .flatMap(field(_, "error"))
      .flatMap(error => stringField(error, "code").orElse(stringField(error, "type")))

  private def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.collectFirst { case (`name`, value) => value }
    case _                => None

  private def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }
