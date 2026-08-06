package com.zyblw.agent.admin

import java.time.Instant

/** keyset 游标中时间戳的精度契约。
  *
  * 管理面目录按 `(updatedAt DESC, id DESC)` 排序并用游标续页。游标里的时间戳精度**必须不低于**排序列的精度， 否则翻页会静默丢行：`TIMESTAMPTZ`
  * 保存到微秒，而一个只记录毫秒的游标在被截断后严格小于同一毫秒内所有实际 时间戳，行值比较会把整个毫秒区间连同游标行本身一起排除在下一页之外。这类缺陷只在时间戳带亚毫秒位时出现，
  * 因此用毫秒精度的测试夹具永远无法复现。
  *
  * 因此游标统一使用微秒——与 PostgreSQL `TIMESTAMPTZ` 的存储精度相同，使行值比较精确且仍然走普通索引。展示用 字段（如
  * `RunSummaryView.updatedAtEpochMilli`）保持毫秒，它们不参与排序。
  */
object CursorTime:
  /** Epoch 微秒；纳秒被截断，与 `Instant` 自身的截断方向一致。 */
  def epochMicro(instant: Instant): Long =
    instant.getEpochSecond * 1_000_000L + instant.getNano / 1_000L

  /** 还原为 `Instant`；用 floorDiv/floorMod 使 epoch 之前的时间戳也保持单调。 */
  def toInstant(epochMicro: Long): Instant =
    Instant.ofEpochSecond(
      Math.floorDiv(epochMicro, 1_000_000L),
      Math.floorMod(epochMicro, 1_000_000L) * 1_000L
    )
