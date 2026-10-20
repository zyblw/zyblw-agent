package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import zio.json.*
import zio.json.ast.Json

/** Only explicit provider facts become billing dimensions; no estimates from prompt text. */
private[integrations] object ProviderBilling:
  /** Stream headers may report the actual tier before the terminal usage frame. */
  def inheritConditions(usage: TokenUsage, earlier: Option[BillingUsage]): TokenUsage = earlier match
    case None           => usage
    case Some(previous) =>
      val current = usage.billing.getOrElse(BillingUsage())
      usage.copy(billing =
        Some(
          current.copy(
            dimensions = previous.dimensions ++ current.dimensions,
            issues = (previous.issues ++ current.issues).distinct
          )
        )
      )

  private def at(json: Json, path: String*): Option[Json] =
    path.foldLeft(Option(json))((current, key) =>
      current.flatMap {
        case Json.Obj(fields) => fields.find(_._1 == key).map(_._2)
        case _                => None
      }
    )
  private def text(value: Json): Option[String] = value match
    case Json.Str(s) if s.length <= 80 => Some(s)
    case _                             => None
  def options(usage: TokenUsage, options: Map[String, Json], request: ChatRequest): TokenUsage =
    val dimensions = List(
      options
        .get("service_tier")
        .flatMap(text)
        .map(v => "service_tier" -> (if Set("default", "standard_only").contains(v) then "standard" else v)),
      options.get("inference_geo").flatMap(text).map(v => "region" -> v)
    ).flatten.toMap
    val plainText = request.messages.forall(_.content.forall {
      case ContentPart.Text(_) => true
      case _                   => false
    })
    // An absent feature is only a zero when this request cannot invoke it. Provider reports always win.
    val absent = Option
      .when(plainText && options.keySet.subsetOf(Set("service_tier", "inference_geo")))(
        Map(
          "image"               -> "0",
          "input_audio_tokens"  -> "0",
          "output_audio_tokens" -> "0",
          "audio_seconds"       -> "0",
          "video_seconds"       -> "0"
        )
      )
      .getOrElse(Map.empty)
    val current     = usage.billing.getOrElse(BillingUsage())
    val withAbsence =
      if absent.isEmpty then usage
      else usage.copy(billing = Some(current.copy(meters = absent ++ current.meters)))
    merge(withAbsence, dimensions, Map.empty, responseWins = false)

  /** Native adapters encode custom function tools, never implicit gateway search plugins. */
  def nativeOptions(usage: TokenUsage, configured: Map[String, Json], request: ChatRequest): TokenUsage =
    val normalized = options(usage, configured, request)
    if configured.keySet.subsetOf(Set("service_tier", "inference_geo")) then
      val facts = normalized.billing.getOrElse(BillingUsage())
      normalized.copy(billing =
        Some(facts.copy(meters = Map("search" -> "0", "maps" -> "0") ++ facts.meters))
      )
    else normalized

  def reported(usage: TokenUsage, response: Json): TokenUsage =
    val reportedTier = at(response, "service_tier").orElse(at(response, "usage", "service_tier"))
    val dimensions   = reportedTier
      .flatMap(text)
      .map(v => "service_tier" -> (if v == "default" then "standard" else v))
      .toMap
    val meters = List(
      "reasoning_tokens"       -> List("usage", "completion_tokens_details", "reasoning_tokens"),
      "input_audio_tokens"     -> List("usage", "prompt_tokens_details", "audio_tokens"),
      "output_audio_tokens"    -> List("usage", "completion_tokens_details", "audio_tokens"),
      "cache_write_1h"         -> List("usage", "cache_creation", "ephemeral_1h_input_tokens"),
      "search"                 -> List("usage", "server_tool_use", "web_search_requests"),
      "responses_input_audio"  -> List("usage", "input_tokens_details", "audio_tokens"),
      "responses_output_audio" -> List("usage", "output_tokens_details", "audio_tokens"),
      "responses_reasoning"    -> List("usage", "output_tokens_details", "reasoning_tokens")
    ).flatMap { (rawMeter, path) =>
      val meter = Map(
        "responses_input_audio"  -> "input_audio_tokens",
        "responses_output_audio" -> "output_audio_tokens",
        "responses_reasoning"    -> "reasoning_tokens"
      ).getOrElse(rawMeter, rawMeter)
      at(response, path*).filter(_ != Json.Null).map {
        case Json.Num(n) => meter -> n.toPlainString
        case other       => meter -> other.toJson
      }
    }.toMap
    val normalized = merge(usage, dimensions, meters, responseWins = true)
    val issues     = List(
      Option.when(reportedTier.exists(v => v != Json.Null && text(v).isEmpty))("供应商 service_tier 格式无效"),
      Option.when(
        (usage.cacheReadInputTokens > 0 || usage.cacheWriteInputTokens > 0) &&
          meters.get("input_audio_tokens").flatMap(BillingTariff.decimal).exists(_ > 0)
      )("供应商未报告音频与缓存的交集，无法确定分摊用量")
    ).flatten
    if issues.nonEmpty then
      val facts = normalized.billing.getOrElse(BillingUsage())
      normalized.copy(billing = Some(facts.copy(issues = facts.issues ++ issues)))
    else normalized

  private def merge(
      usage: TokenUsage,
      dimensions: Map[String, String],
      meters: Map[String, String],
      responseWins: Boolean
  ): TokenUsage =
    if dimensions.isEmpty && meters.isEmpty then usage
    else
      val current = usage.billing.getOrElse(BillingUsage())
      usage.copy(billing =
        Some(
          current.copy(
            dimensions =
              if responseWins then current.dimensions ++ dimensions else dimensions ++ current.dimensions,
            meters = current.meters ++ meters
          )
        )
      )
