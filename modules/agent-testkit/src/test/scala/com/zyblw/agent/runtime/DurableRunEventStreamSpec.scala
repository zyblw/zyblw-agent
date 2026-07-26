package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.stream.*
import zio.test.*

/** 验证跨节点耐久事件流的分页、轮询、终止和损坏检测语义。
  *
  * 测试使用 TestClock，不等待真实墙钟；这正是把轮询建模为 ZIO effect 而不是 Thread.sleep 的价值。
  */
object DurableRunEventStreamSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.randomUUID())
  private val sessionId = SessionId(UUID.randomUUID())
  private val now       = Instant.parse("2026-01-01T00:00:00Z")

  /** 按给定终态和 last sequence 创建最小权威状态。 */
  private def state(status: RunStatus, lastSequence: Long): AgentState =
    AgentState(
      runId = runId,
      sessionId = sessionId,
      agentId = AgentId("durable-stream-test"),
      status = status,
      messages = Chunk(AgentMessage.user("test")),
      steps = Chunk.empty,
      usage = UsageSummary(),
      budget = BudgetState(RunLimits(), UsageSummary(), 0),
      pendingApproval = None,
      createdAt = now,
      updatedAt = now,
      version = Version.initial,
      threadId = Some(ThreadId("durable-stream")),
      lastEventSequence = lastSequence
    )

  /** 创建只含序号差异的安全事件，便于精确验证分页游标。 */
  private def event(sequence: Long): PersistedAgentEvent =
    PersistedAgentEvent(
      EventId(UUID.randomUUID()),
      runId,
      sequence,
      AgentEvent.StepStarted(runId, sequence.toInt, now.toEpochMilli),
      now.toEpochMilli
    )

  /** 可变只读 Runtime 替身；所有状态推进方法都失败，从而证明 DurableRunEventStream 只依赖 inspect/persistedEvents。
    */
  final private class StubRuntime(
      currentState: Ref[AgentState],
      stored: Ref[Chunk[PersistedAgentEvent]],
      cursors: Ref[Chunk[Long]],
      queried: Promise[Nothing, Unit],
      forcedFirstPage: Ref[Option[Chunk[PersistedAgentEvent]]]
  ) extends AgentRuntime:
    private def unexpected[A](name: String): IO[AgentError, A] =
      ZIO.fail(AgentError.Unexpected(s"耐久事件测试不应调用 $name"))

    def run(agent: AgentDefinition, request: RunRequest): IO[AgentError, RunOutcome] = unexpected("run")
    def resume(id: RunId, decision: ApprovalDecision): IO[AgentError, RunOutcome]    = unexpected("resume")
    def recover(id: RunId): IO[AgentError, RunOutcome]                               = unexpected("recover")
    def runEvents(agent: AgentDefinition, request: RunRequest): ZStream[Any, AgentError, AgentEvent] =
      ZStream.fail(AgentError.Unexpected("runEvents"))
    def resumeEvents(id: RunId, decision: ApprovalDecision): ZStream[Any, AgentError, AgentEvent] =
      ZStream.fail(AgentError.Unexpected("resumeEvents"))
    def recoverEvents(id: RunId): ZStream[Any, AgentError, AgentEvent] =
      ZStream.fail(AgentError.Unexpected("recoverEvents"))
    def cancel(id: RunId): IO[AgentError, Unit]        = unexpected("cancel")
    def inspect(id: RunId): IO[AgentError, AgentState] =
      if id == runId then currentState.get else ZIO.fail(AgentError.RunNotFound(id))
    def persistedEvents(
        id: RunId,
        afterSequence: Long,
        limit: Int
    ): IO[AgentError, Chunk[PersistedAgentEvent]] =
      if id != runId then ZIO.fail(AgentError.RunNotFound(id))
      else
        cursors.update(_ :+ afterSequence) *>
          queried.succeed(()).unit *>
          forcedFirstPage.getAndSet(None).flatMap {
            case Some(page) => ZIO.succeed(page)
            case None       => stored.get.map(_.filter(_.sequence > afterSequence).take(limit))
          }

  /** 构造 Stub 与其可变观测句柄。
    * @param forcedFirstPage
    *   可选的第一次事件查询结果，用于确定性制造两次只读查询之间的 TOCTOU 场景
    */
  private def fixture(
      initial: AgentState,
      initialEvents: Chunk[PersistedAgentEvent],
      forcedFirstPage: Option[Chunk[PersistedAgentEvent]] = None
  ) =
    for
      stateRef <- Ref.make(initial)
      eventRef <- Ref.make(initialEvents)
      cursors  <- Ref.make(Chunk.empty[Long])
      queried  <- Promise.make[Nothing, Unit]
      forced   <- Ref.make(forcedFirstPage)
    yield (StubRuntime(stateRef, eventRef, cursors, queried, forced), stateRef, eventRef, cursors, queried)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DurableRunEventStream")(
    test("按 batchSize 分页并在终态最后序号追平后结束") {
      for
        values <- fixture(state(RunStatus.Completed, 4L), Chunk.fromIterable(0L.to(4L).map(event)))
        stream = DurableRunEventStream.make(values._1, DurableRunEventStreamConfig(100.millis, batchSize = 2))
        result <- stream.events(runId).runCollect
        seen   <- values._4.get
      yield assertTrue(
        result.map(_.sequence) == Chunk(0L, 1L, 2L, 3L, 4L),
        seen == Chunk(-1L, 1L, 3L, 4L)
      )
    },
    test("运行中无新事件时使用 TestClock 轮询，随后从同一游标发出新提交") {
      for
        values <- fixture(state(RunStatus.Running, 0L), Chunk(event(0L)))
        stream = DurableRunEventStream.make(values._1, DurableRunEventStreamConfig(500.millis, batchSize = 8))
        fiber  <- stream.events(runId, afterSequence = 0L).take(1).runHead.fork
        _      <- values._5.await
        _      <- values._3.update(_ :+ event(1L))
        _      <- values._2.set(state(RunStatus.Completed, 1L))
        _      <- TestClock.adjust(500.millis)
        result <- fiber.join
      yield assertTrue(result.exists(_.sequence == 1L))
    },
    test("事件查询与状态查询之间提交时重读事件，不把正常 TOCTOU 竞态误判为缺口") {
      for
        values <- fixture(
          state(RunStatus.Completed, 1L),
          Chunk(event(0L), event(1L)),
          forcedFirstPage = Some(Chunk.empty)
        )
        stream = DurableRunEventStream.make(values._1, DurableRunEventStreamConfig(100.millis, batchSize = 8))
        result <- stream.events(runId, afterSequence = 0L).runCollect
        seen   <- values._4.get
      yield assertTrue(result.map(_.sequence) == Chunk(1L), seen == Chunk(0L, 0L, 1L))
    },
    test("事件页存在 sequence 缺口时 fail-closed 而不是让客户端漏过状态转换") {
      for
        values <- fixture(state(RunStatus.Completed, 2L), Chunk(event(0L), event(2L)))
        stream = DurableRunEventStream.make(values._1, DurableRunEventStreamConfig(100.millis, batchSize = 8))
        result <- stream.events(runId).runCollect.either
      yield assertTrue(result.left.exists(_.category == ErrorCategory.Persistence))
    },
    test("客户端游标超过权威 lastEventSequence 时立即拒绝") {
      for
        values <- fixture(state(RunStatus.Completed, 1L), Chunk(event(0L), event(1L)))
        stream = DurableRunEventStream.make(values._1)
        result <- stream.events(runId, afterSequence = 9L).runCollect.either
      yield assertTrue(result.left.exists(_.category == ErrorCategory.Configuration))
    }
  )
