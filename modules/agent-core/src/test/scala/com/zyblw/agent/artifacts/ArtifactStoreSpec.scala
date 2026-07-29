package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*
import java.util.UUID
import zio.*
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
    test("非法版本和路径型名称被拒绝，不把它们降级成缺失内容") {
      (for
        store <- ZIO.service[ArtifactStore]
        invalid = ArtifactName.fromString("../outside.bin")
        version <- store.read(sessionScope, report, Some(0L)).exit
      yield assertTrue(invalid.isLeft, version.isFailure)).provide(ArtifactStore.inMemory())
    }
  )
