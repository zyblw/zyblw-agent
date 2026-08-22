package com.zyblw.agent.examples

import com.zyblw.agent.workflow.WorkflowWakeQueueSnapshot
import java.time.Instant
import zio.*
import zio.test.*

object WorkflowWakeWorkerSoakProbeSpec extends ZIOSpecDefault:
  def spec = suite("WorkflowWakeWorkerSoakProbe")(
    test("soak 至少同时满足轮数和持续时间") {
      val config = WorkflowWakeWorkerSoakConfig.smoke.copy(
        minimumRounds = 3,
        minimumDuration = 10.seconds,
        timeout = 20.seconds
      )

      assertTrue(
        config.shouldContinue(completedRounds = 2, elapsed = 12.seconds),
        config.shouldContinue(completedRounds = 3, elapsed = 9.seconds),
        !config.shouldContinue(completedRounds = 3, elapsed = 10.seconds)
      )
    },
    test("Worker 数量和超时边界在构造期 fail-fast") {
      val invalidWorkers = scala.util.Try(WorkflowWakeWorkerSoakConfig.smoke.copy(workerCount = 33))
      val invalidTimeout = scala.util.Try(
        WorkflowWakeWorkerSoakConfig.smoke.copy(
          minimumDuration = 10.seconds,
          timeout = 10.seconds
        )
      )

      assertTrue(invalidWorkers.isFailure, invalidTimeout.isFailure)
    },
    test("正式 wake queue 快照只聚合进低敏高水位") {
      val high = WorkflowSoakQueueHighWatermarks()
        .observe(WorkflowWakeQueueSnapshot(Instant.EPOCH, 4L, 1L, 3L, 2L, 1L, Some(25L)))
        .observe(WorkflowWakeQueueSnapshot(Instant.EPOCH, 2L, 0L, 1L, 3L, 0L, Some(10L)))

      assertTrue(
        high.pendingWaits == 4L,
        high.dueWaits == 1L,
        high.dispatchableWakeups == 3L,
        high.leasedWakeups == 3L,
        high.expiredWakeLeases == 1L,
        high.oldestDispatchableAgeMillis == 25L
      )
    }
  )
