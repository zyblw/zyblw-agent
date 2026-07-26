package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*

/** 一次异步 Run 创建所需的全部不可分割事实。
  *
  * 这里没有保存原始认证凭据。`submissionScopeHash` 是由 Runtime 对“租户、用户、Agent”规范化后计算的 SHA-256， `requestHash` 则绑定 Agent
  * 定义、输入、线程、预算和可信 RunContext。两个哈希共同保证：HTTP 重试可以返回原回执， 但同一个客户端幂等键绝不能悄悄改成另一份请求。
  *
  * @param state
  *   状态为 Created 的完整初始 AgentState；它已经包含冻结的 AgentDefinition 和可信 RunContext
  * @param createdEvent
  *   sequence=0 的 RunCreated 事件，必须与 state 的 runId/sessionId 完全一致
  * @param submissionScopeHash
  *   认证主体与 Agent 组成的 64 位十六进制 SHA-256
  * @param idempotencyKey
  *   客户端稳定键；网络重试必须复用，建议使用 UUID
  * @param requestHash
  *   请求语义的 64 位十六进制 SHA-256，不包含随机 runId 和创建时间
  */
final case class RunStartSubmission(
    state: AgentState,
    createdEvent: PersistedAgentEvent,
    submissionScopeHash: String,
    idempotencyKey: String,
    requestHash: String
):
  require(state.status == RunStatus.Created, "异步创建只能提交 Created 状态")
  require(
    createdEvent.runId == state.runId && createdEvent.sequence == 0L,
    "RunCreated 必须属于目标 Run 且 sequence=0"
  )
  require(submissionScopeHash.matches("[0-9a-f]{64}"), "submissionScopeHash 必须是 SHA-256 十六进制")
  require(requestHash.matches("[0-9a-f]{64}"), "requestHash 必须是 SHA-256 十六进制")
  require(idempotencyKey.trim.nonEmpty && idempotencyKey.length <= 200, "idempotencyKey 长度必须为 1..200")

/** “初始状态 + 首事件 + Start 命令 + dispatcher”原子提交 SPI。
  *
  * 不能用 `RunStore.createWithEvents` 后再调用 `RunCommandStore.submit` 来冒充该契约：进程可能在两次提交之间退出， 留下永远没有命令的孤儿 Run。生产
  * Adapter 必须在同一个数据库事务中写入四类记录；如果任意一步失败，全部回滚。
  */
trait RunSubmissionStore:
  /** 幂等提交一个新 Run。
    *
    * @param submission
    *   已完成结构和哈希校验的创建事实
    * @return
    *   新建 Start 命令，或同作用域、同键、同请求指纹对应的既有 Start 命令
    */
  def submitStart(submission: RunStartSubmission): IO[StoreError, RunCommandRecord]

object RunSubmissionStore:
  /** 单进程开发与测试实现。
    *
    * 内存进程退出时状态、命令和幂等索引会一起消失，因此不存在跨重启半提交；运行期间则以 `Ref.Synchronized` 串行化 同一提交键，并用不可中断区连接现有
    * RunStore/RunCommandStore。真正的掉电事务保证由 PostgreSQL Adapter 提供。
    */
  val inMemory: URLayer[RunStore & RunCommandStore, RunSubmissionStore] = ZLayer.fromZIO {
    for
      runs     <- ZIO.service[RunStore]
      commands <- ZIO.service[RunCommandStore]
      index    <- Ref.Synchronized.make(Map.empty[(String, String), (String, RunCommandRecord)])
    yield new RunSubmissionStore:
      def submitStart(submission: RunStartSubmission): IO[StoreError, RunCommandRecord] =
        validate(submission) *> index.modifyZIO { current =>
          val key = submission.submissionScopeHash -> submission.idempotencyKey
          current.get(key) match
            case Some((knownHash, record)) if knownHash == submission.requestHash =>
              ZIO.succeed(record -> current)
            case Some(_) =>
              ZIO.fail(AgentError.RunSubmissionConflict(submission.idempotencyKey))
            case None =>
              val create = for
                _      <- runs.createWithEvents(submission.state, NonEmptyChunk(submission.createdEvent))
                record <- commands
                  .submit(
                    submission.state.runId,
                    RunCommandPayload.Start,
                    idempotencyKey = "start",
                    priority = 0
                  )
                  .onError(_ => runs.delete(submission.state.runId).ignore)
              yield record
              ZIO.uninterruptible(create).map { record =>
                record -> current.updated(key, submission.requestHash -> record)
              }
        }
  }

  /** 在 Adapter 边界重复校验事件批次，防止绕过 Runtime 直接构造不一致提交。 */
  private def validate(submission: RunStartSubmission): IO[StoreError, Unit] =
    RunStore.validateEventBatch(
      submission.state,
      NonEmptyChunk(submission.createdEvent),
      requireStartAtZero = true
    )
