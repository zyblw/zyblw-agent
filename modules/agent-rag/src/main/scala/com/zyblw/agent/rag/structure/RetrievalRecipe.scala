package com.zyblw.agent.rag

import zio.json.*

/** 检索高阶执行策略。
  *
  * `Classic`: 传统稠密 + 稀疏/词法混合检索，适用于 FAQ、单篇文章和短知识库。 `Structural`: 经典检索 + 章节结构值搜索（Value Search）+
  * 章节内局部原文检索，适用于长篇书籍、目录型文档。 `Reasoned`: 结构检索 + 有界树推理导航（Reasoning Tree Search），适用于复杂多章节综合分析。 `Adaptive`:
  * 根据查询特征与文档结构质量自动适配策略。
  */
enum RetrievalStrategy derives JsonCodec:
  case Classic, Structural, Reasoned, Adaptive

/** 面向具体业务场景开箱即用的检索配方（Recipe）。
  *
  * 避免业务层组合数十个底层参数（候选数、分支、重排、扩展模式等），直接提供场景化推荐工作点。
  */
enum RetrievalRecipe derives JsonCodec:
  /** 纯文本/FAQ/短知识库的标准混合检索：Dense + Lexical + Rerank。 */
  case Classic

  /** 快速书籍章节检索：Classic + 节点值搜索（紧凑预算，低延迟）。 */
  case BookFast

  /** 中医书籍问答默认推荐：Classic + 节点值搜索 + 章节边界结构扩展 + 精细重排 + 严格证据门槛。 */
  case BookGrounded

  /** 深度书籍综合分析：更大候选池、跨章节覆盖扩展与深度重排。 */
  case BookDeep

  /** 极低延迟要求场景：小候选池、跳过长链扩展。 */
  case LowLatency

object RetrievalRecipe:
  def parse(value: String): Either[String, RetrievalRecipe] =
    values.find(_.toString.equalsIgnoreCase(value.trim)).toRight("unsupported retrieval recipe")
  def parseStrategy(value: String): Either[String, RetrievalStrategy] =
    RetrievalStrategy.values
      .find(_.toString.equalsIgnoreCase(value.trim))
      .toRight("unsupported retrieval strategy")

  /** Deterministic routing; caller modes remain authoritative, model navigation is opt-in. */
  def adapt(
      query: String,
      mode: RetrievalMode,
      hasStructure: Boolean,
      hasModelNavigator: Boolean
  ): RetrievalStrategy =
    if !hasStructure || mode == RetrievalMode.Phrase then RetrievalStrategy.Classic
    else if hasModelNavigator && Seq("对比", "比较", "区别", "综合", "跨章", "compare").exists(
        query.toLowerCase.contains
      )
    then RetrievalStrategy.Reasoned
    else RetrievalStrategy.Structural

  /** 将业务配方解析为对应的底层策略与工作点。 */
  def resolve(
      recipe: RetrievalRecipe,
      baseBudgets: CandidateBudgets = CandidateBudgets(),
      baseExpansion: RetrievalExpansionConfig = RetrievalExpansionConfig()
  ): (RetrievalStrategy, CandidateBudgets, RetrievalExpansionConfig) =
    recipe match
      case Classic =>
        (
          RetrievalStrategy.Classic,
          baseBudgets,
          baseExpansion
        )
      case BookFast =>
        (
          RetrievalStrategy.Structural,
          baseBudgets.copy(
            perBranch = math.min(baseBudgets.perBranch, 40),
            fusion = math.min(baseBudgets.fusion, 40),
            rerankSeeds = math.min(baseBudgets.rerankSeeds, 8)
          ),
          baseExpansion.copy(maxAdditionalChunks = math.min(baseExpansion.maxAdditionalChunks, 6))
        )
      case BookGrounded =>
        (
          RetrievalStrategy.Structural,
          baseBudgets.copy(
            perBranch = math.max(baseBudgets.perBranch, 60),
            fusion = math.max(baseBudgets.fusion, 60),
            rerankSeeds = math.max(baseBudgets.rerankSeeds, 12),
            expansion = math.max(baseBudgets.expansion, 12)
          ),
          baseExpansion.copy(
            maxAdditionalChunks = math.max(baseExpansion.maxAdditionalChunks, 10),
            neighborRadius = math.max(baseExpansion.neighborRadius, 1)
          )
        )
      case BookDeep =>
        (
          RetrievalStrategy.Reasoned,
          baseBudgets.copy(
            perBranch = math.max(baseBudgets.perBranch, 100),
            fusion = math.max(baseBudgets.fusion, 100),
            rerankSeeds = math.max(baseBudgets.rerankSeeds, 20),
            expansion = math.max(baseBudgets.expansion, 20)
          ),
          baseExpansion.copy(
            maxAdditionalChunks = math.max(baseExpansion.maxAdditionalChunks, 16),
            neighborRadius = math.max(baseExpansion.neighborRadius, 1)
          )
        )
      case LowLatency =>
        (
          RetrievalStrategy.Classic,
          baseBudgets.copy(
            perBranch = math.min(baseBudgets.perBranch, 20),
            fusion = math.min(baseBudgets.fusion, 20),
            rerankSeeds = math.min(baseBudgets.rerankSeeds, 5)
          ),
          baseExpansion.copy(maxAdditionalChunks = 0)
        )
