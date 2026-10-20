package com.zyblw.agent.core

import zio.json.*
import zio.schema.*

/** Per-call provider facts. Missing meters are unknown; an explicit zero is a fact. */
final case class BillingUsage(
    dimensions: Map[String, String] = Map.empty,
    meters: Map[String, String] = Map.empty,
    issues: List[String] = Nil
) derives JsonCodec

final case class BillingRate(meter: String, amount: String, per: String) derives JsonCodec, Schema

/** Whole-request context bands: lower bound exclusive, upper bound inclusive. */
final case class BillingRule(
    aboveInputTokens: Long = -1L,
    upToInputTokens: Option[Long] = None,
    dimensions: Map[String, String] = Map.empty,
    rates: List[BillingRate] = Nil
) derives JsonCodec,
      Schema

final case class BillingLine(meter: String, quantity: String, amount: String, per: String, cost: String)
    derives JsonCodec,
      Schema
final case class BillingResult(rule: Int, lines: List[BillingLine], total: String) derives JsonCodec, Schema

enum BillingError derives JsonCodec:
  case InvalidTariff(reason: String)
  case MissingUsage(meter: String)
  case UnpricedMeter(meter: String)
  case NoMatchingRule
  case AmbiguousRules

  def message: String = this match
    case InvalidTariff(reason) => reason
    case MissingUsage(meter)   => s"供应商未报告计量项 $meter"
    case UnpricedMeter(meter)  => s"没有计量项 $meter 的价格"
    case NoMatchingRule        => "没有匹配本次上下文、服务等级、地域或调用模式的规则"
    case AmbiguousRules        => "多条规则同时匹配，无法确定价格"

/** Pure, bounded and versioned pricing; never reads a clock, network or account balance. */
final case class BillingTariff(
    rules: List[BillingRule],
    version: Int = 1,
    requireZeroMeters: List[String] = Nil
) derives JsonCodec,
      Schema:
  import BillingTariff.*

  def validate: Either[BillingError, Unit] =
    val valid = version == 1 && requireZeroMeters.size <= extraMeters.size &&
      requireZeroMeters.distinct.size == requireZeroMeters.size && requireZeroMeters.forall(
        extraMeters.contains
      ) &&
      rules.nonEmpty && rules.size <= 64 && rules.forall { r =>
        r.aboveInputTokens >= -1 && r.upToInputTokens.forall(n => n >= 0 && n > r.aboveInputTokens) &&
        r.dimensions.size <= 8 && r.dimensions.forall((k, v) =>
          dimensions.contains(k) && v.nonEmpty && v.length <= 80 && !v.exists(_.isControl)
        ) && r.rates.nonEmpty && r.rates.size <= meters.size &&
        r.rates.map(_.meter).distinct.size == r.rates.size && r.rates.forall { rate =>
          meters.contains(rate.meter) && decimal(rate.amount).nonEmpty && decimal(rate.per).exists(_ > 0)
        } && Set("input", "output").subsetOf(r.rates.map(_.meter).toSet)
      }
    val overlap = rules.zipWithIndex.exists { (a, i) =>
      rules.drop(i + 1).exists { b =>
        val intersect = a.aboveInputTokens < b.upToInputTokens.getOrElse(Long.MaxValue) &&
          b.aboveInputTokens < a.upToInputTokens.getOrElse(Long.MaxValue)
        intersect && a.dimensions.forall((k, v) => b.dimensions.get(k).forall(_ == v))
      }
    }
    Either.cond(valid && !overlap, (), BillingError.InvalidTariff("规则非法、计量项重复或适用区间重叠"))

  def converted(factor: BigDecimal): BillingTariff =
    copy(rules =
      rules.map(r =>
        r.copy(rates =
          r.rates.map(rate =>
            rate.copy(amount = BigDecimal(rate.amount).bigDecimal.multiply(factor.bigDecimal).toPlainString)
          )
        )
      )
    )

  def rate(usage: TokenUsage): Either[BillingError, BillingResult] =
    val facts   = usage.billing.getOrElse(BillingUsage())
    val context =
      Map("service_tier" -> "standard", "mode" -> "sync", "region" -> "global") ++ facts.dimensions
    for
      _ <- validate
      _ <- Either.cond(
        facts.issues.isEmpty,
        (),
        BillingError.InvalidTariff(facts.issues.take(8).mkString("；"))
      )
      _ <- Either.cond(
        facts.dimensions.size <= 8 && facts.meters.size <= extraMeters.size &&
          facts.dimensions.forall((k, v) =>
            dimensions.contains(k) && v.nonEmpty && v.length <= 80 && !v.exists(_.isControl)
          ),
        (),
        BillingError.InvalidTariff("调用计费条件无效")
      )
      supplied <- facts.meters.toList.foldLeft[Either[BillingError, Map[String, BigDecimal]]](
        Right(Map.empty)
      ) { case (acc, (key, value)) =>
        for
          values <- acc
          number <- decimal(value)
            .filter(n =>
              extraMeters.contains(key) &&
                (!Set(
                  "input_audio_tokens",
                  "output_audio_tokens",
                  "cache_write_1h",
                  "image",
                  "search",
                  "reasoning_tokens",
                  "maps",
                  "tool_use_tokens"
                )
                  .contains(key) || n.isWhole)
            )
            .toRight(BillingError.InvalidTariff(s"无效计量项: $key"))
        yield values.updated(key, number)
      }
      _ <- requireZeroMeters.foldLeft[Either[BillingError, Unit]](Right(())) { (acc, meter) =>
        acc
          .flatMap(_ => supplied.get(meter).toRight(BillingError.MissingUsage(meter)))
          .flatMap(n => Either.cond(n == 0, (), BillingError.UnpricedMeter(meter)))
      }
      audioIn   = supplied.getOrElse("input_audio_tokens", BigDecimal(0))
      audioOut  = supplied.getOrElse("output_audio_tokens", BigDecimal(0))
      longWrite = supplied.getOrElse("cache_write_1h", BigDecimal(0))
      _ <- Either.cond(
        audioIn <= usage.freshInputTokens && audioOut <= usage.outputTokens &&
          longWrite <= usage.cacheWriteInputTokens,
        (),
        BillingError.InvalidTariff("细分用量超过所属 token 总量")
      )
      quantities = Map(
        "input"       -> (BigDecimal(usage.freshInputTokens) - audioIn),
        "output"      -> (BigDecimal(usage.outputTokens) - audioOut),
        "cache_read"  -> BigDecimal(usage.cacheReadInputTokens),
        "cache_write" -> (BigDecimal(usage.cacheWriteInputTokens) - longWrite),
        "request"     -> BigDecimal(1)
      ) ++ Option.when(usage.freshInputTokens == 0)("input_audio_tokens" -> BigDecimal(0)).toMap ++
        Option.when(usage.outputTokens == 0)("output_audio_tokens" -> BigDecimal(0)).toMap ++
        Option.when(usage.cacheWriteInputTokens == 0)("cache_write_1h" -> BigDecimal(0)).toMap ++ supplied
      matches = rules.zipWithIndex.filter { (r, _) =>
        usage.inputTokens > r.aboveInputTokens && r.upToInputTokens.forall(usage.inputTokens <= _) &&
        r.dimensions.forall((k, v) => context.get(k).contains(v))
      }
      selected <- matches match
        case List(one) => Right(one)
        case Nil       => Left(BillingError.NoMatchingRule)
        case _         => Left(BillingError.AmbiguousRules)
      (rule, index) = selected
      partitions    = Map(
        "input_audio_tokens"  -> usage.freshInputTokens,
        "output_audio_tokens" -> usage.outputTokens,
        "cache_write_1h"      -> usage.cacheWriteInputTokens
      )
      _ <- partitions.toList.foldLeft[Either[BillingError, Unit]](Right(())) { case (acc, (meter, total)) =>
        acc.flatMap(_ =>
          Either.cond(
            total == 0 || !rule.rates.exists(_.meter == meter) || supplied.contains(meter),
            (),
            BillingError.MissingUsage(meter)
          )
        )
      }
      reasoning = supplied.get("reasoning_tokens")
      _ <- Either.cond(
        reasoning.forall(_ <= usage.outputTokens - audioOut),
        (),
        BillingError.InvalidTariff("推理与音频输出子集超过输出总量")
      )
      ratedQuantities = quantities.updated(
        "output",
        quantities("output") -
          (if rule.rates.exists(_.meter == "reasoning_tokens") then reasoning.getOrElse(BigDecimal(0))
           else BigDecimal(0))
      ) ++
        Option.when(usage.outputTokens == 0)("reasoning_tokens" -> BigDecimal(0)).toMap
      _ <- Either.cond(
        !rule.rates.exists(_.meter == "reasoning_tokens") || usage.outputTokens == 0 || reasoning.nonEmpty,
        (),
        BillingError.MissingUsage("reasoning_tokens")
      )
      unpriced = ratedQuantities.collectFirst {
        case (key, n)
            if n > 0 && key != "request" && key != "reasoning_tokens" &&
              !rule.rates.exists(_.meter == key) =>
          key
      }
      _     <- unpriced.toLeft(()).left.map(BillingError.UnpricedMeter(_))
      lines <- rule.rates.foldLeft[Either[BillingError, List[BillingLine]]](Right(Nil)) { (acc, rate) =>
        for
          previous <- acc
          quantity <- ratedQuantities
            .get(rate.meter)
            .orElse(Option.when(BigDecimal(rate.amount) == 0)(BigDecimal(0)))
            .toRight(BillingError.MissingUsage(rate.meter))
          cost = quantity * BigDecimal(rate.amount) / BigDecimal(rate.per)
        yield previous :+ BillingLine(
          rate.meter,
          quantity.bigDecimal.toPlainString,
          rate.amount,
          rate.per,
          cost.bigDecimal.toPlainString
        )
      }
    yield BillingResult(index, lines, lines.map(l => BigDecimal(l.cost)).sum.bigDecimal.toPlainString)

object BillingTariff:
  val extraMeters: Set[String] = Set(
    "input_audio_tokens",
    "output_audio_tokens",
    "cache_write_1h",
    "image",
    "audio_seconds",
    "video_seconds",
    "search",
    "cache_token_hours",
    "reasoning_tokens",
    "maps",
    "tool_use_tokens"
  )
  val meters: Set[String]     = Set("input", "output", "cache_read", "cache_write", "request") ++ extraMeters
  val dimensions: Set[String] = Set("service_tier", "mode", "region", "cache_ttl", "period")
  def decimal(value: String): Option[BigDecimal] =
    Option
      .when(value.length <= 96)(value)
      .flatMap(v => scala.util.Try(BigDecimal(v)).toOption)
      .filter(n =>
        n >= 0 && n <= BigDecimal("1000000000000") && n.bigDecimal.precision <= 60 && math.abs(
          n.scale.toLong
        ) <= 48
      )
