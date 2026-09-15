package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.json.JsonCodec

/** 挂起到期索引的权威状态。
  *
  * 这张索引**不保存挂起正文**：`ApprovalRequest`、prompt 与 signal 名都留在 `AgentState.suspension` 里。Worker 只凭 kind / deadline
  * / 决议动作发现并排他领取到期项，决议本身必须经 `RunCommitter` 写回状态。
  */
enum SuspensionIndexStatus derives JsonCodec:
  case Pending, Expired, Resolved

/** 已到期挂起的排他处置租约。 */
final case class SuspensionLease(
    runId: RunId,
    kind: String,
    expiryOutcome: SuspensionExpiry,
    owner: WorkerId,
    token: LeaseToken,
    generation: Long,
    leaseExpiresAt: Instant
) derives JsonCodec:
  require(kind.nonEmpty, "SuspensionLease.kind 不能为空")
  require(generation > 0L, "SuspensionLease.generation 必须大于零")

/** 挂起到期索引 SPI。
  *
  * 生产实现与 `RunStore` 共享同一事务：状态提交时按 `AgentState.suspension` 同步本索引，避免"状态已挂起但没有到期行"或相反的双写窗口。
  */
trait SuspensionStore:
  /** 把已过 deadline 且仍为 Pending 的行原子标为 Expired，返回本次决议的 runId。 */
  def expireDue(limit: Int): IO[StoreError, Chunk[RunId]]

  /** 排他领取已 Expired、租约空闲或已过期的行。 */
  def claimExpired(
      owner: WorkerId,
      leaseDuration: Duration,
      limit: Int = 1
  ): IO[StoreError, Chunk[SuspensionLease]]

  /** 仅在 owner/token/generation 匹配且租约未过期时续租。 */
  def heartbeat(lease: SuspensionLease, leaseDuration: Duration): IO[StoreError, SuspensionLease]

  /** 可重试失败后释放租约，并推迟下一次可领取时间。 */
  def abandon(lease: SuspensionLease, availableAt: Instant): IO[StoreError, Unit]

  /** 处置完成后把行标为 Resolved；Run 删除时由外键/级联清理。 */
  def resolve(lease: SuspensionLease): IO[StoreError, Unit]
