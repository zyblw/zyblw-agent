package com.zyblw.agent.sideeffects

import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 验证 publisher/compensation worker 的 Fiber 生命周期和错误分类，不依赖真实数据库。 */
object SideEffectWorkersSpec extends ZIOSpecDefault:
  private val now   = Instant.parse("2026-01-01T00:00:00Z")
  private val owner = SideEffectWorkerId
    .fromString("side-effect-test-worker")
    .fold(error => throw IllegalArgumentException(error), identity)
  private val token          = SideEffectLeaseToken(UUID.fromString("00000000-0000-0000-0000-000000000010"))
  private val operationId    = BusinessOperationId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
  private val runId          = RunId(UUID.fromString("00000000-0000-0000-0000-000000000030"))
  private val eventId        = OutboxEventId(UUID.fromString("00000000-0000-0000-0000-000000000040"))
  private val compensationId = CompensationId(UUID.fromString("00000000-0000-0000-0000-000000000050"))

  private val event = OutboxEventRecord(
    eventId,
    operationId,
    runId,
    "call-1",
    "user:user-1",
    0,
    OutboxEventDraft("events", "record.created.v1", "record", "1", "1", Json.Obj()),
    OutboxStatus.Publishing,
    1,
    1L,
    now,
    None,
    now,
    None
  )
  private val outboxLease = OutboxLease(event, owner, token, 1L, now, now.plusSeconds(30))

  private val compensation = CompensationRecord(
    compensationId,
    operationId,
    runId,
    "user:user-1",
    CompensationDraft("undo-record-v1", Json.Obj("id" -> Json.Str("1"))),
    CompensationStatus.Running,
    1,
    1L,
    now,
    None,
    now,
    None
  )
  private val compensationLease = CompensationLease(compensation, owner, token, 1L, now, now.plusSeconds(60))

  def spec = suite("side-effect workers")(
    test("OutboxPublisher 发送成功后 fenced 标记 Published") {
      for
        action <- Ref.make("none")
        store     = outboxStore(action)
        transport = new OutboxTransport:
          def publish(value: OutboxEventRecord): IO[AgentError, Unit] =
            ZIO.fail(AgentError.Unexpected("messageId 漂移")).unless(value.eventId == eventId).unit
        publisher = OutboxPublisher(
          store,
          transport,
          owner,
          OutboxPublisherConfig(heartbeatInterval = 5.seconds)
        )
        count  <- publisher.runOnce
        result <- action.get
      yield assertTrue(count == 1, result == "published")
    },
    test("OutboxPublisher 对 retryable 错误 abandon，对永久错误 DeadLetter") {
      for
        retryAction <- Ref.make("none")
        retryTransport = new OutboxTransport:
          def publish(_event: OutboxEventRecord): IO[AgentError, Unit] =
            ZIO.fail(AgentError.ModelFailure("test", "temporary", retryable = true))
        _ <- OutboxPublisher(outboxStore(retryAction), retryTransport, owner, OutboxPublisherConfig()).runOnce
        retryResult     <- retryAction.get
        permanentAction <- Ref.make("none")
        permanentTransport = new OutboxTransport:
          def publish(_event: OutboxEventRecord): IO[AgentError, Unit] =
            ZIO.fail(AgentError.ToolExecutionFailed("transport", "permanent", retryable = false))
        _ <- OutboxPublisher(
          outboxStore(permanentAction),
          permanentTransport,
          owner,
          OutboxPublisherConfig()
        ).runOnce
        permanentResult <- permanentAction.get
      yield assertTrue(retryResult == "abandoned", permanentResult == "dead-letter")
    },
    test("CompensationRegistry 拒绝重复名称，Worker 只执行已注册 handler 并完成 lease") {
      val handler = new CompensationHandler:
        val name                                                         = "undo-record-v1"
        def compensate(record: CompensationRecord): IO[AgentError, Unit] =
          ZIO.fail(AgentError.Unexpected("错误补偿记录")).unless(record.compensationId == compensationId).unit
      for
        duplicate <- CompensationRegistry.make(List(handler, handler)).exit
        registry  <- CompensationRegistry.make(List(handler))
        action    <- Ref.make("none")
        worker = CompensationWorker(compensationStore(action), registry, owner, CompensationWorkerConfig())
        count  <- worker.runOnce
        result <- action.get
      yield assertTrue(duplicate.isFailure, count == 1, result == "completed")
    },
    test("补偿 Worker 失去 lease 后不再用陈旧 generation 写重试或死信状态") {
      val staleHandler = new CompensationHandler:
        val name                                                          = "undo-record-v1"
        def compensate(_record: CompensationRecord): IO[AgentError, Unit] =
          ZIO.fail(
            AgentError.CompensationLeaseLost(
              compensationId.asString,
              owner.value,
              compensationLease.generation
            )
          )
      for
        registry <- CompensationRegistry.make(List(staleHandler))
        action   <- Ref.make("none")
        worker = CompensationWorker(compensationStore(action), registry, owner, CompensationWorkerConfig())
        count  <- worker.runOnce
        result <- action.get
      yield assertTrue(count == 1, result == "none")
    }
  )

  /** 创建只返回一条固定 lease 的 outbox 测试 Store。 */
  private def outboxStore(action: Ref[String]): OutboxStore = new OutboxStore:
    def claim(_owner: SideEffectWorkerId, _batchSize: Int, _leaseDuration: Duration, _maxAttempts: Int) =
      ZIO.succeed(Chunk(outboxLease))
    def heartbeat(_lease: OutboxLease, _extendBy: Duration) = ZIO.succeed(now.plusSeconds(30))
    def markPublished(_lease: OutboxLease)                  = action.set("published")
    def abandon(_lease: OutboxLease, _safeFailure: String, _availableAt: Instant) = action.set("abandoned")
    def deadLetter(_lease: OutboxLease, _safeFailure: String)                     = action.set("dead-letter")
    def get(_eventId: OutboxEventId)                                              = ZIO.succeed(event)
    def list(_operationId: BusinessOperationId)                                   = ZIO.succeed(Chunk(event))

  /** 创建只返回一条固定 lease 的补偿测试 Store。 */
  private def compensationStore(action: Ref[String]): CompensationStore = new CompensationStore:
    def activate(_id: CompensationId, _availableAt: Instant) = ZIO.succeed(compensation)
    def cancel(_id: CompensationId) = ZIO.succeed(compensation.copy(status = CompensationStatus.Cancelled))
    def claim(_owner: SideEffectWorkerId, _batchSize: Int, _leaseDuration: Duration, _maxAttempts: Int) =
      ZIO.succeed(Chunk(compensationLease))
    def heartbeat(_lease: CompensationLease, _extendBy: Duration) = ZIO.succeed(now.plusSeconds(60))
    def complete(_lease: CompensationLease)                       = action.set("completed")
    def abandon(_lease: CompensationLease, _safeFailure: String, _availableAt: Instant) =
      action.set("abandoned")
    def deadLetter(_lease: CompensationLease, _safeFailure: String) = action.set("dead-letter")
    def get(_id: CompensationId)                                    = ZIO.succeed(compensation)
