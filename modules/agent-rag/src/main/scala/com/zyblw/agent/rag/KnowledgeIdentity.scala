package com.zyblw.agent.rag

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import zio.*
import zio.json.*

/** 宿主可信提供的来源修订。
  *
  * `sourceId` 是租户内跨空间稳定的原件身份（例如一本书），`revisionId` 是该原件的业务修订号，撤回和蓝绿切换的语料比较都按它进行。 `sha256` 是原始文件字节摘要，例如 PDF
  * 本身，而不是 OCR 输出或拼接正文；缺失时使用解析输入的摘要。
  */
final case class SourceRevision(
    sourceId: String,
    revisionId: String,
    sha256: Option[String] = None,
    mediaType: Option[String] = None
):
  require(sourceId.trim.nonEmpty && sourceId.length <= 1000, "SourceRevision.sourceId 长度必须位于 1..1000")
  require(revisionId.trim.nonEmpty && revisionId.length <= 200, "SourceRevision.revisionId 长度必须位于 1..200")
  require(sha256.forall(_.matches("[0-9a-f]{64}")), "SourceRevision.sha256 必须是小写 SHA-256")
  require(mediaType.forall(m => m.trim.nonEmpty && m.length <= 200), "SourceRevision.mediaType 长度必须位于 1..200")

object SourceRevision:
  /** 保留一个已有构建的来源身份，用于换模型/切分器的重建：新 Profile 与旧 Profile 的逻辑语料因此可比较。 */
  def of(lineage: DocumentLineage): SourceRevision =
    SourceRevision(
      lineage.sourceId,
      lineage.sourceRevisionId,
      Some(lineage.sourceSha256),
      Some(lineage.sourceMediaType)
    )

/** 摄取时由可信控制面补充的谱系输入。
  *
  * @param source
  *   原件修订；缺失时退化为以解析输入为来源
  * @param artifactSha256
  *   Loader 实际消费的字节摘要，例如 PaddleOCR-VL 导出的 JSON；`DocumentIngestionService` 在读流时计算
  * @param artifactMediaType
  *   解析输入声明的媒体类型
  */
final case class IngestionProvenance(
    source: Option[SourceRevision] = None,
    artifactSha256: Option[String] = None,
    artifactMediaType: Option[String] = None
):
  require(artifactSha256.forall(_.matches("[0-9a-f]{64}")), "artifactSha256 必须是小写 SHA-256")

/** 一次索引构建的不可变来源链：原件修订 → 解析产物 → 结构 → 正文。
  *
  * 任一字段变化都意味着不同的摄取输入，因此它们全部参与幂等键与请求一致性比较。
  */
final case class DocumentLineage(
    sourceId: String,
    sourceRevisionId: String,
    sourceSha256: String,
    sourceMediaType: String,
    parserId: String,
    artifactSha256: String,
    structureSha256: String,
    textSha256: String
) derives JsonCodec:
  require(sourceId.trim.nonEmpty && sourceId.length <= 1000, "lineage sourceId 长度必须位于 1..1000")
  require(
    sourceRevisionId.trim.nonEmpty && sourceRevisionId.length <= 200,
    "lineage sourceRevisionId 长度必须位于 1..200"
  )
  require(sourceMediaType.trim.nonEmpty && sourceMediaType.length <= 200, "lineage sourceMediaType 无效")
  require(parserId.trim.nonEmpty && parserId.length <= 200, "lineage parserId 长度必须位于 1..200")
  require(
    Chunk(sourceSha256, artifactSha256, structureSha256, textSha256).forall(_.matches("[0-9a-f]{64}")),
    "lineage 摘要必须是小写 SHA-256"
  )

object DocumentLineage:
  /** 未经 Loader 的直接索引使用的解析器标识。 */
  val DirectParserId: String = "direct"

  /** 从文档与可信谱系输入推导完整谱系；缺失的上游层级退化为下一层的摘要，而不是伪造独立来源。 */
  def derive(
      document: SourceDocument,
      provenance: IngestionProvenance = IngestionProvenance()
  ): DocumentLineage =
    val text      = KnowledgeDigest.sha256(document.text)
    val artifact  = provenance.artifactSha256.getOrElse(text)
    val mediaType = provenance.artifactMediaType
      .orElse(document.metadata.get("mediaType"))
      .orElse(document.metadata.get("contentType"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("text/plain")
      .take(200)
    val source = provenance.source.getOrElse(SourceRevision(document.id, artifact))
    DocumentLineage(
      sourceId = source.sourceId,
      sourceRevisionId = source.revisionId,
      sourceSha256 = source.sha256.getOrElse(artifact),
      sourceMediaType = source.mediaType.getOrElse(mediaType),
      parserId =
        document.metadata.get("loaderId").map(_.trim).filter(_.nonEmpty).getOrElse(DirectParserId).take(200),
      artifactSha256 = artifact,
      structureSha256 = structureSha256(document),
      textSha256 = text
    )

  /** 规范化结构摘要：表示方式、结构 schema 以及每个 block 的身份、父子、顺序、类型、正文摘要、标题路径与页内几何。
    *
    * 正文不变但章节父子、页码或 bbox 改变时摘要随之改变，从而产生新的摄取身份。
    */
  def structureSha256(document: SourceDocument): String =
    val builder                    = StringBuilder()
    def field(value: String): Unit =
      val _ = builder.append(value.length).append(':').append(value).append('|')
    field(document.representation.toString)
    document.structure match
      case None            => field("none")
      case Some(structure) =>
        field(structure.schemaName)
        field(structure.schemaVersion.getOrElse(""))
        structure.blocks.foreach { block =>
          field(block.id)
          field(block.parentId.getOrElse(""))
          field(block.ordinal.toString)
          field(block.kind.toString)
          field(KnowledgeDigest.sha256(block.text))
          field(block.headingPath.mkString("\u001f"))
          block.origins.foreach { origin =>
            field(origin.pageNumber.toString)
            field(origin.blockId.getOrElse(""))
            origin.boundingBox.foreach { box =>
              field(
                Chunk(box.left, box.top, box.right, box.bottom)
                  .map(java.lang.Double.toString)
                  .mkString(",")
              )
              field(box.pageWidth.fold("")(java.lang.Double.toString))
              field(box.pageHeight.fold("")(java.lang.Double.toString))
              field(box.origin.toString)
            }
          }
          field("end-block")
        }
    KnowledgeDigest.sha256(builder.result())

/** 完整、不可变的索引构建规格；它的摘要就是 Profile 的配置身份。
  *
  * 任何改变向量值或切块结果的配置（模型、维度、tokenizer、文档 instruction、切分与词法策略、Enricher）都在这里。 批大小、并发和超时属于运行参数，不参与身份。
  */
final case class IndexBuildSpec(
    embeddingProvider: String,
    embeddingModel: String,
    embeddingDimension: Int,
    tokenizerId: String,
    documentInstruction: Option[String],
    indexingStrategy: String,
    enricher: String,
    distance: String = "cosine",
    specVersion: Int = 1
) derives JsonCodec:
  require(embeddingProvider.trim.nonEmpty && embeddingModel.trim.nonEmpty, "build spec embedding 身份不能为空")
  require(embeddingDimension > 0, "build spec embeddingDimension 必须为正数")
  require(tokenizerId.trim.nonEmpty, "build spec tokenizerId 不能为空")
  require(documentInstruction.forall(_.trim.nonEmpty), "build spec documentInstruction 不能为空白")
  require(indexingStrategy.trim.nonEmpty && enricher.trim.nonEmpty, "build spec 策略不能为空")
  require(distance == "cosine", "当前物理索引只支持 cosine")
  require(specVersion == 1, "未知 build spec 版本")

  /** 字段顺序固定、长度前缀的规范化摘要；不依赖 JSON 库的键顺序。 */
  lazy val sha256: String =
    KnowledgeDigest.sha256(
      Chunk(
        s"v$specVersion",
        embeddingProvider,
        embeddingModel,
        embeddingDimension.toString,
        distance,
        tokenizerId,
        documentInstruction.getOrElse(""),
        indexingStrategy,
        enricher
      ).map(value => s"${value.length}:$value").mkString("|")
    )

  def dense: DenseIndexIdentity =
    DenseIndexIdentity(embeddingProvider, embeddingModel, embeddingDimension, distance)

  def matches(descriptor: EmbeddingProviderDescriptor): Boolean =
    descriptor.provider == embeddingProvider && descriptor.model == embeddingModel &&
      descriptor.dimension == embeddingDimension

object IndexBuildSpec:
  /** 测试与未声明 tokenizer 的直接调用使用的占位 tokenizer。生产索引器从 Embedding 能力声明读取真实值。 */
  val UndeclaredTokenizer: String = "undeclared"

  def of(
      descriptor: EmbeddingProviderDescriptor,
      indexingStrategy: String,
      tokenizerId: String = UndeclaredTokenizer,
      documentInstruction: Option[String] = None,
      enricher: String = "identity"
  ): IndexBuildSpec =
    IndexBuildSpec(
      descriptor.provider,
      descriptor.model,
      descriptor.dimension,
      tokenizerId,
      documentInstruction,
      indexingStrategy,
      enricher
    )

  def enricherId(descriptor: EnricherDescriptor): String = descriptor match
    case EnricherDescriptor.Identity          => "identity"
    case EnricherDescriptor.Host(id, version) => s"host:$id:$version"

/** 一个版本暂存块集合的精确身份：块数加上按块 ID 排序后的 `id/display/dense/lexical` 摘要。
  *
  * 发布时 Store 从暂存表重新计算并比较，比只比较块数更能发现漏写、重复批次覆盖和跨版本混入。 排序使用 UTF-8 字节序，与 PostgreSQL `COLLATE "C"` 一致。
  */
final case class ChunkSetDigest(count: Int, sha256: String):
  require(count > 0, "ChunkSetDigest.count 必须为正数")
  require(sha256.matches("[0-9a-f]{64}"), "ChunkSetDigest.sha256 必须是小写 SHA-256")

object ChunkSetDigest:
  /** 块 ID 不能含制表符或换行，否则规范化行会产生歧义。 */
  def validChunkId(id: String): Boolean =
    id.nonEmpty && !id.exists(ch => ch == '\t' || ch == '\n' || ch == '\r')

  def line(chunk: DocumentChunk): String =
    val r = chunk.representations
    s"${chunk.id}\t${r.displaySha256}\t${r.denseSha256}\t${r.lexicalSha256}"

  def of(chunks: Chunk[DocumentChunk]): ChunkSetDigest =
    val lines =
      chunks.map(chunk => chunk.id -> line(chunk)).sortWith((a, b) => utf8Less(a._1, b._1)).map(_._2)
    ChunkSetDigest(chunks.length, KnowledgeDigest.sha256(lines.mkString("\n")))

  private def utf8Less(left: String, right: String): Boolean =
    java.util.Arrays.compareUnsigned(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8)
    ) < 0

/** 目标 Profile 与 active Profile 的逻辑语料差异；`isEmpty` 时才可能切换。
  *
  * @param missing
  *   active 中有、目标中没有的文档修订
  * @param stale
  *   两边都有但修订不同的文档（目标一侧的修订）
  * @param extra
  *   目标中有、active 中没有的文档
  * @param notReady
  *   目标中仍在 Building/Failed 的文档
  */
final case class ProfileCorpusDiff(
    missing: Chunk[ProfileCorpusEntry],
    stale: Chunk[ProfileCorpusEntry],
    extra: Chunk[ProfileCorpusEntry],
    notReady: Chunk[String]
):
  def isEmpty: Boolean = missing.isEmpty && stale.isEmpty && extra.isEmpty && notReady.isEmpty

final case class ProfileCorpusEntry(documentId: String, sourceRevisionId: String)

object ProfileCorpusDiff:
  def between(
      active: Chunk[ProfileCorpusEntry],
      target: Chunk[ProfileCorpusEntry],
      notReady: Chunk[String]
  ): ProfileCorpusDiff =
    val activeById = active.map(entry => entry.documentId -> entry).toMap
    val targetById = target.map(entry => entry.documentId -> entry).toMap
    ProfileCorpusDiff(
      missing = active.filterNot(entry => targetById.contains(entry.documentId)).sortBy(_.documentId),
      stale = target
        .filter(entry =>
          activeById.get(entry.documentId).exists(_.sourceRevisionId != entry.sourceRevisionId)
        )
        .sortBy(_.documentId),
      extra = target.filterNot(entry => activeById.contains(entry.documentId)).sortBy(_.documentId),
      notReady = notReady.distinct.sorted
    )

object KnowledgeDigest:
  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
