package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.sql.{Connection, SQLTransientConnectionException}
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.test.*

/** 连接池耗尽契约：DataSource 获取连接失败必须变成 typed、retryable StoreError，不能成为 defect 或永久挂起。 真实池的等待上限由宿主 HikariCP/PgBouncer
  * 配置；框架 adapter 不私自创建第二个连接池。
  */
object PostgresConnectionExhaustionSpec extends ZIOSpecDefault:
  /** 模拟已经等到 connectionTimeout 后仍无可用连接的 DataSource。 */
  private val exhaustedDataSource = new PGSimpleDataSource:
    override def getConnection: Connection =
      throw SQLTransientConnectionException("pool exhausted", "08001")
    override def getConnection(user: String, password: String): Connection = getConnection

  def spec = suite("PostgreSQL 连接池耗尽")(
    test("RunStore 映射为可重试持久化错误") {
      for
        runId <- RunId.random
        exit  <- PostgresRunStore(exhaustedDataSource).load(runId).exit
        failure = exit match
          case Exit.Failure(cause) => cause.failureOption
          case Exit.Success(_)     => None
      yield assertTrue(
        failure.exists(_.isInstanceOf[StoreError]),
        failure.exists(_.retryable),
        failure.exists(_.category == ErrorCategory.Persistence)
      )
    },
    test("EvalTrendStore 保留 SQLSTATE 与可重试语义") {
      val identity = EvalTrendIdentity(
        EvalSuiteKind.Agent,
        "connection-exhaustion-suite",
        "connection-exhaustion-dataset",
        "v1"
      )
      for
        exit <- PostgresEvalTrendStore(exhaustedDataSource).latestPassing(identity).exit
        failure = exit match
          case Exit.Failure(cause) => cause.failureOption
          case Exit.Success(_)     => None
      yield assertTrue(
        failure.exists(_.isInstanceOf[AgentError.DatabaseFailure]),
        failure.exists(_.retryable),
        failure.exists(_.diagnostic.get("sqlState").contains("08001")),
        failure.exists(_.category == ErrorCategory.Persistence)
      )
    }
  )
