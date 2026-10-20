package com.zyblw.agent.core

import zio.json.*
import zio.test.*

object BillingTariffSpec extends ZIOSpecDefault:
  private def rates(in: String, out: String) = List(
    BillingRate("input", in, "1000000"),
    BillingRate("output", out, "1000000"),
    BillingRate("cache_read", "0.2", "1000000"),
    BillingRate("cache_write", "2.5", "1000000")
  )
  private val tariff = BillingTariff(
    List(
      BillingRule(upToInputTokens = Some(200000), rates = rates("2", "12")),
      BillingRule(aboveInputTokens = 200000, rates = rates("4", "18"))
    )
  )
  def spec = suite("BillingTariff")(
    test("free partitions still require counts; absent data never becomes paid text or free audio") {
      val t = BillingTariff(
        List(BillingRule(rates = rates("2", "4") :+ BillingRate("input_audio_tokens", "0", "1000000")))
      )
      assertTrue(
        t.rate(TokenUsage(1000000, 0)) == Left(BillingError.MissingUsage("input_audio_tokens")),
        t.rate(
          TokenUsage(
            1000000,
            0,
            billing = Some(BillingUsage(meters = Map("input_audio_tokens" -> "1000000")))
          )
        ).map(_.total)
          .exists(BigDecimal(_) == 0),
        t.rate(TokenUsage(0, 0)).isRight
      )
    },
    test("a separate reasoning price replaces output; unpriced reasoning remains part of ordinary output") {
      val t = BillingTariff(
        List(BillingRule(rates = rates("1", "10") :+ BillingRate("reasoning_tokens", "20", "1000000")))
      )
      val usage = TokenUsage(
        100,
        10000,
        reasoningOutputTokens = 9000,
        billing = Some(BillingUsage(meters = Map("reasoning_tokens" -> "9000")))
      )
      assertTrue(
        t.rate(usage).map(r => BigDecimal(r.total)) == Right(BigDecimal("0.1901")),
        t.rate(usage.copy(billing = None)) == Left(BillingError.MissingUsage("reasoning_tokens")),
        ModelPrice(1, 10).rated(usage) == Right(BigDecimal("0.1001")),
        tariff.rate(usage).isRight,
        BillingTariff.decimal("0.0000000416666666666667").contains(BigDecimal("0.0000000416666666666667")),
        BillingTariff.decimal("1e-10000000").isEmpty,
        t.rate(usage.copy(billing = Some(BillingUsage(issues = List("模态用量不完整"))))).isLeft
      )
    },
    test("whole-request bands include the boundary; output uses the input context tier") {
      assertTrue(
        tariff.rate(TokenUsage(200000, 1000)).map(r => BigDecimal(r.total)) == Right(BigDecimal("0.412")),
        tariff.rate(TokenUsage(200001, 1000)).map(r => BigDecimal(r.total)) == Right(BigDecimal("0.818004")),
        tariff.converted(BigDecimal(7)).rate(TokenUsage(200001, 1000)).map(r => BigDecimal(r.total)) == Right(
          BigDecimal("5.726028")
        )
      )
    },
    test("cache and reasoning subsets are charged once, request fees are additive") {
      val price = ModelPrice(
        2,
        4,
        tariff = Some(
          BillingTariff(
            List(
              BillingRule(
                rates = rates("2", "4") :+ BillingRate("request", "0.01", "1")
              )
            )
          )
        )
      )
      assertTrue(
        price.rated(TokenUsage(1000000, 1000000, 200000, 100000, 100000)) == Right(BigDecimal("5.70"))
      )
    },
    test("missing meters remain pending, explicit zero and unsupported usage are different") {
      val t = BillingTariff(List(BillingRule(rates = rates("2", "4") :+ BillingRate("search", "10", "1000"))))
      assertTrue(
        t.rate(TokenUsage(10, 10)) == Left(BillingError.MissingUsage("search")),
        t.rate(TokenUsage(10, 10, billing = Some(BillingUsage(meters = Map("search" -> "0"))))).isRight,
        tariff.rate(TokenUsage(10, 10, billing = Some(BillingUsage(meters = Map("image" -> "1"))))) == Left(
          BillingError.UnpricedMeter("image")
        )
      )
    },
    test("conditional rules require matching facts; overlap, malformed prices and units fail closed") {
      val rule = BillingRule(dimensions = Map("mode" -> "batch"), rates = rates("1", "2"))
      assertTrue(
        BillingTariff(List(rule)).rate(TokenUsage(1, 1)) == Left(BillingError.NoMatchingRule),
        BillingTariff(List(rule))
          .rate(TokenUsage(1, 1, billing = Some(BillingUsage(Map("mode" -> "batch")))))
          .isRight,
        BillingTariff(tariff.rules :+ tariff.rules.head).validate.isLeft,
        BillingTariff(List(rule.copy(rates = rates("-1", "2")))).validate.isLeft,
        BillingTariff(
          List(rule.copy(rates = rates("1", "2") :+ BillingRate("request", "1", "0")))
        ).validate.isLeft
      )
    },
    test("audio and long cache writes replace their token subsets rather than adding them twice") {
      val t = BillingTariff(
        List(
          BillingRule(rates =
            rates("2", "4") ++ List(
              BillingRate("input_audio_tokens", "10", "1000000"),
              BillingRate("cache_write_1h", "4", "1000000")
            )
          )
        )
      )
      val usage = TokenUsage(
        1000000,
        0,
        cacheWriteInputTokens = 200000,
        billing =
          Some(BillingUsage(meters = Map("input_audio_tokens" -> "100000", "cache_write_1h" -> "100000")))
      )
      assertTrue(t.rate(usage).map(r => BigDecimal(r.total)) == Right(BigDecimal("3.05")))
    },
    test("preflight uses a conservative bound across tiers and cache categories") {
      val price = ModelPrice(2, 12, tariff = Some(tariff))
      assertTrue(
        price.reserve(TokenUsage(200000, 1000)) == Right(BigDecimal("0.818")),
        price.rated(TokenUsage(200000, 1000)) == Right(BigDecimal("0.412")),
        price
          .copy(tariff = Some(tariff.copy(requireZeroMeters = List("search"))))
          .reserve(TokenUsage(1, 1))
          .isLeft,
        tariff.copy(requireZeroMeters = List("search")).rate(TokenUsage(1, 1)).isLeft,
        tariff
          .copy(requireZeroMeters = List("search"))
          .rate(TokenUsage(1, 1, billing = Some(BillingUsage(meters = Map("search" -> "0")))))
          .isRight
      )
    },
    test("old JSON remains readable and new rules survive a snapshot round trip") {
      val old   = """{"inputPerMillionTokens":2,"outputPerMillionTokens":4,"currency":"CNY"}"""
      val price = ModelPrice(2, 4, currency = "CNY", tariff = Some(tariff))
      assertTrue(
        old.fromJson[ModelPrice].exists(_.rated(TokenUsage(1000000, 0)) == Right(BigDecimal(2))),
        """{"inputTokens":1,"outputTokens":2}""".fromJson[TokenUsage].exists(_.billing.isEmpty),
        price.toJson.fromJson[ModelPrice] == Right(price)
      )
    }
  )
