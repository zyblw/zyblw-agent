package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 多阶段 PDF/文档解析级联配置。
  *
  * 字节只收集一次，后续阶段消费 `DocumentInput.fromBytes` 回放，避免一次性 `ZStream` 被廉价解析耗尽后无法 OCR。
  */
final case class CascadingDocumentLoaderConfig(
    mediaTypes: Set[String] = Set("application/pdf"),
    maxInputBytes: Int = 32 * 1024 * 1024,
    quality: ExtractionQualityPolicy = ExtractionQualityPolicy(),
    failIfInsufficient: Boolean = true
):
  require(mediaTypes.nonEmpty, "CascadingDocumentLoader mediaTypes 不能为空")
  require(maxInputBytes > 0, "CascadingDocumentLoader maxInputBytes 必须为正数")

/** 按成本递增尝试多个 Loader：廉价文本层 → OCR → 可选逐页 VLM。
  *
  * 默认 `extractionMode=auto` 由程序根据提取质量自动升档。业务可在 `DocumentInput.metadata` 写入 `extractionMode=text|ocr|vision`
  * 强制只走对应档；未装配该档时 fail-closed。
  */
final class CascadingDocumentLoader(
    stages: Chunk[ExtractionStage],
    config: CascadingDocumentLoaderConfig = CascadingDocumentLoaderConfig()
) extends DocumentLoader:
  require(stages.nonEmpty, "CascadingDocumentLoader 至少需要一个解析阶段")
  require(
    stages.forall(_.supportedMediaTypes.exists(config.mediaTypes.contains)),
    "CascadingDocumentLoader 每个阶段必须覆盖所声明的至少一个 MIME"
  )

  override val id: String = s"cascade:${stages.map(_.id).mkString("+")}"

  override val supportedMediaTypes: Set[String] = config.mediaTypes

  def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
    rejectDeclaredOversize(input) *>
      ZIO
        .fromEither(ExtractionMode.fromMetadata(input.metadata))
        .mapError(AgentError.RetrievalFailed(_))
        .flatMap { mode =>
          input.content.take(config.maxInputBytes.toLong + 1L).runCollect.flatMap { bytes =>
            if bytes.length > config.maxInputBytes then
              ZIO.fail(AgentError.RetrievalFailed(s"文档输入超过级联解析字节上限 ${config.maxInputBytes}"))
            else replay(input, bytes, mode)
          }
        }

  private def rejectDeclaredOversize(input: DocumentInput): IO[RetrievalError, Unit] =
    input.declaredLength match
      case Some(length) if length > config.maxInputBytes.toLong =>
        ZIO.fail(AgentError.RetrievalFailed(s"文档声明长度 $length 超过级联解析字节上限 ${config.maxInputBytes}"))
      case _ => ZIO.unit

  private def replay(
      input: DocumentInput,
      bytes: Chunk[Byte],
      mode: ExtractionMode
  ): IO[RetrievalError, SourceDocument] =
    val replayed = DocumentInput.fromBytes(
      input.id,
      input.sourceUri,
      input.fileName,
      input.declaredMediaType,
      bytes,
      input.metadata.updated(ExtractionMode.MetadataKey, mode.wireValue)
    )
    val applicable = stages.filter(_.supportedMediaTypes.contains(input.declaredMediaType))
    val selected   = select(applicable, mode)
    if applicable.isEmpty then
      ZIO.fail(AgentError.RetrievalFailed(s"级联解析没有支持 ${input.declaredMediaType} 的阶段"))
    else if selected.isEmpty then ZIO.fail(AgentError.RetrievalFailed(s"当前部署未装配 ${mode.wireValue} 解析阶段"))
    else attempt(replayed, selected, mode, attemptIndex = 0, lastError = None, lastInsufficient = None)

  private def select(stages: Chunk[ExtractionStage], mode: ExtractionMode): Chunk[ExtractionStage] =
    mode match
      case ExtractionMode.Auto      => stages
      case ExtractionMode.TextLayer => stages.filter(_.kind == ExtractionStageKind.TextLayer)
      case ExtractionMode.LayoutOcr => stages.filter(_.kind == ExtractionStageKind.LayoutOcr)
      case ExtractionMode.Vision    => stages.filter(_.kind == ExtractionStageKind.Vision)

  private def attempt(
      input: DocumentInput,
      remaining: Chunk[ExtractionStage],
      mode: ExtractionMode,
      attemptIndex: Int,
      lastError: Option[RetrievalError],
      lastInsufficient: Option[SourceDocument]
  ): IO[RetrievalError, SourceDocument] =
    remaining.headOption match
      case None =>
        lastInsufficient match
          case Some(document) if !config.failIfInsufficient =>
            ZIO.succeed(
              annotate(document, mode, attemptIndex, fallbackUsed = attemptIndex > 1, sufficient = false)
            )
          case Some(_) =>
            ZIO.fail(AgentError.RetrievalFailed("文档提取质量不足，拒绝索引空白或损坏文本层"))
          case None =>
            ZIO.fail(lastError.getOrElse(AgentError.RetrievalFailed("级联解析未提取到可索引正文")))
      case Some(stage) =>
        stage
          .load(input)
          .foldZIO(
            error => attempt(input, remaining.drop(1), mode, attemptIndex + 1, Some(error), lastInsufficient),
            document =>
              val quality = ExtractionQuality.assess(document.text)
              if quality.sufficient(config.quality) then
                ZIO.succeed(
                  annotate(
                    document,
                    mode,
                    attemptIndex + 1,
                    fallbackUsed = attemptIndex > 0,
                    sufficient = true,
                    Some(quality),
                    Some(stage.id)
                  )
                )
              else
                attempt(
                  input,
                  remaining.drop(1),
                  mode,
                  attemptIndex + 1,
                  lastError,
                  Some(
                    annotate(
                      document,
                      mode,
                      attemptIndex + 1,
                      fallbackUsed = true,
                      sufficient = false,
                      Some(quality),
                      Some(stage.id)
                    )
                  )
                )
          )

  private def annotate(
      document: SourceDocument,
      mode: ExtractionMode,
      attemptCount: Int,
      fallbackUsed: Boolean,
      sufficient: Boolean,
      quality: Option[ExtractionQuality] = None,
      method: Option[String] = None
  ): SourceDocument =
    val extras = Map(
      ExtractionMode.MetadataKey -> mode.wireValue,
      "extractionFallbackUsed"   -> fallbackUsed.toString,
      "extractionAttemptCount"   -> attemptCount.toString,
      "extractionSufficient"     -> sufficient.toString
    ) ++ method.map("extractionMethod" -> _) ++ quality.map("extractionQuality" -> _.compact)
    document.copy(metadata = document.metadata ++ extras)
