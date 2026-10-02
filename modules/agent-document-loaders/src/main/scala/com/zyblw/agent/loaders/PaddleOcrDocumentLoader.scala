package com.zyblw.agent.loaders

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import zio.*
import zio.json.*

/** Offline Paddle artifacts; the host pairs bytes, identity and optional original-page count. */
final case class PaddleOcrArtifact(
    json: String,
    markdown: Option[String] = None,
    pageCount: Option[Int] = None,
    originalSha256: Option[String] = None
) derives JsonCodec

final class PaddleOcrDocumentLoader extends DocumentLoader:
  val id                                                             = PaddleOcrVlDocument.Method
  val supportedMediaTypes                                            = Set(PaddleOcrDocumentLoader.MediaType)
  def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
    if input.declaredLength.exists(_ > PaddleOcrDocumentLoader.MaxBytes) then
      ZIO.fail(AgentError.RetrievalFailed("Paddle artifact declared byte limit"))
    else
      input.content.take(PaddleOcrDocumentLoader.MaxBytes + 1L).runCollect.flatMap { bytes =>
        for
          _ <- ZIO
            .fail(AgentError.RetrievalFailed("Paddle artifact byte limit"))
            .when(bytes.length > PaddleOcrDocumentLoader.MaxBytes)
          text <- ZIO
            .attempt(
              StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toArray))
                .toString
            )
            .mapError(_ => AgentError.RetrievalFailed("Paddle artifact UTF-8 invalid"))
          artifact <- ZIO
            .fromEither(text.fromJson[PaddleOcrArtifact])
            .mapError(_ => AgentError.RetrievalFailed("Paddle artifact envelope invalid"))
          _ <- ZIO
            .fail(AgentError.RetrievalFailed("Paddle original digest invalid"))
            .when(artifact.originalSha256.exists(s => !s.matches("[0-9a-f]{64}")))
          parsed <- ZIO
            .fromEither(PaddleOcrVlDocument.decode(artifact.json, artifact.markdown, artifact.pageCount))
            .mapError(AgentError.RetrievalFailed(_))
        yield parsed.toSourceDocument(
          input.id,
          input.sourceUri,
          input.metadata ++ Map(
            "paddleJsonSha256"     -> KnowledgeDigest.sha256(artifact.json),
            "paddleMarkdownSha256" -> KnowledgeDigest.sha256(artifact.markdown.getOrElse("")),
            "pageCount"            -> parsed.pageCount.toString,
            "title"                -> parsed.sections.headOption.map(_.title).getOrElse(input.fileName)
          ) ++ artifact.originalSha256.map("originalSha256" -> _)
        )
      }

object PaddleOcrDocumentLoader:
  val MediaType = "application/vnd.zyblw.paddleocr+json"
  val MaxBytes  = 20 * 1024 * 1024
