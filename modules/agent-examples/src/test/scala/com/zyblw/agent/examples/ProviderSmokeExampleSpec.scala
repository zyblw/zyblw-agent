package com.zyblw.agent.examples

import zio.*
import zio.http.Client
import zio.test.*

object ProviderSmokeExampleSpec extends ZIOSpecDefault:
  private val endpointsJson =
    """{"defaultProvider":"relay-deepseek","endpoints":[{"providerId":"relay-deepseek","baseUrl":"https://gateway.example/v1","apiKeyEnv":"RELAY_API_KEY","defaultModel":"deepseek-test","protocol":"relay","compatibilityProfile":"deepseek","models":[{"name":"deepseek-test","capabilities":{"toolCalls":true,"streaming":true,"thinking":true,"reasoningTokens":true,"reasoningEfforts":["None","Low","High","Max"]}}]}]}"""

  def spec = suite("ProviderSmokeExample relay target")(
    test("从多端点配置选择默认中转 Provider 且不发起网络请求") {
      (for
        client <- ZIO.service[Client]
        target <- ProviderSmokeExample.loadTarget("relay", client)
      yield assertTrue(
        target.model.provider == "relay-deepseek",
        target.modelId == "deepseek-test",
        target.model.descriptor.capabilitiesFor(Some(target.modelId)).thinking
      )).provide(
        Client.default,
        Runtime.setConfigProvider(
          ConfigProvider.fromMap(
            Map(
              "ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON" -> endpointsJson,
              "RELAY_API_KEY"                       -> "test-secret"
            )
          )
        )
      )
    },
    test("未知中转 Provider 在任何网络请求前失败且不泄漏 Key") {
      val effect = for
        client <- ZIO.service[Client]
        target <- ProviderSmokeExample.loadTarget("relay", client)
      yield target
      effect
        .provide(
          Client.default,
          Runtime.setConfigProvider(
            ConfigProvider.fromMap(
              Map(
                "ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON" -> endpointsJson,
                "ZYBLW_SMOKE_PROVIDER_ID"             -> "missing",
                "RELAY_API_KEY"                       -> "must-not-leak"
              )
            )
          )
        )
        .exit
        .map(exit => assertTrue(exit.isFailure, !exit.toString.contains("must-not-leak")))
    }
  )
