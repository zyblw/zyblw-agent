package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ProviderBillingSpec extends ZIOSpecDefault:
  def spec = suite("Provider billing facts")(
    test("malformed reported tier and overlapping audio/cache facts stay unpriceable") {
      val invalid =
        ProviderBilling.reported(TokenUsage(100, 10), """{"service_tier":42}""".fromJson[Json].toOption.get)
      val overlap = ProviderBilling.reported(
        TokenUsage(100, 10, cachedInputTokens = 10),
        """{"usage":{"prompt_tokens_details":{"audio_tokens":20}}}""".fromJson[Json].toOption.get
      )
      assertTrue(
        invalid.billing.exists(_.issues.nonEmpty),
        overlap.billing.exists(_.issues.nonEmpty),
        ModelPrice(1, 2).rated(invalid).isLeft,
        ModelPrice(1, 2).rated(overlap).isLeft
      )
    },
    test("Anthropic usage tier is authoritative and native search absence is distinct from gateways") {
      val json       = """{"usage":{"service_tier":"priority"}}""".fromJson[Json].toOption.get
      val raw        = ProviderBilling.reported(TokenUsage(1, 1), json)
      val configured = Map("service_tier" -> Json.Str("auto"))
      val request    = ChatRequest(zio.Chunk.empty)
      val native     = ProviderBilling.nativeOptions(raw, configured, request)
      val gateway    = ProviderBilling.options(raw, configured, request)
      assertTrue(
        native.billing.exists(_.dimensions.get("service_tier").contains("priority")),
        native.billing.exists(_.meters.get("search").contains("0")),
        gateway.billing.forall(!_.meters.contains("search"))
      )
    },
    test("actual service tier overrides requested auto; audio and cache TTL facts retain exact quantities") {
      val raw = """{"service_tier":"priority","usage":{"prompt_tokens_details":{"audio_tokens":12},
        "completion_tokens_details":{"audio_tokens":5},"cache_creation":{"ephemeral_1h_input_tokens":20}}}"""
      val reported = ProviderBilling.reported(
        TokenUsage(100, 10, cacheWriteInputTokens = 20),
        raw.fromJson[Json].toOption.get
      )
      val usage = ProviderBilling.options(
        reported,
        Map("service_tier" -> Json.Str("auto")),
        ChatRequest(zio.Chunk.empty)
      )
      assertTrue(
        usage.billing.exists(_.dimensions.get("service_tier").contains("priority")),
        usage.billing.exists(facts =>
          Map(
            "input_audio_tokens"  -> "12",
            "output_audio_tokens" -> "5",
            "cache_write_1h"      -> "20"
          ).forall((key, value) => facts.meters.get(key).contains(value))
        )
      )
    }
  )
