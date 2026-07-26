package com.zyblw.agent.testkit

// 测试专用 JSON Schema 工厂：减少测试样板代码，不进入生产运行路径。

import zio.*
import zio.json.ast.Json

object TestSchemas:
  def stringObject(name: String, description: String): Json.Obj =
    Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        name -> Json.Obj("type" -> Json.Str("string"), "description" -> Json.Str(description))
      ),
      "required"             -> Json.Arr(Chunk(Json.Str(name))),
      "additionalProperties" -> Json.Bool(false)
    )
