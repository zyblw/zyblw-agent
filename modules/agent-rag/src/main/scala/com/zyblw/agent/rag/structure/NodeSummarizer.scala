package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

enum SummaryMode derives zio.json.JsonCodec:
  case Off, Selective, Full

trait NodeSummaryStore:
  def get(cacheKey: String): UIO[Option[String]]
  def put(cacheKey: String, summary: String): UIO[Unit]

object InMemoryNodeSummaryStore:
  val make: UIO[NodeSummaryStore]                     = bounded(10000)
  def bounded(maxEntries: Int): UIO[NodeSummaryStore] =
    require(maxEntries > 0 && maxEntries <= 100000, "summary cache size invalid")
    Ref.make(Map.empty[String, String]).map { state =>
      new NodeSummaryStore:
        def get(cacheKey: String): UIO[Option[String]]        = state.get.map(_.get(cacheKey))
        def put(cacheKey: String, summary: String): UIO[Unit] =
          state.update { entries =>
            val bounded =
              if entries.size >= maxEntries && !entries.contains(cacheKey) then entries - entries.keys.min
              else entries
            bounded.updated(cacheKey, summary)
          }
    }

/** 章节/节点导航摘要器。
  *
  * 仅用于结构树自顶向下的推理导航，明确禁止将生成的摘要作为 Citation 引用直接呈现给用户。
  */
trait NodeSummarizer:
  def summarize(
      section: DocumentSection,
      blocks: Chunk[DocumentBlock],
      isLeaf: Boolean,
      spec: NodeSummarySpec
  ): IO[RetrievalError, Option[String]]

object NodeSummarizer:
  /** 规则摘要里正文开头的引导词；`StructureOutline` 据此只取正文部分做导航提示。 */
  val DeterministicLeadMarker: String = "主要涵盖："

  def cached(underlying: NodeSummarizer, cache: NodeSummaryStore): NodeSummarizer =
    new NodeSummarizer:
      def summarize(
          section: DocumentSection,
          blocks: Chunk[DocumentBlock],
          isLeaf: Boolean,
          spec: NodeSummarySpec
      ): IO[RetrievalError, Option[String]] =
        val key = KnowledgeDigest.sha256(
          spec.toJson + section.toJson + isLeaf.toString + blocks.map(b => (b.id, b.text)).toJson
        )
        cache.get(key).flatMap {
          case Some(summary) => ZIO.succeed(Some(summary))
          case None          =>
            underlying.summarize(section, blocks, isLeaf, spec).flatMap {
              case Some(summary) => cache.put(key, summary).as(Some(summary))
              case None          => ZIO.succeed(None)
            }
        }

  /** 确定性规则摘要器（作为离线兜底或轻量本地实现）。
    *
    * Selective 模式下：短叶子节点无需生成额外摘要；非叶子或长篇章节提取代表性前言作为导航摘要。
    */
  def deterministic(
      mode: SummaryMode = SummaryMode.Selective,
      cache: Option[NodeSummaryStore] = None
  ): NodeSummarizer =
    new NodeSummarizer:
      def summarize(
          section: DocumentSection,
          blocks: Chunk[DocumentBlock],
          isLeaf: Boolean,
          spec: NodeSummarySpec
      ): IO[RetrievalError, Option[String]] =
        if mode == SummaryMode.Off then ZIO.succeed(None)
        else if mode == SummaryMode.Selective && isLeaf && blocks.map(_.text.length).sum < 400 then
          ZIO.succeed(None)
        else
          val textContent = blocks.map(_.text).mkString(" ")
          val contentHash = KnowledgeDigest.sha256(textContent)
          val cacheKey    = KnowledgeDigest.sha256(
            spec.toString + "\u0000" + section.id + "\u0000" + section.title + "\u0000" + mode.toString + "\u0000" + contentHash
          )
          cache match
            case Some(store) =>
              store.get(cacheKey).flatMap {
                case Some(cached) => ZIO.succeed(Some(cached))
                case None         =>
                  val excerpt   = blocks.headOption.map(_.text.take(60)).getOrElse("")
                  val generated = s"${section.title}：包含 ${blocks.length} 处论述。$DeterministicLeadMarker$excerpt"
                  store.put(cacheKey, generated).as(Some(generated))
              }
            case None =>
              val excerpt   = blocks.headOption.map(_.text.take(60)).getOrElse("")
              val generated = s"${section.title}：包含 ${blocks.length} 处论述。$DeterministicLeadMarker$excerpt"
              ZIO.succeed(Some(generated))
