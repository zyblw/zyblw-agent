package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 验证 retention 的有限批处理、错误分类重试和结构化取消。 */
object MemoryRetentionWorkerSpec extends ZIOSpecDefault:

  /** 测试只关心 purgeExpired，因此其余 MemoryStore API 返回空结果。把所有方法显式实现，可以保证接口扩展时测试会 编译失败并提醒维护者更新 stub，而不是通过动态 mock
    * 静默漏测。
    */
  final private class PurgeStub(run: (Long, Int) => IO[StoreError, Long]) extends MemoryStore:
    def put(scope: MemoryScope, entry: MemoryEntry): UIO[Unit] = ZIO.unit
    def compareAndSet(
        scope: MemoryScope,
        expectedVersion: Long,
        entry: MemoryEntry
    ): IO[StoreError, MemoryEntry] =
      ZIO.succeed(entry.copy(version = expectedVersion + 1L))
    def get(scope: MemoryScope, key: String): UIO[Option[MemoryEntry]]                 = ZIO.none
    def search(scope: MemoryScope, query: String, limit: Int): UIO[Chunk[MemoryEntry]] =
      ZIO.succeed(Chunk.empty)
    def list(scope: MemoryScope, limit: Int): UIO[Chunk[MemoryEntry]]       = ZIO.succeed(Chunk.empty)
    def delete(scope: MemoryScope, key: String): UIO[Unit]                  = ZIO.unit
    def deleteScope(scope: MemoryScope): UIO[Long]                          = ZIO.succeed(0L)
    def purgeExpired(nowEpochMilli: Long, limit: Int): IO[StoreError, Long] = run(nowEpochMilli, limit)

  private val noopObserver = new MemoryRetentionObserver:
    def completed(report: MemoryRetentionReport): UIO[Unit]            = ZIO.unit
    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] = ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Memory retention worker")(
    test("每批都满时严格遵守 maxBatchesPerCycle，避免积压任务独占连接池") {
      for
        calls <- Ref.make(0)
        store  = PurgeStub((_, limit) => calls.updateAndGet(_ + 1).as(limit.toLong))
        worker = MemoryRetentionWorker(
          store,
          noopObserver,
          MemoryRetentionConfig(batchSize = 2, maxBatchesPerCycle = 3, maxRetries = 0)
        )
        report <- worker.runOnce
        count  <- calls.get
      yield assertTrue(
        count == 3,
        report.batches == 3,
        report.purgedRows == 6L,
        report.reachedCycleLimit
      )
    },
    test("不足一批立即结束，整轮所有批次共享同一个 cutoff") {
      for
        replies <- Ref.make(List(2L, 1L))
        cutoffs <- Ref.make(Chunk.empty[Long])
        store = PurgeStub((cutoff, _) =>
          cutoffs.update(_ :+ cutoff) *>
            replies.modify {
              case head :: tail => head -> tail
              case Nil          => 0L   -> Nil
            }
        )
        worker = MemoryRetentionWorker(
          store,
          noopObserver,
          MemoryRetentionConfig(batchSize = 2, maxBatchesPerCycle = 10, maxRetries = 0)
        )
        report <- worker.runOnce
        seen   <- cutoffs.get
      yield assertTrue(
        report.batches == 2,
        report.purgedRows == 3L,
        !report.reachedCycleLimit,
        seen.length == 2,
        seen.distinct.length == 1,
        seen.head == report.cutoff.toEpochMilli
      )
    },
    test("瞬时错误按 Schedule 重试，永久错误不重试") {
      for
        retryCalls <- Ref.make(0)
        transientStore = PurgeStub((_, _) =>
          retryCalls.updateAndGet(_ + 1).flatMap {
            case attempt if attempt < 3 => ZIO.fail(AgentError.PersistenceFailure("temporary"))
            case _                      => ZIO.succeed(0L)
          }
        )
        transientWorker = MemoryRetentionWorker(
          transientStore,
          noopObserver,
          MemoryRetentionConfig(
            batchSize = 10,
            maxBatchesPerCycle = 1,
            retryInitialDelay = 1.second,
            maxRetries = 3
          )
        )
        fiber           <- transientWorker.runOnce.fork
        _               <- TestClock.adjust(10.seconds)
        transientResult <- fiber.join
        transientCount  <- retryCalls.get
        permanentCalls  <- Ref.make(0)
        permanentStore = PurgeStub((_, _) =>
          permanentCalls.updateAndGet(_ + 1) *>
            ZIO.fail(AgentError.MemoryPolicyRejected("retention", "permanent"))
        )
        permanentWorker = MemoryRetentionWorker(
          permanentStore,
          noopObserver,
          MemoryRetentionConfig(maxRetries = 5, retryInitialDelay = 1.second)
        )
        permanentResult <- permanentWorker.runOnce.exit
        permanentCount  <- permanentCalls.get
      yield assertTrue(
        transientResult.purgedRows == 0L,
        transientCount == 3,
        permanentResult.isFailure,
        permanentCount == 1
      )
    },
    test("startScoped 返回的后台 Fiber 可被结构化中断，不遗留 daemon 清理任务") {
      val worker = MemoryRetentionWorker(
        PurgeStub((_, _) => ZIO.succeed(0L)),
        noopObserver,
        MemoryRetentionConfig(interval = 1.hour, maxRetries = 0)
      )
      for
        fiber <- worker.startScoped
        exit  <- fiber.interrupt
      yield assertTrue(exit.isInterrupted)
    }
  )
