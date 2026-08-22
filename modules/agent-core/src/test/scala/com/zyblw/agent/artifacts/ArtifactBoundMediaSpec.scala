package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*
import java.util.UUID
import zio.*
import zio.test.*

object ArtifactBoundMediaSpec extends ZIOSpecDefault:
  private val bytes  = Chunk.fromArray(Array[Byte](1, 2, 3, 4))
  private val digest = ArtifactBlobStore.digest(bytes)

  private def reference(mediaType: String, size: Long = bytes.length.toLong, sha: String = digest) =
    ArtifactReference(
      ArtifactScope.Session(SessionId(UUID.fromString("00000000-0000-0000-0000-000000000041"))),
      ArtifactName("page.png"),
      version = 1L,
      mediaType = mediaType,
      byteSize = size,
      sha256 = sha
    )

  def spec = suite("ArtifactBoundMedia")(
    test("digest、大小和 MIME 必须同时匹配，且拒绝任意 URL 媒体") {
      val ok       = ArtifactBoundMedia.imagePlaceholder(reference("image/png"), bytes)
      val mismatch = ArtifactBoundMedia.accept(reference("image/png", size = 99L), bytes)
      val badType  = ArtifactBoundMedia.accept(reference("text/plain"), bytes)
      val notImage = ArtifactBoundMedia.imagePlaceholder(reference("application/pdf"), bytes)
      val part     = ArtifactBoundMedia.imagePart(reference("image/png"), bytes)
      val bound    = part.flatMap(ArtifactBoundMedia.bind(_, Map(digest -> bytes)))
      val remote   = ArtifactBoundMedia.bind(ContentPart.ImageUrl("https://evil.example/x.png"), Map.empty)
      val encoded  = bound.flatMap {
        case ContentPart.ImageUrl(url, _) =>
          OpenAIWireSafe(url)
        case _ => Left("not-bound")
      }
      assertTrue(
        ok.exists(_.text.contains(digest)),
        mismatch == Left("artifact-size-mismatch"),
        badType == Left("unsupported-media"),
        notImage == Left("not-an-image"),
        part.exists(_.sha256 == digest),
        bound.exists {
          case ContentPart.ImageUrl(url, _) =>
            url.startsWith("data:image/png;base64,") && !url.contains("http")
          case _ => false
        },
        remote == Left("remote-image-url-forbidden"),
        encoded.contains(())
      )
    }
  )

  private def OpenAIWireSafe(url: String): Either[String, Unit] =
    Either.cond(ProviderImagePolicy.allowsDataUri(url), (), "remote-image-url-forbidden")
