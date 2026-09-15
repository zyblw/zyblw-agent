package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*
import java.util.UUID
import zio.*
import zio.json.*
import zio.test.*

/** 不可变 Artifact 版本、scope 隔离与输入治理的确定性契约测试。 */
object ArtifactStoreSpec extends ZIOSpecDefault:
  private val sessionScope =
    ArtifactScope.Session(SessionId(UUID.fromString("00000000-0000-0000-0000-000000000001")))
  private val userScope = ArtifactScope.User(TenantId("tenant-a"), UserId("user-a"))
  private val report    = ArtifactName("reports/answer.pdf")

  private def input(bytes: Chunk[Byte], mediaType: String = "application/pdf"): ArtifactInput =
    ArtifactInput(bytes, mediaType, Map("source" -> "agent"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ArtifactStore")(
    test("同名保存追加不可变版本，读取旧版和最新版都保留原始二进制") {
      (for
        store  <- ZIO.service[ArtifactStore]
        first  <- store.save(sessionScope, report, input(Chunk(0x00.toByte, 0xff.toByte)))
        second <- store.save(sessionScope, report, input(Chunk(0x01.toByte)))
        old    <- store.read(sessionScope, report, Some(1L))
        latest <- store.read(sessionScope, report)
      yield assertTrue(
        first.version == 1L,
        second.version == 2L,
        first.sha256 == "06eb7d6a69ee19e5fbdf749018d3d2abfa04bcbd1365db312eb86dc7169389b8",
        old.map(_.bytes) == Some(Chunk(0x00.toByte, 0xff.toByte)),
        latest.map(_.bytes) == Some(Chunk(0x01.toByte)),
        latest.map(_.descriptor.version) == Some(2L)
      )).provide(ArtifactStore.inMemory())
    },
    test("低敏引用不携带 metadata，并在内容身份漂移时 fail-closed") {
      (for
        store      <- ZIO.service[ArtifactStore]
        descriptor <- store.save(sessionScope, report, input(Chunk(1.toByte, 2.toByte)))
        reference = descriptor.reference
        loaded   <- store.read(reference)
        mismatch <- store.read(reference.copy(sha256 = "0" * 64)).either
      yield assertTrue(
        loaded.map(_.descriptor) == Some(descriptor),
        reference.matches(descriptor),
        !reference.toString.contains("source -> agent"),
        !reference.toJson.contains("metadata"),
        !reference.toJson.contains("createdAt"),
        !reference.toJson.contains("bytes"),
        mismatch.left.exists {
          case AgentError.ArtifactPolicyRejected(_, reason) => reason == "reference-mismatch"
          case _                                            => false
        }
      )).provide(ArtifactStore.inMemory())
    },
    test("scope 完全隔离，列表只暴露最新描述符且按名称稳定排序") {
      (for
        store   <- ZIO.service[ArtifactStore]
        _       <- store.save(sessionScope, ArtifactName("zeta.txt"), input(Chunk(1.toByte), "text/plain"))
        _       <- store.save(sessionScope, ArtifactName("alpha.txt"), input(Chunk(2.toByte), "text/plain"))
        _       <- store.save(sessionScope, ArtifactName("alpha.txt"), input(Chunk(3.toByte), "text/plain"))
        _       <- store.save(userScope, ArtifactName("private.txt"), input(Chunk(4.toByte), "text/plain"))
        session <- store.list(sessionScope, 10)
        user    <- store.read(userScope, ArtifactName("alpha.txt"))
      yield assertTrue(
        session.map(_.name.value) == Chunk("alpha.txt", "zeta.txt"),
        session.head.version == 2L,
        user.isEmpty
      )).provide(ArtifactStore.inMemory())
    },
    test("容量、敏感 metadata 和 scope 名称配额均 fail-closed，失败不会写入内容") {
      val policy = ArtifactStorePolicy(maxArtifactBytes = 2L, maxArtifactsPerScope = 1)
      (for
        store    <- ZIO.service[ArtifactStore]
        tooLarge <- store
          .save(sessionScope, ArtifactName("large.bin"), input(Chunk(1.toByte, 2.toByte, 3.toByte)))
          .exit
        secret <- store
          .save(
            sessionScope,
            ArtifactName("secret.txt"),
            ArtifactInput(Chunk(1.toByte), "text/plain", Map("api_key" -> "x"))
          )
          .exit
        _     <- store.save(sessionScope, ArtifactName("first.txt"), input(Chunk(1.toByte), "text/plain"))
        quota <- store
          .save(sessionScope, ArtifactName("second.txt"), input(Chunk(2.toByte), "text/plain"))
          .exit
        listed <- store.list(sessionScope, 10)
      yield assertTrue(
        tooLarge.isFailure,
        secret.isFailure,
        quota.isFailure,
        listed.map(_.name.value) == Chunk("first.txt")
      )).provide(ArtifactStore.inMemory(policy))
    },
    test("删除历史版本并审计，拒绝删除最新版本") {
      (for
        store  <- ZIO.service[ArtifactStore]
        first  <- store.save(sessionScope, report, input(Chunk(1.toByte)))
        _      <- store.save(sessionScope, report, input(Chunk(2.toByte)))
        latest <- store.delete(sessionScope, report, 2L).either
        gone   <- store.delete(sessionScope, report, first.version)
        old    <- store.read(sessionScope, report, Some(first.version))
        audits <- store.audits(20)
      yield assertTrue(
        latest.left.exists {
          case AgentError.ArtifactPolicyRejected(_, reason) => reason == "cannot-delete-latest"
          case _                                            => false
        },
        gone == 1L,
        old.isEmpty,
        audits.exists(record =>
          record.action == ArtifactAuditAction.Delete && record.reasonCode == "user-requested"
        ),
        !audits.exists(_.nameHash.contains("reports"))
      )).provide(ArtifactStore.inMemory())
    },
    test("保留期清理跳过最新版本") {
      (for
        store  <- ZIO.service[ArtifactStore]
        _      <- store.save(sessionScope, report, input(Chunk(1.toByte)))
        _      <- store.save(sessionScope, report, input(Chunk(2.toByte)))
        now    <- Clock.instant
        purged <- store.purgeExpired(now.plusSeconds(1), 10)
        latest <- store.read(sessionScope, report)
        old    <- store.read(sessionScope, report, Some(1L))
      yield assertTrue(purged == 1L, latest.map(_.bytes) == Some(Chunk(2.toByte)), old.isEmpty))
        .provide(ArtifactStore.inMemory())
    },
    test("非法版本和路径型名称被拒绝，不把它们降级成缺失内容") {
      (for
        store <- ZIO.service[ArtifactStore]
        invalid = ArtifactName.fromString("../outside.bin")
        version <- store.read(sessionScope, report, Some(0L)).exit
      yield assertTrue(invalid.isLeft, version.isFailure)).provide(ArtifactStore.inMemory())
    },
    test("Run 域与 Session 域完全隔离") {
      val runScope = ArtifactScope.Run(
        RunId(UUID.fromString("00000000-0000-0000-0000-000000000301")),
        ThreadId("thread-run")
      )
      (for
        store <- ZIO.service[ArtifactStore]
        _     <- store.save(runScope, report, input(Chunk(9.toByte), "application/json"))
        same  <- store.read(runScope, report)
        other <- store.read(sessionScope, report)
      yield assertTrue(same.map(_.bytes) == Some(Chunk(9.toByte)), other.isEmpty))
        .provide(ArtifactStore.inMemory())
    }
  )
