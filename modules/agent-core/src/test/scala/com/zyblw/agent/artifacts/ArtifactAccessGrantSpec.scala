package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*
import java.util.UUID
import zio.Chunk
import zio.test.*

object ArtifactAccessGrantSpec extends ZIOSpecDefault:
  private val tenant = TenantId("clinic-a")
  private val other  = TenantId("clinic-b")
  private val user   = UserId("user-1")
  private val bytes  = Chunk.fromArray(Array[Byte](1, 2, 3, 4))
  private val digest = ArtifactBlobStore.digest(bytes)

  private def userRef =
    ArtifactReference(
      ArtifactScope.User(tenant, user),
      ArtifactName("report.png"),
      version = 1L,
      mediaType = "image/png",
      byteSize = bytes.length.toLong,
      sha256 = digest
    )

  private def sessionRef =
    userRef.copy(scope =
      ArtifactScope.Session(SessionId(UUID.fromString("00000000-0000-0000-0000-000000000099")))
    )

  def spec = suite("ArtifactAccessGrant")(
    test("只允许同租户用户域兑换，session 与过期 grant fail-closed") {
      val issued  = ArtifactAccessGrant.issue(userRef, tenant, nowEpochMilli = 10L, ttlMillis = 5L)
      val expired = issued.flatMap(ArtifactAccessGrant.redeem(_, tenant, nowEpochMilli = 16L))
      val cross   = issued.flatMap(ArtifactAccessGrant.redeem(_, other, nowEpochMilli = 12L))
      assertTrue(
        ArtifactAccessGrant.authorize(userRef, tenant).isRight,
        ArtifactAccessGrant.authorize(userRef, other) == Left("artifact-tenant-mismatch"),
        ArtifactAccessGrant.authorize(sessionRef, tenant) == Left("artifact-session-scope-not-exportable"),
        expired == Left("artifact-grant-expired"),
        cross == Left("artifact-tenant-mismatch"),
        issued.flatMap(ArtifactAccessGrant.redeem(_, tenant, 12L)).contains(userRef)
      )
    },
    test("工具读取只允许当前 Run 域，拒绝模型自报的其他隔离域") {
      val runId    = RunId(UUID.fromString("00000000-0000-0000-0000-000000000401"))
      val thread   = ThreadId("thread-a")
      val context  = ToolExecutionContext(runId, thread, "call-1", RunContext(tenantId = Some(tenant.value)))
      val runRef   = userRef.copy(scope = ArtifactScope.Run(runId, thread))
      val otherRun = userRef.copy(scope = ArtifactScope.Run(runId, ThreadId("other")))
      assertTrue(
        ArtifactAccessGrant.authorize(runRef, context).contains(runRef),
        ArtifactAccessGrant.authorize(otherRun, context) == Left("artifact-run-scope-mismatch"),
        ArtifactAccessGrant.authorize(runRef, tenant) == Left("artifact-run-scope-not-exportable")
      )
    }
  )
