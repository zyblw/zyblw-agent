package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*
import zio.Chunk

/** Artifact 进入模型可见媒体前的边界：只接受已校验 digest/大小/MIME 的引用，不把任意 URL 当成图片。 */
object ArtifactBoundMedia:
  val AllowedImages: Set[String]    = Set("image/png", "image/jpeg", "image/webp")
  val AllowedDocuments: Set[String] = Set("application/pdf")
  val MaxBytes: Long                = 8L * 1024 * 1024

  def accept(reference: ArtifactReference, bytes: Chunk[Byte]): Either[String, ArtifactReference] =
    if bytes.length.toLong != reference.byteSize then Left("artifact-size-mismatch")
    else if ArtifactBlobStore.digest(bytes) != reference.sha256 then Left("artifact-digest-mismatch")
    else if reference.byteSize > MaxBytes then Left("artifact-too-large")
    else if !(AllowedImages ++ AllowedDocuments).contains(reference.mediaType) then Left("unsupported-media")
    else Right(reference)

  /** 通过边界后只产生低敏文本占位；Provider adapter 再按引用取字节，禁止 ImageUrl SSRF。 */
  def imagePlaceholder(reference: ArtifactReference, bytes: Chunk[Byte]): Either[String, AgentMessage] =
    accept(reference, bytes).flatMap { accepted =>
      if !AllowedImages.contains(accepted.mediaType) then Left("not-an-image")
      else
        Right(
          AgentMessage.user(s"artifact-image:${accepted.sha256}:${accepted.mediaType}:${accepted.byteSize}")
        )
    }

  def imagePart(reference: ArtifactReference, bytes: Chunk[Byte]): Either[String, ContentPart.ImageArtifact] =
    accept(reference, bytes).flatMap { accepted =>
      if !AllowedImages.contains(accepted.mediaType) then Left("not-an-image")
      else Right(ContentPart.ImageArtifact(accepted.sha256, accepted.mediaType, accepted.byteSize))
    }

  def dataUri(mediaType: String, bytes: Chunk[Byte]): Either[String, String] =
    if !AllowedImages.contains(mediaType) then Left("not-an-image")
    else
      val encoded = java.util.Base64.getEncoder.encodeToString(bytes.toArray)
      Right(s"data:$mediaType;base64,$encoded")

  /** 将耐久 `ImageArtifact` 绑定为仅含 data URI 的瞬时 `ImageUrl`；远程 URL 永远 fail-closed。 */
  def bind(part: ContentPart, bytesByDigest: Map[String, Chunk[Byte]]): Either[String, ContentPart] =
    part match
      case ContentPart.ImageArtifact(sha256, mediaType, byteSize) =>
        bytesByDigest.get(sha256).toRight("artifact-bytes-missing").flatMap { bytes =>
          if bytes.length.toLong != byteSize then Left("artifact-size-mismatch")
          else if ArtifactBlobStore.digest(bytes) != sha256 then Left("artifact-digest-mismatch")
          else if byteSize > MaxBytes then Left("artifact-too-large")
          else dataUri(mediaType, bytes).map(ContentPart.ImageUrl(_, None))
        }
      case ContentPart.ImageUrl(url, detail) =>
        if ProviderImagePolicy.allowsDataUri(url) then Right(ContentPart.ImageUrl(url, detail))
        else Left("remote-image-url-forbidden")
      case other => Right(other)

  def bindRequest(
      request: ChatRequest,
      bytesByDigest: Map[String, Chunk[Byte]]
  ): Either[String, ChatRequest] =
    request.messages
      .foldLeft[Either[String, Chunk[AgentMessage]]](Right(Chunk.empty)) { (acc, message) =>
        acc.flatMap { done =>
          message.content
            .foldLeft[Either[String, Chunk[ContentPart]]](Right(Chunk.empty)) { (parts, part) =>
              parts.flatMap(current => bind(part, bytesByDigest).map(current :+ _))
            }
            .map(parts => done :+ message.copy(content = parts))
        }
      }
      .map(messages => request.copy(messages = messages))
