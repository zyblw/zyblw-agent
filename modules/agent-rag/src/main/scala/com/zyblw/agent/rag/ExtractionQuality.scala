package com.zyblw.agent.rag

import scala.util.matching.Regex

/** 提取质量门禁。用于判断廉价文本层是否足以索引，而不是把 CID 垃圾或空白扫描件当成成功正文。
  *
  * @param minScriptCodePoints
  *   字母/表意文字的 Unicode code point 下限
  * @param minScriptDensity
  *   非空白中脚本字符占比下限
  * @param maxCidRatio
  *   非空白中 PDF CID 伪影占比上限
  * @param maxReplacementRatio
  *   非空白中 U+FFFD 占比上限
  */
final case class ExtractionQualityPolicy(
    minScriptCodePoints: Int = 48,
    minScriptDensity: Double = 0.35,
    maxCidRatio: Double = 0.08,
    maxReplacementRatio: Double = 0.05
):
  require(minScriptCodePoints > 0, "minScriptCodePoints 必须为正数")
  require(minScriptDensity >= 0.0 && minScriptDensity <= 1.0, "minScriptDensity 必须位于 0..1")
  require(maxCidRatio >= 0.0 && maxCidRatio <= 1.0, "maxCidRatio 必须位于 0..1")
  require(maxReplacementRatio >= 0.0 && maxReplacementRatio <= 1.0, "maxReplacementRatio 必须位于 0..1")

/** 一次提取结果的可观测质量摘要。不保存正文。 */
final case class ExtractionQuality(
    totalCodePoints: Int,
    nonWhitespaceCodePoints: Int,
    scriptCodePoints: Int,
    cidArtifacts: Int,
    replacementCodePoints: Int
):
  def scriptDensity: Double =
    if nonWhitespaceCodePoints == 0 then 0.0
    else scriptCodePoints.toDouble / nonWhitespaceCodePoints.toDouble

  def cidRatio: Double =
    if nonWhitespaceCodePoints == 0 then 0.0
    else cidArtifacts.toDouble / nonWhitespaceCodePoints.toDouble

  def replacementRatio: Double =
    if nonWhitespaceCodePoints == 0 then 0.0
    else replacementCodePoints.toDouble / nonWhitespaceCodePoints.toDouble

  def sufficient(policy: ExtractionQualityPolicy): Boolean =
    scriptCodePoints >= policy.minScriptCodePoints &&
      scriptDensity >= policy.minScriptDensity &&
      cidRatio <= policy.maxCidRatio &&
      replacementRatio <= policy.maxReplacementRatio

  /** 写入 metadata 的低敏摘要，不含正文。 */
  def compact: String =
    f"script=$scriptCodePoints,cid=$cidArtifacts,repl=$replacementCodePoints,density=$scriptDensity%.2f"

object ExtractionQuality:
  private val CidArtifact: Regex = """\((?:cid|CID):\d+\)""".r

  def assess(text: String): ExtractionQuality =
    val cidArtifacts = CidArtifact.findAllIn(text).map(value => value.codePointCount(0, value.length)).sum
    var total        = 0
    var nonWs        = 0
    var script       = 0
    var replacement  = 0
    var index        = 0
    while index < text.length do
      val cp = text.codePointAt(index)
      total += 1
      if !Character.isWhitespace(cp) then nonWs += 1
      if Character.isLetter(cp) then script += 1
      if cp == 0xfffd then replacement += 1
      index += Character.charCount(cp)
    ExtractionQuality(total, nonWs, script, cidArtifacts, replacement)
