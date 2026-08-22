package com.zyblw.agent.examples

import zio.*
import zio.test.*

object DurableWorkerSoakProbeSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("DurableWorkerSoakProbe")(
    test("延迟直方图使用保守 10ms 桶并计算 nearest-rank 分位数") {
      val histogram = Chunk(0L, 1L, 9L, 10L, 11L, 50L, 100L, 101L, 1000L, 2000L)
        .foldLeft(SoakLatencyHistogram.empty)(_.observe(_))
        .summary
      assertTrue(
        histogram.samples == 10L,
        histogram.minimumMillis == 0L,
        histogram.p50Millis == 20L,
        histogram.p95Millis == 2000L,
        histogram.p99Millis == 2000L,
        histogram.maximumMillis == 2000L,
        histogram.meanMillis == 328L,
        histogram.bucketWidthMillis == 10
      )
    },
    test("soak 至少同时满足轮数和持续时间") {
      val config = DurableWorkerSoakConfig.smoke
      assertTrue(
        config.shouldContinue(config.minimumRounds - 1, config.minimumDuration),
        config.shouldContinue(
          config.minimumRounds,
          Duration.fromNanos(config.minimumDuration.toNanos - 1L)
        ),
        !config.shouldContinue(config.minimumRounds, config.minimumDuration)
      )
    },
    test("队列高水位只保留低敏聚合最大值") {
      val first = com.zyblw.agent.memory.RunCommandQueueSnapshot(
        java.time.Instant.EPOCH,
        queuedCommands = 10L,
        dispatchableRuns = 4L,
        leasedRuns = 2L,
        expiredLeases = 0L,
        deadLetterCommands = 0L,
        oldestDispatchableAgeMillis = Some(30L)
      )
      val second = first.copy(
        queuedCommands = 2L,
        dispatchableRuns = 1L,
        leasedRuns = 6L,
        expiredLeases = 1L,
        oldestDispatchableAgeMillis = Some(20L)
      )
      val result = SoakQueueHighWatermarks().observe(first).observe(second)
      assertTrue(
        result.queuedCommands == 10L,
        result.dispatchableRuns == 4L,
        result.leasedRuns == 6L,
        result.expiredLeases == 1L,
        result.deadLetterCommands == 0L,
        result.oldestDispatchableAgeMillis == 30L
      )
    }
  )
