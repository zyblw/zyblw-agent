package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.*
import zio.test.*

object LiveModelRegistrySpec extends ZIOSpecDefault:
  private val routes: Routes[Any, Response] = Routes(
    Method.POST / "v1" / "chat" / "completions" -> handler { (request: Request) =>
      val auth  = request.headers.get("Authorization").getOrElse("")
      val title = request.headers.get("X-Title").getOrElse("")
      ZIO.succeed(
        Response.json(
          s"""{"id":"stub","choices":[{"message":{"content":"$auth|$title"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""
        )
      )
    }
  )

  private def connection(id: String, port: Int, key: String, headers: Map[String, String] = Map.empty) =
    ModelConnectionSpec(
      providerId = id,
      displayName = id,
      protocol = ModelWireProtocol.OpenAICompatible,
      baseUrl = s"http://127.0.0.1:$port/v1",
      apiKey = key,
      defaultModel = s"$id-model",
      compatibilityProfile = Some("generic"),
      models = Chunk(ProviderEndpointModel(s"$id-model")),
      extraHeaders = headers,
      credentialReference = s"db:connection/$id"
    )

  private def ask(model: ChatModel, provider: String): IO[AgentError, String] =
    model
      .complete(
        ChatRequest(
          Chunk(AgentMessage.user("hi")),
          settings = ModelSettings(provider = Some(provider), toolChoice = ToolChoice.None)
        )
      )
      .map(_.message.text)

  def spec = suite("LiveModelRegistry")(
    test("未配置连接时以可操作的配置错误失败,而不是启动失败") {
      for
        client   <- ZIO.service[Client]
        registry <- LiveModelRegistry.make(client, ModelRegistrySource.static(ModelRegistrySnapshot.empty))
        result   <- ask(registry.chatModel, "any").either
        current  <- registry.registry
      yield assertTrue(
        current.isEmpty,
        result.left.exists {
          case AgentError.InvalidConfiguration(message) => message.contains("管理台")
          case _                                        => false
        }
      )
    }.provide(Client.default),
    test("按 provider 路由多个连接,发送额外请求头,换 Key 后新版本立即生效") {
      for
        _        <- TestServer.addRoutes(routes)
        port     <- ZIO.serviceWithZIO[Server](_.port)
        client   <- ZIO.service[Client]
        ref      <- Ref.make(
          ModelRegistrySnapshot(
            1L,
            Some("alpha"),
            Chunk(connection("alpha", port, "key-a1", Map("X-Title" -> "zyblw")), connection("beta", port, "key-b1"))
          )
        )
        registry <- LiveModelRegistry.make(client, ModelRegistrySource.fromRef(ref))
        alpha    <- ask(registry.chatModel, "alpha")
        beta     <- ask(registry.chatModel, "beta")
        _        <- ref.update(snapshot =>
          snapshot.copy(
            revision = 2L,
            connections = snapshot.connections.map(c => if c.providerId == "alpha" then c.copy(apiKey = "key-a2") else c)
          )
        )
        rotated <- ask(registry.chatModel, "alpha")
        missing <- ask(registry.chatModel, "gamma").either
      yield assertTrue(
        alpha == "Bearer key-a1|zyblw",
        beta == "Bearer key-b1|",
        rotated == "Bearer key-a2|zyblw",
        missing.left.exists(_.isInstanceOf[AgentError.ProviderNotFound])
      )
    }.provide(Client.default, TestServer.default),
    test("live 目录随注册表版本变化,未保存的连接可以直接探活") {
      for
        _        <- TestServer.addRoutes(routes)
        port     <- ZIO.serviceWithZIO[Server](_.port)
        client   <- ZIO.service[Client]
        ref      <- Ref.make(ModelRegistrySnapshot.empty)
        registry <- LiveModelRegistry.make(client, ModelRegistrySource.fromRef(ref))
        catalog = ModelCatalogLive.live(registry.registry, ZIO.succeed(ModelPriceBook.empty))
        before <- catalog.options
        _      <- ref.set(ModelRegistrySnapshot(1L, None, Chunk(connection("alpha", port, "key"))))
        after  <- catalog.options
        probe  <- registry.probeSpec(connection("draft", port, "draft-key"), None)
      yield assertTrue(
        before.isEmpty,
        after.map(o => o.provider -> o.model) == Chunk("alpha" -> "alpha-model"),
        after.head.credential.reference == "db:connection/alpha",
        probe.succeeded,
        probe.model == "draft-model"
      )
    }.provide(Client.default, TestServer.default),
    test("连接值的 toString 不包含密钥,保留请求头不可覆盖") {
      val value      = connection("alpha", 443, "sk-secret-value")
      val overriding = scala.util.Try(value.copy(extraHeaders = Map("Authorization" -> "x")))
      assertTrue(!value.toString.contains("sk-secret-value"), overriding.isSuccess) &&
      assertTrue(
        scala.util
          .Try(
            com.zyblw.agent.integrations.openai.OpenAICompatibleConfig(
              "https://example.com/v1",
              "k",
              "m",
              extraHeaders = Map("Authorization" -> "x")
            )
          )
          .isFailure
      )
    }
  )
