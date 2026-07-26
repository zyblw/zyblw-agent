package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunStore
import com.zyblw.agent.sideeffects.*
import java.sql.Connection
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 真实 PostgreSQL 16 可靠副作用契约。
  *
  * 这些测试不能由内存 Map 或 H2 取代，因为它们验证的正是 transaction rollback、唯一约束等待、`SKIP LOCKED`、 TIMESTAMPTZ 和 lease fencing。
  */
object PostgresSideEffectIntegrationSpec extends ZIOSpecDefault:
  final private case class Services(
      dataSource: DataSource,
      runStore: RunStore,
      writes: PostgresTransactionalWriteExecutor,
      outbox: OutboxStore,
      inbox: PostgresTransactionalInbox,
      compensations: CompensationStore
  )

  final private case class WriteInput(recordId: String, value: String) derives JsonCodec
  final private case class WriteOutput(recordId: String, value: String) derives JsonCodec
  final private case class ConsumeOutput(applied: Int) derives JsonCodec

  /** 启动真库、执行唯一最新基线，并创建仅供测试的业务表。 */
  private val servicesLayer: ZLayer[Any, Throwable, Services] = ZLayer.scoped {
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
      _ <- ZIO.attemptBlocking(
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(AgentPostgresMigrations.DefaultLocation)
          .load()
          .migrate()
      )
      _ <- ZIO.attemptBlocking {
        val connection = dataSource.getConnection
        try
          val statement = connection.createStatement()
          try
            statement.execute(
              """CREATE TABLE test_business_records (
                     |  record_id TEXT PRIMARY KEY,
                     |  value TEXT NOT NULL,
                     |  mutation_count INTEGER NOT NULL DEFAULT 1
                     |)""".stripMargin
            )
            statement.execute(
              """CREATE TABLE test_consumer_projection (
                     |  projection_id TEXT PRIMARY KEY,
                     |  applied_count INTEGER NOT NULL
                     |)""".stripMargin
            )
          finally statement.close()
        finally connection.close()
      }
    yield Services(
      dataSource,
      PostgresRunStore(dataSource),
      new PostgresTransactionalWriteExecutor(dataSource),
      new PostgresOutboxStore(dataSource),
      new PostgresTransactionalInbox(dataSource),
      new PostgresCompensationStore(dataSource)
    )
  }

  /** 创建最小 Run，保持 side-effect 审计中的 runId 对应真实 AgentState。 */
  private def createRun(store: RunStore): UIO[RunId] =
    (for
      runId   <- RunId.random
      session <- SessionId.random
      eventId <- EventId.random
      now     <- Clock.instant
      state = AgentState(
        runId,
        session,
        AgentId("side-effect-pg-test"),
        RunStatus.Running,
        Chunk.empty,
        Chunk.empty,
        UsageSummary(),
        BudgetState(RunLimits(), UsageSummary(), 0),
        None,
        now,
        now,
        Version.initial,
        runContext = RunContext(userId = Some("user-1")),
        lastEventSequence = 0L
      )
      event = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, session, now.toEpochMilli),
        now.toEpochMilli
      )
      _ <- store.createWithEvents(state, NonEmptyChunk(event))
    yield runId).orDie

  /** 生成真实写工具使用的可信执行上下文。 */
  private def context(runId: RunId, callId: String): ToolExecutionContext =
    ToolExecutionContext(runId, ThreadId("side-effect-thread"), callId, RunContext(userId = Some("user-1")))

  /** 正常 mutation：插入业务行，并生成一个 outbox 与一个 Registered 补偿计划。 */
  private val successfulMutation = new PostgresBusinessMutation[WriteInput, WriteOutput]:
    val operationName = "test.write-record"

    def idempotencyKey(
        input: WriteInput,
        _context: ToolExecutionContext
    ): Either[AgentError, BusinessIdempotencyKey] =
      BusinessIdempotencyKey
        .fromString(s"record:${input.recordId}")
        .left
        .map(error => AgentError.ToolInputInvalid(operationName, error))

    def mutate(
        connection: Connection,
        input: WriteInput,
        _context: ToolExecutionContext
    ): Either[AgentError, WriteOutput] =
      val statement = connection.prepareStatement(
        "INSERT INTO test_business_records(record_id, value) VALUES (?, ?)"
      )
      try
        statement.setString(1, input.recordId)
        statement.setString(2, input.value)
        statement.executeUpdate()
        Right(WriteOutput(input.recordId, input.value))
      catch case error: Throwable => Left(AgentError.PersistenceFailure("测试业务 mutation 失败", Some(error)))
      finally statement.close()

    override def outbox(
        output: WriteOutput,
        _context: ToolExecutionContext
    ): Either[AgentError, Chunk[OutboxEventDraft]] =
      Right(
        Chunk(
          OutboxEventDraft(
            "test-events",
            "test.record.created.v1",
            "test-record",
            output.recordId,
            output.recordId,
            Json.Obj("recordId" -> Json.Str(output.recordId), "value" -> Json.Str(output.value))
          )
        )
      )

    override def compensation(
        output: WriteOutput,
        _context: ToolExecutionContext
    ): Either[AgentError, Option[CompensationDraft]] =
      Right(
        Some(CompensationDraft("delete-test-record-v1", Json.Obj("recordId" -> Json.Str(output.recordId))))
      )

  /** 先写业务表再返回失败，用来证明整个 transaction 会回滚。 */
  private val failingMutation = new PostgresBusinessMutation[WriteInput, WriteOutput]:
    val operationName = "test.failing-write"

    def idempotencyKey(
        input: WriteInput,
        _context: ToolExecutionContext
    ): Either[AgentError, BusinessIdempotencyKey] =
      BusinessIdempotencyKey
        .fromString(s"fail:${input.recordId}")
        .left
        .map(error => AgentError.ToolInputInvalid(operationName, error))

    def mutate(
        connection: Connection,
        input: WriteInput,
        _context: ToolExecutionContext
    ): Either[AgentError, WriteOutput] =
      val statement =
        connection.prepareStatement("INSERT INTO test_business_records(record_id, value) VALUES (?, ?)")
      try
        statement.setString(1, input.recordId)
        statement.setString(2, input.value)
        statement.executeUpdate()
        Left(AgentError.ToolExecutionFailed(operationName, "intentional-test-failure"))
      finally statement.close()

  def spec = suite("PostgreSQL transactional side effects")(
    test("业务 mutation、幂等结果、outbox 与补偿计划同事务提交并可重复调用") {
      (for
        services <- ZIO.service[Services]
        runId    <- createRun(services.runStore)
        input = WriteInput("record-1", "first")
        first       <- services.writes.execute(input, context(runId, "call-1"), successfulMutation)
        replay      <- services.writes.execute(input, context(runId, "call-2"), successfulMutation)
        counts      <- queryCounts(services.dataSource, "record-1")
        operationId <- queryOperationId(
          services.dataSource,
          successfulMutation.operationName,
          "record:record-1"
        )
        events       <- services.outbox.list(operationId)
        compensation <- queryCompensation(services.dataSource, operationId)
        conflict     <- services.writes
          .execute(WriteInput("record-1", "different"), context(runId, "call-3"), successfulMutation)
          .exit
      yield assertTrue(
        first == WriteOutput("record-1", "first"),
        replay == first,
        counts == (1, 1, 1, 1),
        events.length == 1,
        events.head.status == OutboxStatus.Pending,
        compensation.status == CompensationStatus.Registered,
        conflict.causeOption
          .flatMap(_.failureOption)
          .exists(_.isInstanceOf[AgentError.BusinessIdempotencyConflict])
      )).provideLayer(servicesLayer)
    },
    test("mutation 在返回错误前已经写 SQL 时，业务行、幂等记录和 outbox 全部回滚") {
      (for
        services <- ZIO.service[Services]
        runId    <- createRun(services.runStore)
        failed   <- services.writes
          .execute(WriteInput("rollback-1", "value"), context(runId, "call-fail"), failingMutation)
          .exit
        counts <- queryCounts(services.dataSource, "rollback-1")
      yield assertTrue(failed.isFailure, counts == (0, 0, 0, 0))).provideLayer(servicesLayer)
    },
    test("发布成功确认窗口崩溃后保持相同 eventId 重发，旧 generation 不能迟到完成") {
      (for
        services <- ZIO.service[Services]
        runId    <- createRun(services.runStore)
        _        <- services.writes
          .execute(WriteInput("outbox-1", "value"), context(runId, "call-outbox"), successfulMutation)
        owner1 <- ZIO
          .fromEither(SideEffectWorkerId.fromString("publisher-old"))
          .mapError(new IllegalArgumentException(_))
          .orDie
        owner2 <- ZIO
          .fromEither(SideEffectWorkerId.fromString("publisher-new"))
          .mapError(new IllegalArgumentException(_))
          .orDie
        first <- services.outbox
          .claim(owner1, 1, 200.millis, 3)
          .flatMap(_.headOption match
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(AgentError.Unexpected("first outbox claim missing")))
        _      <- ZIO.sleep(300.millis)
        second <- services.outbox
          .claim(owner2, 1, 5.seconds, 3)
          .flatMap(_.headOption match
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(AgentError.Unexpected("second outbox claim missing")))
        stale  <- services.outbox.markPublished(first).exit
        _      <- services.outbox.markPublished(second)
        stored <- services.outbox.get(second.event.eventId)
      yield assertTrue(
        second.event.eventId == first.event.eventId,
        second.generation == first.generation + 1L,
        stale.isFailure,
        stored.status == OutboxStatus.Published,
        stored.attempt == 2
      )).provideLayer(servicesLayer)
    },
    test("inbox 去重与 consumer 业务 mutation 同事务，重复投递不再次修改业务表") {
      (for
        services  <- ZIO.service[Services]
        messageId <- OutboxEventId.random
        now       <- Clock.instant
        consumer  <- ZIO
          .fromEither(InboxConsumerName.fromString("test-projection-v1"))
          .mapError(new IllegalArgumentException(_))
          .orDie
        message = InboxMessage(
          messageId,
          "test.record.created.v1",
          Json.Obj("id" -> Json.Str("projection-1")),
          Map.empty,
          now
        )
        handler = (connection: Connection, _: InboxMessage) =>
          val statement = connection.prepareStatement(
            """INSERT INTO test_consumer_projection(projection_id, applied_count) VALUES ('projection-1', 1)
                        |ON CONFLICT (projection_id) DO UPDATE SET applied_count = test_consumer_projection.applied_count + 1
                        |RETURNING applied_count""".stripMargin
          )
          try
            val result = statement.executeQuery()
            result.next()
            Right(ConsumeOutput(result.getInt(1)))
          finally statement.close()
        first   <- services.inbox.consume(consumer, message)(handler)
        replay  <- services.inbox.consume(consumer, message)(handler)
        applied <- queryInt(
          services.dataSource,
          "SELECT applied_count FROM test_consumer_projection WHERE projection_id = 'projection-1'"
        )
        conflict <- services.inbox
          .consume(consumer, message.copy(payload = Json.Obj("id" -> Json.Str("different"))))(handler)
          .exit
      yield assertTrue(
        first == InboxConsumeResult(ConsumeOutput(1), duplicate = false),
        replay == InboxConsumeResult(ConsumeOutput(1), duplicate = true),
        applied == 1,
        conflict.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.InboxMessageConflict])
      )).provideLayer(servicesLayer)
    },
    test("补偿计划默认 Registered，显式激活后才可 claim 并以 fencing 完成") {
      (for
        services <- ZIO.service[Services]
        runId    <- createRun(services.runStore)
        _        <- services.writes.execute(
          WriteInput("compensation-1", "value"),
          context(runId, "call-compensation"),
          successfulMutation
        )
        operationId <- queryOperationId(
          services.dataSource,
          successfulMutation.operationName,
          "record:compensation-1"
        )
        registered <- queryCompensation(services.dataSource, operationId)
        owner      <- ZIO
          .fromEither(SideEffectWorkerId.fromString("compensation-worker-old"))
          .mapError(new IllegalArgumentException(_))
          .orDie
        newOwner <- ZIO
          .fromEither(SideEffectWorkerId.fromString("compensation-worker-new"))
          .mapError(new IllegalArgumentException(_))
          .orDie
        before    <- services.compensations.claim(owner, 1, 5.seconds, 3)
        activated <- services.compensations.activate(registered.compensationId, registered.availableAt)
        lease     <- services.compensations
          .claim(owner, 1, 200.millis, 3)
          .flatMap(_.headOption match
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(AgentError.Unexpected("compensation claim missing")))
        _           <- ZIO.sleep(300.millis)
        replacement <- services.compensations
          .claim(newOwner, 1, 5.seconds, 3)
          .flatMap(_.headOption match
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(AgentError.Unexpected("replacement compensation claim missing")))
        stale     <- services.compensations.complete(lease).exit
        _         <- services.compensations.complete(replacement)
        completed <- services.compensations.get(registered.compensationId)
        invalid   <- services.compensations.cancel(registered.compensationId).exit
      yield assertTrue(
        registered.status == CompensationStatus.Registered,
        before.isEmpty,
        activated.status == CompensationStatus.Pending,
        lease.record.compensationId == registered.compensationId,
        replacement.generation == lease.generation + 1L,
        stale.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.CompensationLeaseLost]),
        completed.status == CompensationStatus.Succeeded,
        invalid.isFailure
      )).provideLayer(servicesLayer)
    }
  ) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.withLiveClock @@
    TestAspect.timeout(3.minutes) @@ TestAspect.sequential

  /** 查询目标业务行及三个可靠性表的数量。 */
  private def queryCounts(dataSource: DataSource, recordId: String): Task[(Int, Int, Int, Int)] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val business =
          count(connection, "SELECT count(*) FROM test_business_records WHERE record_id = ?", recordId)
        val operation = count(
          connection,
          "SELECT count(*) FROM agent_business_operations WHERE idempotency_key IN (?, ?)",
          s"record:$recordId",
          s"fail:$recordId"
        )
        val outbox =
          count(connection, "SELECT count(*) FROM agent_outbox_events WHERE aggregate_id = ?", recordId)
        val compensation = count(
          connection,
          """SELECT count(*) FROM agent_compensations c
          |JOIN agent_business_operations o ON o.operation_id = c.operation_id
          |WHERE o.idempotency_key IN (?, ?)""".stripMargin,
          s"record:$recordId",
          s"fail:$recordId"
        )
        (business, operation, outbox, compensation)
      finally connection.close()
    }

  /** 可变参数计数查询辅助方法。 */
  private def count(connection: Connection, sql: String, values: String*): Int =
    val statement = connection.prepareStatement(sql)
    try
      values.zipWithIndex.foreach { case (value, index) => statement.setString(index + 1, value) }
      val result = statement.executeQuery()
      result.next()
      result.getInt(1)
    finally statement.close()

  /** 查询业务操作 ID。 */
  private def queryOperationId(
      dataSource: DataSource,
      operationName: String,
      idempotencyKey: String
  ): Task[BusinessOperationId] = ZIO.attemptBlocking {
    val connection = dataSource.getConnection
    try
      val statement = connection.prepareStatement(
        "SELECT operation_id::text FROM agent_business_operations WHERE operation_name = ? AND idempotency_key = ?"
      )
      try
        statement.setString(1, operationName)
        statement.setString(2, idempotencyKey)
        val result = statement.executeQuery()
        if !result.next() then throw IllegalStateException("operation missing")
        BusinessOperationId
          .fromString(result.getString(1))
          .fold(error => throw IllegalStateException(error), identity)
      finally statement.close()
    finally connection.close()
  }

  /** 读取 operation 注册的补偿计划。 */
  private def queryCompensation(
      dataSource: DataSource,
      operationId: BusinessOperationId
  ): Task[CompensationRecord] =
    for
      id <- ZIO.attemptBlocking {
        val connection = dataSource.getConnection
        try
          val statement = connection.prepareStatement(
            "SELECT compensation_id::text FROM agent_compensations WHERE operation_id = ?::uuid"
          )
          try
            statement.setString(1, operationId.asString)
            val result = statement.executeQuery()
            if !result.next() then throw IllegalStateException("compensation missing")
            CompensationId
              .fromString(result.getString(1))
              .fold(error => throw IllegalStateException(error), identity)
          finally statement.close()
        finally connection.close()
      }
      services = new PostgresCompensationStore(dataSource)
      record <- services.get(id)
    yield record

  /** 查询单个整数结果。 */
  private def queryInt(dataSource: DataSource, sql: String): Task[Int] = ZIO.attemptBlocking {
    val connection = dataSource.getConnection
    try
      val statement = connection.createStatement()
      try
        val result = statement.executeQuery(sql)
        if !result.next() then throw IllegalStateException("integer result missing")
        result.getInt(1)
      finally statement.close()
    finally connection.close()
  }
