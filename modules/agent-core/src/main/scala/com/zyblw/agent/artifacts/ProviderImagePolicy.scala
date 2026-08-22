package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*

/** Provider 出站图片门禁：只允许已绑定的 data-image URI，拒绝远程 URL 与未绑定 Artifact。 */
object ProviderImagePolicy:
  def allowsDataUri(url: String): Boolean =
    val normalized = url.trim
    ArtifactBoundMedia.AllowedImages.exists(media => normalized.startsWith(s"data:$media;")) &&
    !normalized.toLowerCase.contains("http://") &&
    !normalized.toLowerCase.contains("https://")

  def requireBound(request: ChatRequest): Either[AgentError, Unit] =
    request.messages
      .flatMap(_.content)
      .collectFirst {
        case ContentPart.ImageArtifact(_, _, _) =>
          AgentError.InvalidConfiguration("unbound ImageArtifact; bind via ArtifactBoundMedia first")
        case ContentPart.ImageUrl(url, _) if !allowsDataUri(url) =>
          AgentError.PermissionDenied("model.image", "remote-image-url-forbidden")
      }
      .toLeft(())
