package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.time.Instant
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 使用真实 PostgreSQL 16 验证评测趋势仓库的多节点生产语义。
  *
  * 普通内存或 mock JDBC 无法证明唯一索引竞争、`ON CONFLICT` 等待、JSONB、部分索引查询和 Flyway SQL 真实可执行， 因此本套件通过
  * `RUN_POSTGRES_INTEGRATION=1` 显式开启 Testcontainers。测试不会调用任何模型 Provider。
  */
object PostgresEvalTrendStoreIntegrationSpec extends ZIOSpecDefault:
  private val baseInstant   = Instant.parse("2026-07-17T00:00:00Z")
  private val agentIdentity =
    EvalTrendIdentity(EvalSuiteKind.Agent, "tcm-learning-agent", "tcm-learning-golden", "dataset-v1")

  /** 两个独立 Store 实例共享同一个 DataSource，模拟两个 CI/worker 进程。
    *
    * @param storeA
    *   第一个 Adapter 实例
    * @param storeB
    *   第二个 Adapter 实例
    * @param dataSource
    *   仅供测试执行受控篡改和原始低敏边界断言
    */
  final private case class Harness(
      storeA: PostgresEvalTrendStore,
      storeB: PostgresEvalTrendStore,
      dataSource: DataSource
  )

  /** 启动干净 PostgreSQL 并执行正式 V001；迁移中的语法、CHECK 或索引错误都会使 Layer 构造失败。 */
  private val harnessLayer: ZLayer[Any, Throwable, Harness] = ZLayer.scoped {
    for
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val value =
            PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"))
          value.start()
          value
        }
      )(value => ZIO.attemptBlocking(value.stop()).orDie)
      dataSource <- ZIO.attempt {
        val value = PGSimpleDataSource()
        value.setURL(container.jdbcUrl)
        value.setUser(container.username)
        value.setPassword(container.password)
        value: DataSource
      }
      _ <- ZIO.attemptBlocking {
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(AgentPostgresMigrations.DefaultLocation)
          .load()
          .migrate()
      }
    yield Harness(
      PostgresEvalTrendStore(dataSource),
      PostgresEvalTrendStore(dataSource),
      dataSource
    )
  }

  /** 创建一份稳定 metadata；序号同时控制 startedAt/finishedAt 的确定顺序。 */
  private def metadata(evaluationId: String, sequence: Int): EvalSnapshotMetadata =
    EvalSnapshotMetadata(
      evaluationId = evaluationId,
      suiteId = agentIdentity.suiteId,
      datasetId = agentIdentity.datasetId,
      datasetVersion = agentIdentity.datasetVersion,
      harnessVersion = "harness-v1",
      provider = Some("stub"),
      model = Some("stub-model"),
      pricingVersion = Some("price-v1"),
      commitSha = Some("abcdef1234"),
      startedAt = baseInstant.plusSeconds(sequence.toLong),
      // 使用非微秒 nano，证明数据库排序依赖显式 second+nano，而不是 TIMESTAMPTZ 舍入。
      finishedAt = baseInstant.plusSeconds(sequence.toLong).plusNanos(sequence.toLong)
    )

  /** 构造一个单维度低敏快照；`passed=false` 用于验证失败候选不会替换成功基线。 */
  private def snapshot(
      evaluationId: String,
      sequence: Int,
      passed: Boolean = true,
      score: Double = 1.0,
      kind: EvalSuiteKind = EvalSuiteKind.Agent
  ): EvalSuiteSnapshot =
    EvalSuiteSnapshot(
      EvalSuiteSnapshot.CurrentSchemaVersion,
      kind,
      metadata(evaluationId, sequence),
      Chunk(
        EvalCaseSnapshot(
          s"case-$sequence",
          agentIdentity.datasetVersion,
          Chunk(EvalDimensionSnapshot("tool-selection", passed, score))
        )
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostgreSQL EvalTrendStore")(
    test("跨 Store 并发追加、kind 隔离、有界历史和最近成功基线保持确定") {
      (for
        harness <- ZIO.service[Harness]
        passing = Chunk.fromIterable((1 to 24).map(index => snapshot(f"eval-$index%02d", index)))
        failed  = snapshot("eval-25-failed", 25, passed = false, score = 0.0)
        rag     = snapshot("eval-26-rag", 26, kind = EvalSuiteKind.Rag)
        _ <- ZIO.foreachParDiscard(passing.zipWithIndex) { case (value, index) =>
          if index % 2 == 0 then harness.storeA.append(value) else harness.storeB.append(value)
        }
        _             <- harness.storeA.append(failed)
        _             <- harness.storeB.append(rag)
        history       <- harness.storeB.history(agentIdentity, 5)
        latestPassing <- harness.storeA.latestPassing(agentIdentity)
        ragHistory    <- harness.storeA.history(agentIdentity.copy(kind = EvalSuiteKind.Rag), 10)
      yield assertTrue(
        history.map(_.metadata.evaluationId) ==
          Chunk("eval-21", "eval-22", "eval-23", "eval-24", "eval-25-failed"),
        latestPassing.map(_.metadata.evaluationId).contains("eval-24"),
        ragHistory == Chunk(rag)
      )).provideLayer(harnessLayer)
    },
    test("相同 evaluationId 同内容幂等，不同内容并发竞争只能留下一个不可变事实") {
      (for
        harness <- ZIO.service[Harness]
        same = snapshot("eval-same-content", 1)
        sameResults <- ZIO.collectAllPar(
          Chunk(harness.storeA.append(same).either, harness.storeB.append(same).either)
        )
        left  = snapshot("eval-conflicting-content", 2, passed = true, score = 1.0)
        right = snapshot("eval-conflicting-content", 2, passed = false, score = 0.0)
        conflictResults <- ZIO.collectAllPar(
          Chunk(harness.storeA.append(left).either, harness.storeB.append(right).either)
        )
        history <- harness.storeA.history(agentIdentity, 10)
        count   <- rowCount(harness.dataSource)
      yield assertTrue(
        sameResults.forall(_.isRight),
        conflictResults.count(_.isRight) == 1,
        conflictResults.count(_.left.exists(_.message == "eval-trend:evaluation-id-conflict")) == 1,
        history.count(_.metadata.evaluationId == "eval-same-content") == 1,
        history.count(_.metadata.evaluationId == "eval-conflicting-content") == 1,
        count == 2
      )).provideLayer(harnessLayer)
    },
    test("低敏投影不落原始 details，checksum 或冗余列被篡改时读取 fail-closed") {
      (for
        harness <- ZIO.service[Harness]
        secret = "用户原始问题与密钥 sk-secret-must-not-persist"
        report = AgentEvalSuiteReport(
          Chunk(
            AgentEvalReport(
              "safe-case",
              agentIdentity.datasetVersion,
              Chunk(EvalGrade("tool-selection", passed = true, score = 1.0, details = secret))
            )
          )
        )
        projected <- EvalSuiteSnapshot.fromAgent(metadata("eval-low-sensitive", 1), report)
        _         <- harness.storeA.append(projected)
        raw       <- rawSnapshot(harness.dataSource, projected.metadata.evaluationId)
        _         <- tamperChecksum(harness.dataSource, projected.metadata.evaluationId)
        result    <- harness.storeB.latestPassing(agentIdentity).either
      yield assertTrue(
        !raw.contains(secret),
        !raw.contains("sk-secret"),
        !raw.contains("details"),
        result.left.exists(_.category == ErrorCategory.Persistence),
        result.left.exists(!_.retryable),
        result.left.exists(_.message.contains("checksum-mismatch")),
        result.left.forall(!_.message.contains(projected.metadata.evaluationId))
      )).provideLayer(harnessLayer)
    }
  ) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(
    3.minutes
  ) @@ TestAspect.sequential

  /** 查询表中真实行数，确认幂等与冲突不会产生重复事实。 */
  private def rowCount(dataSource: DataSource): Task[Int] = ZIO.attemptBlocking {
    val connection = dataSource.getConnection
    try
      val statement = connection.prepareStatement("SELECT count(*) FROM agent_eval_snapshots")
      try
        val result = statement.executeQuery()
        result.next()
        result.getInt(1)
      finally statement.close()
    finally connection.close()
  }

  /** 读取数据库原始 JSON，只用于证明长期表没有保存 grade details 或秘密正文。 */
  private def rawSnapshot(dataSource: DataSource, evaluationId: String): Task[String] = ZIO.attemptBlocking {
    val connection = dataSource.getConnection
    try
      val statement = connection.prepareStatement(
        "SELECT snapshot_json::text FROM agent_eval_snapshots WHERE evaluation_id = ?"
      )
      try
        statement.setString(1, evaluationId)
        val result = statement.executeQuery()
        if !result.next() then throw IllegalStateException("测试快照不存在")
        result.getString(1)
      finally statement.close()
    finally connection.close()
  }

  /** 模拟错误运维脚本篡改 checksum；读取方必须拒绝整条趋势而不是跳过坏行。 */
  private def tamperChecksum(dataSource: DataSource, evaluationId: String): Task[Unit] = ZIO.attemptBlocking {
    val connection = dataSource.getConnection
    try
      val statement = connection.prepareStatement(
        "UPDATE agent_eval_snapshots SET snapshot_sha256 = ? WHERE evaluation_id = ?"
      )
      try
        statement.setString(1, "0" * 64)
        statement.setString(2, evaluationId)
        if statement.executeUpdate() != 1 then throw IllegalStateException("测试篡改目标不存在")
      finally statement.close()
    finally connection.close()
  }
