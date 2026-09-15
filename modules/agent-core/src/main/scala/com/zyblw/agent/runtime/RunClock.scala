package com.zyblw.agent.runtime

import java.time.Instant
import zio.*

/** Runtime 内部的单一时间读取点。
  *
  * 状态与内核只使用 `Instant`；事件与耐久账本使用 epoch milli。两者混用曾让同一次转换里出现 `Clock.instant` 与
  * `Clock.currentTime(MILLISECONDS)` 两次独立读取——它们可以落在不同毫秒上，于是状态时间与事件时间不一致。
  *
  * 这里的约定是：一次转换只读一次时间，并在需要时把同一个 `Instant` 转成 milli，绝不反向由 milli 还原 `Instant`。
  */
private[agent] object RunClock:
  /** 读取一次时间；同一次状态转换内应复用该值。 */
  val instant: UIO[Instant] = Clock.instant

  /** 只需要事件时间戳时的便捷形式；与 [[instant]] 同源。 */
  val millis: UIO[Long] = Clock.instant.map(_.toEpochMilli)
