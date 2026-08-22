package com.zyblw.agent.a2a

import zio.json.ast.Json
import zio.test.*

object A2AAgentCardSpec extends ZIOSpecDefault:
  private def card(
      url: String = "https://agent.example/a2a",
      version: String = "1.0",
      schemes: Boolean = true
  ): Json.Obj =
    val security =
      if schemes then
        Json.Obj(
          "oauth2" -> Json.Obj("type" -> Json.Str("oauth2"))
        )
      else Json.Obj()
    val fields = List(
      "name"                -> Json.Str("Research"),
      "description"         -> Json.Str("Remote research agent"),
      "version"             -> Json.Str("1.0.0"),
      "supportedInterfaces" -> Json.Arr(
        Json.Obj(
          "url"             -> Json.Str(url),
          "protocolBinding" -> Json.Str("HTTP+JSON"),
          "protocolVersion" -> Json.Str(version)
        )
      ),
      "capabilities"       -> Json.Obj("streaming" -> Json.Bool(false)),
      "defaultInputModes"  -> Json.Arr(Json.Str("text/plain")),
      "defaultOutputModes" -> Json.Arr(Json.Str("application/json")),
      "skills"             -> Json.Arr(
        Json.Obj(
          "id"          -> Json.Str("search"),
          "name"        -> Json.Str("Search"),
          "description" -> Json.Str("Search"),
          "tags"        -> Json.Arr(Json.Str("research"))
        )
      )
    ) ++ Option.when(schemes)("securitySchemes" -> security).toList
    Json.Obj(fields*)

  def spec = suite("A2AAgentCard")(
    test("解析 1.0 Agent Card，并拒绝无认证、私网和旧协议") {
      val ok         = A2AAgentCard.parse(card())
      val noAuth     = A2AAgentCard.parse(card(schemes = false))
      val privateNet = A2AAgentCard.parse(card(url = "https://10.0.0.8/a2a"))
      val query      = A2AAgentCard.parse(card(url = "https://agent.example/a2a?token=1"))
      val old        = A2AAgentCard.parse(card(version = "0.3"))
      assertTrue(
        ok.exists(_.name == "Research"),
        ok.flatMap(A2AAgentCard.remoteOnly).exists(_.protocolBinding == "HTTP+JSON"),
        noAuth.isLeft,
        privateNet.isLeft,
        query.isLeft,
        old.flatMap(A2AAgentCard.remoteOnly).isLeft
      )
    }
  )
