package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

enum IngestCheckpoint:
  case Resolved, Extracted, Normalized, Chunked, Enriched, Embedded, Staged, Validated, Ready, Quarantined,
    Cancelled, Failed

final case class SourceArtifactRef(
    uri: String,
    mediaType: String,
    contentSha256: String,
    extractedArtifactUri: Option[String] = None,
    extractedSha256: Option[String] = None
):
  require(uri.trim.nonEmpty, "source URI 不能为空")
  require(contentSha256.matches("[0-9a-f]{64}"), "contentSha256 必须是小写 SHA-256")

enum SourceScheme:
  case Https, File, Book, Knowledge, Memory, Doc, AdminUpload, Upload, ZyblwBook, ZyblwContent, ZyblwAsset,
    ZyblwInternal

object SourceScheme:
  private val ByName: Map[String, SourceScheme] = Map(
    "https"          -> SourceScheme.Https,
    "file"           -> SourceScheme.File,
    "book"           -> SourceScheme.Book,
    "knowledge"      -> SourceScheme.Knowledge,
    "memory"         -> SourceScheme.Memory,
    "doc"            -> SourceScheme.Doc,
    "admin-upload"   -> SourceScheme.AdminUpload,
    "upload"         -> SourceScheme.Upload,
    "zyblw-book"     -> SourceScheme.ZyblwBook,
    "zyblw-content"  -> SourceScheme.ZyblwContent,
    "zyblw-asset"    -> SourceScheme.ZyblwAsset,
    "zyblw-internal" -> SourceScheme.ZyblwInternal
  )

  def parse(name: String): Option[SourceScheme] = ByName.get(name.toLowerCase)

  def names: Set[String] = ByName.keySet

object ApprovedSourceResolver:
  /** 第一方与对象存储 scheme；明确拒绝 `http` 以防 SSRF。 */
  def validate(uri: String): IO[RetrievalError, Unit] =
    if uri.contains("..") || uri.contains('\u0000') then
      ZIO.fail(AgentError.RetrievalFailed("source URI 含有路径穿越或非法字符"))
    else if !uri.contains(":") then ZIO.unit
    else
      val scheme = uri.split(":", 2).headOption.map(_.toLowerCase).getOrElse("")
      if scheme == "http" || SourceScheme.parse(scheme).isEmpty then
        ZIO.fail(AgentError.RetrievalFailed(s"未批准的 source scheme: $scheme"))
      else ZIO.unit

enum EnricherDescriptor:
  case Identity
  case Host(id: String, version: String)

final case class EnrichmentResult(
    metadata: Map[String, String],
    aliases: Map[String, String] = Map.empty,
    provenance: Map[String, String] = Map.empty
)

trait DocumentEnricher:
  def descriptor: EnricherDescriptor
  def enrich(document: SourceDocument, chunks: Chunk[DocumentChunk]): IO[RetrievalError, EnrichmentResult]

object DocumentEnricher:
  val identity: DocumentEnricher = new DocumentEnricher:
    def descriptor: EnricherDescriptor = EnricherDescriptor.Identity
    def enrich(document: SourceDocument, chunks: Chunk[DocumentChunk]): UIO[EnrichmentResult] =
      val _ = chunks
      ZIO.succeed(EnrichmentResult(document.metadata))

final case class BaselineRagProfile(
    id: String = "hybrid-rerank-v1",
    denseDimension: Int = 1024,
    fusion: String = "weighted-rrf",
    rerankEnabled: Boolean = true,
    sparseEnabled: Boolean = false,
    assistEnabled: Boolean = false,
    budgets: CandidateBudgets = CandidateBudgets()
)
