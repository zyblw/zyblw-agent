package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.sideeffects.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, SQLException}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL 下游 inbox 消费器。
  *
  * 它把 `(consumerName, messageId)` 去重记录、consumer 业务 mutation 和可重放结果放在同一个 transaction 中。仅在处理前 查询
  * inbox、随后另开业务事务仍有崩溃窗口，不属于本实现保证。
  */
final class PostgresTransactionalInbox(dataSource: DataSource):
  /** 首次消费消息或复用已经提交的结果。
    *
    * @param consumer
    *   稳定业务消费者名称；不能使用 pod/进程实例名
    * @param message
    *   上游 outbox 信封；messageId 在发布重试期间必须不变
    * @param handler
    *   使用同一 JDBC Connection 修改下游业务状态的同步函数；不得在事务中访问网络
    * @tparam O
    *   可持久化并可在重复投递时恢复的安全结果类型
    * @return
    *   value 与是否命中重复消息
    */
  def consume[O: JsonCodec](
      consumer: InboxConsumerName,
      message: InboxMessage
  )(handler: (Connection, InboxMessage) => Either[AgentError, O]): IO[AgentError, InboxConsumeResult[O]] =
    val messageHash = sha256(message.toJson)
    withConnection { connection =>
      ZIO.attemptBlocking {
        transaction(connection) {
          val inserted = insertInbox(connection, consumer, message, messageHash)
          val existing = loadForUpdate(connection, consumer, message.messageId)
          if existing.messageHash != messageHash then
            throw InboxBusinessFailure(
              AgentError.InboxMessageConflict(consumer.value, message.messageId.asString)
            )
          else if !inserted then
            val value = existing.resultJson
              .toRight(AgentError.PersistenceFailure(s"inbox ${message.messageId.asString} 缺少可重放结果"))
              .flatMap(
                _.fromJson[O].left.map(error => AgentError.PersistenceFailure(s"inbox 结果无法解码: $error"))
              )
              .fold(error => throw InboxBusinessFailure(error), identity)
            InboxConsumeResult(value, duplicate = true)
          else
            val value =
              handler(connection, message).fold(error => throw InboxBusinessFailure(error), identity)
            completeInbox(connection, consumer, message.messageId, value.toJson)
            InboxConsumeResult(value, duplicate = false)
        }
      }
    }

  /** 插入消费占位；并发重复投递通过唯一约束收敛。 */
  private def insertInbox(
      connection: Connection,
      consumer: InboxConsumerName,
      message: InboxMessage,
      messageHash: String
  ): Boolean =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_inbox_messages
        |(consumer_name, message_id, event_type, message_hash, status, received_at)
        |VALUES (?, ?::uuid, ?, ?, 'Processing', CURRENT_TIMESTAMP)
        |ON CONFLICT (consumer_name, message_id) DO NOTHING""".stripMargin
    )
    try
      statement.setString(1, consumer.value)
      statement.setString(2, message.messageId.asString)
      statement.setString(3, message.eventType)
      statement.setString(4, messageHash)
      statement.executeUpdate() == 1
    finally statement.close()

  /** 锁住当前 consumer/messageId；等待并发首次消费者提交后再判断是否复用。 */
  private def loadForUpdate(
      connection: Connection,
      consumer: InboxConsumerName,
      messageId: OutboxEventId
  ): StoredInbox =
    val statement = connection.prepareStatement(
      """SELECT message_hash, status, result_json::text
        |FROM agent_inbox_messages
        |WHERE consumer_name = ? AND message_id = ?::uuid
        |FOR UPDATE""".stripMargin
    )
    try
      statement.setString(1, consumer.value)
      statement.setString(2, messageId.asString)
      val result = statement.executeQuery()
      if !result.next() then throw IllegalStateException("inbox 插入或读取失败")
      StoredInbox(result.getString(1), result.getString(2), Option(result.getString(3)))
    finally statement.close()

  /** 保存 handler 结果；此更新与 handler 对业务表的修改由同一 commit 原子确认。 */
  private def completeInbox(
      connection: Connection,
      consumer: InboxConsumerName,
      messageId: OutboxEventId,
      resultJson: String
  ): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_inbox_messages
        |SET status = 'Succeeded', result_json = ?::jsonb, processed_at = CURRENT_TIMESTAMP
        |WHERE consumer_name = ? AND message_id = ?::uuid AND status = 'Processing'""".stripMargin
    )
    try
      statement.setString(1, resultJson)
      statement.setString(2, consumer.value)
      statement.setString(3, messageId.asString)
      if statement.executeUpdate() != 1 then throw IllegalStateException("inbox 完成状态推进失败")
    finally statement.close()

  /** 执行最小 all-or-nothing transaction。 */
  private def transaction[A](connection: Connection)(body: => A): A =
    connection.setAutoCommit(false)
    try
      val value = body
      connection.commit()
      value
    catch
      case error: Throwable =>
        try connection.rollback()
        catch case rollbackError: Throwable => error.addSuppressed(rollbackError)
        throw error
    finally connection.setAutoCommit(true)

  /** 从宿主共享池借连接，并把 JDBC 阻塞移到 blocking executor。 */
  private def withConnection[A](use: Connection => Task[A]): IO[AgentError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection))(connection =>
            ZIO.attemptBlocking(connection.close()).orDie
          )
          .flatMap(use)
      }
      .mapError {
        case InboxBusinessFailure(error) => error
        case sql: SQLException           =>
          val state     = Option(sql.getSQLState).getOrElse("unknown")
          val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
          AgentError.DatabaseFailure("执行 PostgreSQL inbox 消费失败", state, retryable, Some(sql))
        case error => AgentError.PersistenceFailure("执行 PostgreSQL inbox 消费失败", Some(error))
      }

  /** 对完整消息信封取 SHA-256，检测上游错误复用 messageId。 */
  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

object PostgresTransactionalInbox:
  /** 使用宿主 DataSource 构造 inbox；consumer 业务表必须在同一个数据库中。 */
  val layer: URLayer[DataSource, PostgresTransactionalInbox] =
    ZLayer.fromFunction((dataSource: DataSource) => new PostgresTransactionalInbox(dataSource))

final private case class StoredInbox(messageHash: String, status: String, resultJson: Option[String])
final private case class InboxBusinessFailure(error: AgentError) extends RuntimeException(error.message)
