package com.zyblw.agent.integrations

import zio.Chunk
import zio.json.*
import zio.test.*

/** 中转站多端点声明的静态契约：URL、密钥引用和唯一 id。 */
object ProviderEndpointsSpec extends ZIOSpecDefault:
  private val valid = ProviderEndpointDeclaration(
    providerId = "relay",
    baseUrl = "https://gateway.example/v1",
    apiKeyEnv = "RELAY_API_KEY",
    defaultModel = "deepseek-v4",
    protocol = "relay",
    models = Chunk(ProviderEndpointModel("deepseek-v4"))
  )

  def spec = suite("ProviderEndpoints")(
    test("拒绝非 HTTPS 远程 URL 和重复 providerId") {
      val httpRemote = scala.util.Try(
        valid.copy(baseUrl = "http://gateway.example/v1")
      )
      val loopback = scala.util.Try(
        valid.copy(baseUrl = "http://127.0.0.1:8080/v1")
      )
      val config = scala.util.Try(
        ProviderEndpointsConfig("relay", Chunk(valid, valid.copy(providerId = "relay")))
      )
      val decoded = scala.util.Try(
        """{"defaultProvider":"relay","endpoints":[]}""".fromJson[ProviderEndpointsConfig]
      )
      assertTrue(
        httpRemote.isFailure,
        loopback.isSuccess,
        config.isFailure,
        decoded.isFailure || decoded.toOption.exists(_.isLeft)
      )
    },
    test("JSON 编解码保留协议和模型能力") {
      val config = ProviderEndpointsConfig("relay", Chunk(valid))
      val round  = config.toJson.fromJson[ProviderEndpointsConfig]
      assertTrue(
        round.contains(config),
        valid.protocol == "relay",
        valid.models.head.capabilities.toolCalls
      )
    }
  )
