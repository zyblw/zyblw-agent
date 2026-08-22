package com.zyblw.agent.mcp

import com.zyblw.agent.core.AgentError
import zio.*
import zio.json.ast.Json
import zio.test.*

object Mcp2026ClientSpec extends ZIOSpecDefault:
  private val config = Mcp2026ClientConfig(
    McpServerId("tools"),
    McpImplementation("zyblw-agent", "0.7.0-test")
  )

  private def discoverResult(version: String = "2026-07-28"): Json.Obj =
    Json.Obj(
      "resultType"      -> Json.Str("complete"),
      "protocolVersion" -> Json.Str(version),
      "serverInfo"      -> Json.Obj("name" -> Json.Str("demo"), "version" -> Json.Str("1")),
      "capabilities"    -> Json.Obj("tools" -> Json.Obj()),
      "ttlMs"           -> Json.Num(1000),
      "cacheScope"      -> Json.Str("private")
    )

  private def toolsResult: Json.Obj =
    Json.Obj(
      "resultType" -> Json.Str("complete"),
      "tools"      -> Json.Arr(),
      "ttlMs"      -> Json.Num(0),
      "cacheScope" -> Json.Str("public")
    )

  def spec = suite("Mcp2026Client")(
    test("只调用 server/discover 与 tools/list，从不发送 initialize 或 session 通知") {
      for
        transport <- ScriptedMcpTransport.successful(
          Map("server/discover" -> Chunk(discoverResult()), "tools/list" -> Chunk(toolsResult))
        )
        client <- Mcp2026Client.make(transport, config)
        found  <- client.discover
        _      <- client.listTools
        calls  <- transport.requests
        notes  <- transport.notificationsSent
      yield assertTrue(
        found.protocolVersion == "2026-07-28",
        calls.map(_.method) == Chunk("server/discover", "tools/list"),
        calls.forall(_.params.fields.exists(_._1 == "_meta")),
        calls.forall { request =>
          request.params.fields
            .collectFirst { case ("_meta", Json.Obj(fields)) =>
              fields.exists {
                case (Mcp2026Protocol.ProtocolVersionKey, Json.Str("2026-07-28")) => true
                case _                                                            => false
              }
            }
            .getOrElse(false)
        },
        !calls.exists(_.method == "initialize"),
        notes.isEmpty
      )
    },
    test("discover 到 2025-11-25 时 fail-closed，不降级握手") {
      for
        transport <- ScriptedMcpTransport.successful(
          Map("server/discover" -> Chunk(discoverResult("2025-11-25")))
        )
        client <- Mcp2026Client.make(transport, config)
        failed <- client.discover.either
      yield assertTrue(
        failed.left.exists {
          case AgentError.ExternalProtocolFailure(_, "server/discover", message, Some(code), _, _) =>
            code == "unsupported_protocol_version" && message.contains("2025-11-25")
          case _ => false
        }
      )
    },
    test("拒绝已删除的 stateful 方法，并拒绝 session/resume 头") {
      val headers = Mcp2026Protocol.httpHeaders("tools/call", Some("lookup"))
      assertTrue(
        Mcp2026Protocol.rejectLegacyMethod("initialize").isLeft,
        Mcp2026Protocol.rejectLegacyMethod("ping").isLeft,
        Mcp2026Protocol.rejectSessionHeaders(Map("Mcp-Session-Id" -> "abc")).isLeft,
        Mcp2026Protocol.rejectSessionHeaders(Map("Last-Event-ID" -> "1")).isLeft,
        Mcp2026Protocol.rejectSessionHeaders(headers).isRight,
        headers("Mcp-Method") == "tools/call",
        headers("Mcp-Name") == "lookup"
      )
    },
    test("HTTPS endpoint 默认拒绝私网与 metadata 地址") {
      assertTrue(
        Mcp2026Endpoint.validate("https://mcp.example/mcp").isRight,
        Mcp2026Endpoint.validate("http://mcp.example/mcp").isLeft,
        Mcp2026Endpoint.validate("https://user:pass@mcp.example/mcp").isLeft,
        Mcp2026Endpoint.validate("https://mcp.example/mcp?token=1").isLeft,
        Mcp2026Endpoint.validate("https://169.254.169.254/latest").isLeft,
        Mcp2026Endpoint.validate("https://10.0.0.8/mcp").isLeft,
        Mcp2026Endpoint.validate("https://10.0.0.8/mcp", allowPrivateNetwork = true).isRight
      )
    },
    test("input_required 解析后按原方法重试，并原样回传 requestState") {
      val required = Json.Obj(
        "resultType"    -> Json.Str("input_required"),
        "requestState"  -> Json.Str("opaque-state"),
        "inputRequests" -> Json.Obj(
          "login" -> Json.Obj(
            "method" -> Json.Str("elicitation/create"),
            "params" -> Json.Obj("mode" -> Json.Str("form"))
          )
        )
      )
      val completed = Json.Obj("resultType" -> Json.Str("complete"), "ok" -> Json.Bool(true))
      for
        transport <- ScriptedMcpTransport.successful(
          Map("tools/call" -> Chunk(required, completed))
        )
        client <- Mcp2026Client.make(transport, config)
        first  <- client.invokeTool("lookup", Json.Obj("q" -> Json.Str("1")))
        retry  <- first match
          case Left(pending) =>
            client.retryTool(
              "lookup",
              Json.Obj("q" -> Json.Str("1")),
              pending,
              Json.Obj("login" -> Json.Obj("action" -> Json.Str("accept")))
            )
          case Right(_) => ZIO.fail(AgentError.InvalidConfiguration("expected-input-required"))
        calls <- transport.requests
        echoed = calls.lift(1).exists { request =>
          request.params.fields.exists {
            case ("requestState", Json.Str("opaque-state")) => true
            case _                                          => false
          } && request.params.fields.exists(_._1 == "inputResponses")
        }
      yield assertTrue(first.isLeft, retry.fields.exists(_._1 == "ok"), echoed)
    },
    test("subscriptions/listen 只接受声明的变更类型，resources/subscribe 仍被拒绝") {
      val ack = Json.Obj(
        "resultType" -> Json.Str("complete"),
        "accepted"   -> Json.Arr(Json.Str("toolsListChanged")),
        "_meta"      -> Json.Obj(Mcp2026Protocol.SubscriptionIdKey -> Json.Str("sub-1"))
      )
      for
        transport <- ScriptedMcpTransport.successful(Map("subscriptions/listen" -> Chunk(ack)))
        client    <- Mcp2026Client.make(transport, config)
        listened  <- client.listen(Set("toolsListChanged"))
        rejected  <- client.listen(Set("unknown")).either
        legacy = Mcp2026Protocol.rejectLegacyMethod("resources/subscribe")
      yield assertTrue(
        listened.accepted == Set("toolsListChanged"),
        listened.subscriptionId.contains("sub-1"),
        rejected.isLeft,
        legacy.isLeft
      )
    },
    test("OAuth issuer 与 CIMD URL 拒绝私网，iss 必须匹配已记录 issuer") {
      val issuer = "https://auth.example/issuer"
      val keyA   = Mcp2026Auth.credentialKey(issuer, "client-a")
      val keyB   = Mcp2026Auth.credentialKey("https://other.example/issuer", "client-a")
      assertTrue(
        Mcp2026Auth.validateIssuer(issuer).isRight,
        Mcp2026Auth.validateIssuer("https://10.0.0.8/issuer").isLeft,
        Mcp2026Auth.validateCimdUrl("https://app.example/.well-known/cimd.json").isRight,
        Mcp2026Auth.validateCimdUrl("https://169.254.169.254/latest").isLeft,
        Mcp2026Auth.validateIssParameter(issuer, Some(issuer)).isRight,
        Mcp2026Auth.validateIssParameter(issuer, Some("https://evil.example")).isLeft,
        keyA.isRight,
        keyB.isRight,
        keyA.toOption != keyB.toOption
      )
    },
    test("cache hint 必须成对且合法") {
      val ok  = Mcp2026Protocol.cacheHint(toolsResult)
      val bad =
        Mcp2026Protocol.cacheHint(Json.Obj("resultType" -> Json.Str("complete"), "ttlMs" -> Json.Num(1)))
      assertTrue(ok.contains(Some(Mcp2026Protocol.CacheHint(0, "public"))), bad.isLeft)
    }
  )
