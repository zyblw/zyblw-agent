package com.zyblw.agent.workspace

import com.zyblw.agent.artifacts.ArtifactBlobStore
import com.zyblw.agent.core.*
import zio.*

/** inspect → validate → apply → rollback。禁止用宽泛 shell 代替这次序。 */
final case class WorkspaceInspection(path: WorkspacePath, sha256: String, byteSize: Long)

final case class WorkspacePatch(
    path: WorkspacePath,
    expectedSha256: String,
    bytes: Chunk[Byte]
):
  require(expectedSha256.matches("[0-9a-f]{64}"), "WorkspacePatch.expectedSha256 必须是 SHA-256")

final case class WorkspaceAppliedEdit(
    path: WorkspacePath,
    previous: Chunk[Byte],
    nextSha256: String
)

object WorkspaceEdit:
  def inspect(
      workspace: Workspace,
      path: WorkspacePath,
      maxBytes: Long
  ): IO[AgentError, WorkspaceInspection] =
    workspace.read(path, maxBytes).map { bytes =>
      WorkspaceInspection(path, ArtifactBlobStore.digest(bytes), bytes.length.toLong)
    }

  def validate(inspection: WorkspaceInspection, patch: WorkspacePatch): Either[String, WorkspacePatch] =
    if patch.path.value != inspection.path.value then Left("workspace-edit-path-mismatch")
    else if patch.expectedSha256 != inspection.sha256 then Left("workspace-edit-stale-base")
    else if ArtifactBlobStore.digest(patch.bytes) == inspection.sha256 then Left("workspace-edit-noop")
    else Right(patch)

  def apply(
      workspace: Workspace,
      inspection: WorkspaceInspection,
      patch: WorkspacePatch
  ): IO[AgentError, WorkspaceAppliedEdit] =
    ZIO.fromEither(validate(inspection, patch)).mapError(AgentError.PermissionDenied("workspace.edit", _)) *>
      workspace.read(patch.path, math.max(inspection.byteSize, 1L)).flatMap { previous =>
        workspace
          .write(patch.path, patch.bytes, overwrite = true)
          .as(
            WorkspaceAppliedEdit(patch.path, previous, ArtifactBlobStore.digest(patch.bytes))
          )
      }

  def rollback(workspace: Workspace, applied: WorkspaceAppliedEdit): IO[AgentError, Unit] =
    workspace.write(applied.path, applied.previous, overwrite = true)
