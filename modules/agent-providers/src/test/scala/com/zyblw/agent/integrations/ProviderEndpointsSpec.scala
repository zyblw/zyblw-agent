package com.zyblw.agent.integrations

import zio.*
import zio.json.*
import zio.test.*
import com.zyblw.agent.integrations.openai.OpenAICompatibility

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
    test("严格解析 URL，拒绝 user-info、query、fragment 和伪 loopback 主机") {
      val invalid = Chunk(
        "http://127.0.0.1.evil.example/v1",
        "https://user:secret@gateway.example/v1",
        "https://gateway.example/v1?api_key=secret",
        "https://gateway.example/v1#fragment",
        " https://gateway.example/v1",
        "https:///v1"
      )
      assertTrue(
        invalid.forall(value => scala.util.Try(valid.copy(baseUrl = value)).isFailure),
        scala.util.Try(valid.copy(baseUrl = "https://gateway.internal.example:8443/openai/v1")).isSuccess,
        scala.util.Try(valid.copy(baseUrl = "http://127.0.0.1:8080/v1")).isSuccess
      )
    },
    test("JSON 编解码保留协议和模型能力") {
      val rich = valid.copy(
        models = Chunk(
          ProviderEndpointModel(
            "deepseek-v4",
            ProviderEndpointCapabilities(
              thinking = true,
              parallelToolCalls = true,
              reasoningTokens = true,
              maxInputTokens = Some(128000L),
              maxOutputTokens = Some(8192L)
            )
          )
        ),
        compatibilityProfile = Some("deepseek")
      )
      val config = ProviderEndpointsConfig("relay", Chunk(rich))
      val round  = config.toJson.fromJson[ProviderEndpointsConfig]
      assertTrue(
        round.contains(config),
        rich.protocol == "relay",
        rich.models.head.capabilities.parallelToolCalls,
        rich.models.head.capabilities.maxInputTokens.contains(128000L)
      )
    },
    test("文档中的业务别名端点可以直接解码") {
      val json =
        """{"defaultProvider":"china-reasoning","endpoints":[{"providerId":"china-reasoning","baseUrl":"https://gateway.example/v1","apiKeyEnv":"RELAY_API_KEY","defaultModel":"deepseek-v4","protocol":"relay","compatibilityProfile":"deepseek","models":[{"name":"deepseek-v4","capabilities":{"toolCalls":true,"streaming":true,"thinking":true,"reasoningTokens":true,"maxInputTokens":128000,"maxOutputTokens":8192,"reasoningEfforts":["None","Low","High","Max"]}}]}]}"""
      val decoded = json.fromJson[ProviderEndpointsConfig]
      assertTrue(
        decoded.exists(_.defaultProvider == "china-reasoning"),
        decoded.exists(_.endpoints.head.compatibilityProfile.contains("deepseek")),
        decoded.exists(
          _.endpoints.head.models.head.capabilities.reasoningEfforts.contains(
            com.zyblw.agent.core.ReasoningEffort.High
          )
        )
      )
    },
    test("显式兼容档案让业务别名复用厂商 wire 方言且保持路由 id") {
      val declaration = valid.copy(
        providerId = "cn-reasoning",
        compatibilityProfile = Some("deepseek")
      )
      val compatibility = ProviderEndpoints.compatibilityFor(declaration)
      assertTrue(
        compatibility.descriptor.id == "cn-reasoning",
        compatibility.reasoningWireMode ==
          com.zyblw.agent.integrations.openai.ReasoningWireMode.ThinkingObjectEffort,
        compatibility.preserveReasoningContent
      )
    },
    test("模型清单只覆盖可配置子集并继承 Provider 协议能力") {
      val qwen = ProviderEndpointCapabilities(vision = true)
        .applyTo(OpenAICompatibility.qwen.descriptor.capabilities)
      assertTrue(
        qwen.vision,
        qwen.specificToolChoice,
        qwen.toolCalls,
        !qwen.strictToolSchema,
        !qwen.developerRole
      )
    },
    test("拒绝重复模型、缺失默认模型和未知兼容档案") {
      val duplicate = scala.util.Try(
        valid.copy(models = Chunk.fill(2)(ProviderEndpointModel("deepseek-v4")))
      )
      val missingDefault = scala.util.Try(
        valid.copy(models = Chunk(ProviderEndpointModel("another-model")))
      )
      val unknown = scala.util.Try(valid.copy(compatibilityProfile = Some("unknown")))
      assertTrue(duplicate.isFailure, missingDefault.isFailure, unknown.isFailure)
    },
    test("可选加载缺失或空白时返回 None，非空非法语义收敛为 typed 配置错误") {
      val invalid =
        """{"defaultProvider":"relay","endpoints":[{"providerId":"relay","baseUrl":"http://127.0.0.1.evil/v1","apiKeyEnv":"RELAY_API_KEY","defaultModel":"model"}]}"""
      for
        missing <- ProviderEndpointsConfig.fromEnvironmentOption
          .provide(Runtime.setConfigProvider(ConfigProvider.fromMap(Map.empty)))
        blank <- ProviderEndpointsConfig.fromEnvironmentOption
          .provide(
            Runtime.setConfigProvider(
              ConfigProvider.fromMap(Map(ProviderEndpointsConfig.EnvironmentVariable -> "  "))
            )
          )
        rejected <- ProviderEndpointsConfig.fromEnvironmentOption
          .provide(
            Runtime.setConfigProvider(
              ConfigProvider.fromMap(Map(ProviderEndpointsConfig.EnvironmentVariable -> invalid))
            )
          )
          .either
      yield assertTrue(
        missing.isEmpty,
        blank.isEmpty,
        rejected.left.exists {
          case com.zyblw.agent.core.AgentError.InvalidConfiguration(message) =>
            message == "Provider 端点 JSON 结构或约束无效"
          case _ => false
        }
      )
    }
  )
