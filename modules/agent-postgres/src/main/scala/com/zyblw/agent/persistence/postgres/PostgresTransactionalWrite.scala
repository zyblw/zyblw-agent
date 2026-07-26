package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.sideeffects.*
import com.zyblw.agent.tools.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, SQLException}
import java.util.UUID
import javax.sql.DataSource
import scala.annotation.unused
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 业务项目为一个可靠写工具实现的 PostgreSQL mutation。
  *
  * 所有方法都在 JDBC blocking executor 上运行；`mutate` 只能使用传入 Connection 访问与框架 outbox 相同的 PostgreSQL 数据库，禁止执行
  * HTTP、模型、对象存储或消息发送。返回后框架会在同一 transaction 中写入业务幂等结果、outbox 和 可选补偿计划。
  *
  * @tparam I
  *   已由工具 JSON codec 校验的输入
  * @tparam O
  *   对模型安全、大小受限并可持久化重放的输出
  */
trait PostgresBusinessMutation[I, O]:
  /** 稳定业务操作名；发布后视作唯一约束和审计协议的一部分。 */
  def operationName: String

  /** 从业务输入构造跨 Run 稳定的幂等键。
    *
    * @param input
    *   已校验输入
    * @param context
    *   Runtime 提供的可信 run/thread/user/tenant 上下文
    */
  def idempotencyKey(input: I, context: ToolExecutionContext): Either[AgentError, BusinessIdempotencyKey]

  /** 在当前事务 Connection 上执行真正的业务 mutation。
    *
    * @param connection
    *   与 `agent_business_operations`、`agent_outbox_events` 完全相同的 JDBC Connection
    * @param input
    *   已校验输入
    * @param context
    *   可信调用上下文；业务仍必须校验资源归属，不能只依赖模型参数
    * @return
    *   成功业务输出或类型化错误；失败会回滚业务写、幂等记录、outbox 和补偿计划
    */
  def mutate(connection: Connection, input: I, context: ToolExecutionContext): Either[AgentError, O]

  /** 根据已经成功的 mutation 输出生成需要事务提交后发送的事件。
    * @return
    *   按稳定 ordinal 排列的事件；不得在此执行发送
    */
  def outbox(
      @unused output: O,
      @unused context: ToolExecutionContext
  ): Either[AgentError, Chunk[OutboxEventDraft]] =
    Right(Chunk.empty)

  /** 为不可逆工作流注册一个确定的补偿计划。 返回 None 表示业务通过自身幂等更新即可恢复；返回计划也只会 Registered，不会自动执行。
    */
  def compensation(
      @unused output: O,
      @unused context: ToolExecutionContext
  ): Either[AgentError, Option[CompensationDraft]] =
    Right(None)

/** 是否允许没有 tenant/user 的全局写入。安全默认拒绝匿名全局作用域。 */
final case class PostgresTransactionalWriteConfig(allowGlobalScope: Boolean = false)

/** 在一个 PostgreSQL transaction 中执行“业务 mutation + 幂等结果 + outbox + 补偿计划”。
  *
  * 该服务是可靠性的强制入口：`PostgresReliableWriteTool` 会把它捕获为 ZIO 依赖，普通工具无法通过事后调用 `OutboxStore` 获得同样保证。
  */
final class PostgresTransactionalWriteExecutor(
    dataSource: DataSource,
    config: PostgresTransactionalWriteConfig = PostgresTransactionalWriteConfig()
):
  /** 执行或幂等复用一次业务 mutation。
    *
    * @param input
    *   已校验业务输入
    * @param context
    *   Runtime 可信执行上下文
    * @param mutation
    *   业务项目实现的窄 mutation
    * @tparam I
    *   输入类型，必须具有确定性 JSON 编码用于 request hash
    * @tparam O
    *   输出类型，必须可编码持久化并可解码恢复
    * @return
    *   首次执行结果或同幂等键已提交结果；不同正文复用同键会失败
    */
  def execute[I: JsonEncoder, O: JsonCodec](
      input: I,
      context: ToolExecutionContext,
      mutation: PostgresBusinessMutation[I, O]
  ): IO[AgentError, O] =
    for
      _     <- validateOperationName(mutation.operationName)
      key   <- ZIO.fromEither(mutation.idempotencyKey(input, context))
      scope <- ZIO.fromEither(scopeKey(context.runContext))
      requestJson = input.toJson
      requestHash = sha256(requestJson)
      candidateId <- BusinessOperationId.random
      output      <- withConnection { connection =>
        ZIO.attemptBlocking {
          transaction(connection) {
            executeInTransaction(
              connection,
              candidateId,
              scope,
              key,
              requestHash,
              input,
              context,
              mutation
            )
          }
        }
      }
    yield output

  /** 校验操作名，避免把动态用户文本放进唯一键和指标标签。 */
  private def validateOperationName(name: String): IO[AgentError.InvalidConfiguration, Unit] =
    val normalized = name.trim
    ZIO.cond(
      normalized.nonEmpty && normalized.length <= 200,
      (),
      AgentError.InvalidConfiguration("PostgresBusinessMutation.operationName 必须为 1-200 个字符")
    )

  /** 生成租户优先、用户次之的隔离键；匿名写入默认拒绝。 */
  private def scopeKey(context: RunContext): Either[AgentError, String] =
    context.tenantId
      .map(value => s"tenant:$value")
      .orElse(context.userId.map(value => s"user:$value"))
      .orElse(Option.when(config.allowGlobalScope)("global"))
      .toRight(AgentError.PermissionDenied("transactional-write", "可靠写工具必须绑定 tenantId 或 userId"))

  /** 在已经关闭 autoCommit 的 Connection 中执行幂等仲裁与业务写。 `INSERT ... ON CONFLICT DO NOTHING` 和随后 `FOR UPDATE`
    * 让并发相同请求只会有一个执行 mutation。
    */
  private def executeInTransaction[I, O: JsonCodec](
      connection: Connection,
      candidateId: BusinessOperationId,
      scope: String,
      key: BusinessIdempotencyKey,
      requestHash: String,
      input: I,
      context: ToolExecutionContext,
      mutation: PostgresBusinessMutation[I, O]
  ): O =
    val inserted =
      insertOperation(connection, candidateId, scope, key, requestHash, context, mutation.operationName)
    val existing = loadOperationForUpdate(connection, scope, mutation.operationName, key)
    if existing.requestHash != requestHash then
      throw BusinessFailure(
        AgentError.BusinessIdempotencyConflict(mutation.operationName, key.value)
      )
    else if !inserted then decodeStoredResult[O](mutation.operationName, existing.resultJson)
    else
      val output =
        mutation.mutate(connection, input, context).fold(error => throw BusinessFailure(error), identity)
      val events = mutation.outbox(output, context).fold(error => throw BusinessFailure(error), identity)
      val compensation =
        mutation.compensation(output, context).fold(error => throw BusinessFailure(error), identity)
      insertOutbox(connection, existing.operationId, context, scope, events)
      compensation.foreach(insertCompensation(connection, existing.operationId, context, scope, _))
      val resultJson = output.toJson
      completeOperation(connection, existing.operationId, resultJson)
      output

  /** 插入执行占位；冲突时不覆盖原 request hash 或结果。 */
  private def insertOperation(
      connection: Connection,
      operationId: BusinessOperationId,
      scope: String,
      key: BusinessIdempotencyKey,
      requestHash: String,
      context: ToolExecutionContext,
      operationName: String
  ): Boolean =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_business_operations
        |(operation_id, scope_key, operation_name, idempotency_key, request_hash, status, run_id, tool_call_id, created_at)
        |VALUES (?::uuid, ?, ?, ?, ?, 'Executing', ?::uuid, ?, CURRENT_TIMESTAMP)
        |ON CONFLICT (scope_key, operation_name, idempotency_key) DO NOTHING""".stripMargin
    )
    try
      statement.setString(1, operationId.asString)
      statement.setString(2, scope)
      statement.setString(3, operationName)
      statement.setString(4, key.value)
      statement.setString(5, requestHash)
      statement.setString(6, context.runId.asString)
      statement.setString(7, context.callId)
      statement.executeUpdate() == 1
    finally statement.close()

  /** 锁定同幂等键操作；并发请求会等待首次事务提交，然后复用其结果。 */
  private def loadOperationForUpdate(
      connection: Connection,
      scope: String,
      operationName: String,
      key: BusinessIdempotencyKey
  ): StoredOperation =
    val statement = connection.prepareStatement(
      """SELECT operation_id::text, request_hash, status, result_json::text
        |FROM agent_business_operations
        |WHERE scope_key = ? AND operation_name = ? AND idempotency_key = ?
        |FOR UPDATE""".stripMargin
    )
    try
      statement.setString(1, scope)
      statement.setString(2, operationName)
      statement.setString(3, key.value)
      val result = statement.executeQuery()
      if !result.next() then throw IllegalStateException("幂等操作插入或读取失败")
      val id = BusinessOperationId
        .fromString(result.getString(1))
        .fold(error => throw IllegalStateException(error), identity)
      StoredOperation(id, result.getString(2), result.getString(3), Option(result.getString(4)))
    finally statement.close()

  /** 复用已提交结果；Executing 不可能独立提交，其他无结果状态视作损坏。 */
  private def decodeStoredResult[O: JsonDecoder](operationName: String, resultJson: Option[String]): O =
    resultJson
      .toRight(AgentError.PersistenceFailure(s"业务操作 $operationName 已存在但缺少可重放结果"))
      .flatMap(
        _.fromJson[O].left.map(error => AgentError.PersistenceFailure(s"业务操作 $operationName 结果无法解码: $error"))
      )
      .fold(error => throw BusinessFailure(error), identity)

  /** 按 ordinal 插入 outbox；事件 ID 由 operationId+ordinal 确定生成，恢复时保持稳定。 */
  private def insertOutbox(
      connection: Connection,
      operationId: BusinessOperationId,
      context: ToolExecutionContext,
      scope: String,
      events: Chunk[OutboxEventDraft]
  ): Unit =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_outbox_events
        |(event_id, operation_id, run_id, tool_call_id, scope_key, ordinal, destination, event_type,
        | aggregate_type, aggregate_id, partition_key, payload, headers, status, available_at, created_at)
        |VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'Pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        |ON CONFLICT (operation_id, ordinal) DO NOTHING""".stripMargin
    )
    try
      events.zipWithIndex.foreach { case (event, ordinal) =>
        val eventId = deterministicUuid(s"${operationId.asString}:outbox:$ordinal")
        statement.setString(1, eventId.toString)
        statement.setString(2, operationId.asString)
        statement.setString(3, context.runId.asString)
        statement.setString(4, context.callId)
        statement.setString(5, scope)
        statement.setInt(6, ordinal)
        statement.setString(7, event.destination)
        statement.setString(8, event.eventType)
        statement.setString(9, event.aggregateType)
        statement.setString(10, event.aggregateId)
        statement.setString(11, event.partitionKey)
        statement.setString(12, event.payload.toJson)
        statement.setString(13, event.headers.toJson)
        statement.addBatch()
      }
      if events.nonEmpty then
        val _ = statement.executeBatch()
    finally statement.close()

  /** 将补偿计划注册为 Registered；它不会因为原事务成功就自动执行。 */
  private def insertCompensation(
      connection: Connection,
      operationId: BusinessOperationId,
      context: ToolExecutionContext,
      scope: String,
      draft: CompensationDraft
  ): Unit =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_compensations
        |(compensation_id, operation_id, run_id, scope_key, handler_name, payload, status, available_at, created_at)
        |VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::jsonb, 'Registered', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        |ON CONFLICT (operation_id) DO NOTHING""".stripMargin
    )
    try
      statement.setString(1, deterministicUuid(s"${operationId.asString}:compensation").toString)
      statement.setString(2, operationId.asString)
      statement.setString(3, context.runId.asString)
      statement.setString(4, scope)
      statement.setString(5, draft.handlerName)
      statement.setString(6, draft.payload.toJson)
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 保存可安全返回模型的成功结果，并把操作推进到 Succeeded。 */
  private def completeOperation(
      connection: Connection,
      operationId: BusinessOperationId,
      resultJson: String
  ): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_business_operations
        |SET status = 'Succeeded', result_json = ?::jsonb, completed_at = CURRENT_TIMESTAMP
        |WHERE operation_id = ?::uuid AND status = 'Executing'""".stripMargin
    )
    try
      statement.setString(1, resultJson)
      statement.setString(2, operationId.asString)
      if statement.executeUpdate() != 1 then throw IllegalStateException("业务操作完成状态推进失败")
    finally statement.close()

  /** 把 callback 包装成 all-or-nothing transaction，并保证任何异常都 rollback。 */
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

  /** 从宿主共享 DataSource 借连接，Scope 结束时确定归还；不创建隐藏连接池。 */
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
        case BusinessFailure(error) => error
        case sql: SQLException      => databaseError("执行 PostgreSQL 事务写工具失败", sql)
        case error                  => AgentError.PersistenceFailure("执行 PostgreSQL 事务写工具失败", Some(error))
      }

  /** SHA-256 只保存请求指纹，不把完整输入复制到幂等表和日志。 */
  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  /** 用稳定名称生成 UUID，确保同 operation/ordinal 重放仍得到相同 messageId。 */
  private def deterministicUuid(value: String): UUID =
    UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))

  /** 将 SQLSTATE 分类为可重试或永久存储错误。 */
  private def databaseError(operation: String, sql: SQLException): StoreError =
    val state     = Option(sql.getSQLState).getOrElse("unknown")
    val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
    AgentError.DatabaseFailure(operation, state, retryable, Some(sql))

object PostgresTransactionalWriteExecutor:
  /** 使用宿主 DataSource 构造可靠写执行器；必须与业务 repository 使用同一数据库。 */
  val layer: URLayer[DataSource, PostgresTransactionalWriteExecutor] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresTransactionalWriteExecutor(dataSource))

  /** 允许宿主显式配置是否开放全局作用域；生产业务通常应保持 false。 */
  def layer(
      config: PostgresTransactionalWriteConfig
  ): URLayer[DataSource, PostgresTransactionalWriteExecutor] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresTransactionalWriteExecutor(dataSource, config))

/** 构造只能经 PostgreSQL 同事务协议执行的类型化工具。
  *
  * 工厂会强制把 sideEffect 设置为 `TransactionalOutboxWrite`；业务不能传入普通 `Tool.json` 后仅靠元数据声称可靠。
  */
object PostgresReliableWriteTool:
  /** 创建可靠写工具。
    *
    * @param name
    *   工具稳定名称，必须与 mutation.operationName 一致
    * @param description
    *   给模型看的窄能力说明
    * @param inputSchema
    *   严格输入 JSON Schema
    * @param outputSchema
    *   可选输出 Schema
    * @param metadata
    *   风险、scope、脱敏与冲突组；sideEffect 会被工厂覆盖
    * @param mutation
    *   同事务业务 mutation
    */
  def make[I: JsonCodec, O: JsonCodec](
      name: ToolName,
      description: String,
      inputSchema: Json.Obj,
      outputSchema: Option[Json.Obj],
      metadata: ToolMetadata,
      mutation: PostgresBusinessMutation[I, O]
  ): Either[AgentError.InvalidConfiguration, Tool[PostgresTransactionalWriteExecutor, I, AgentError, O]] =
    if name.value != mutation.operationName then
      Left(
        AgentError.InvalidConfiguration(
          s"可靠写工具名 ${name.value} 必须等于 mutation.operationName ${mutation.operationName}"
        )
      )
    else
      Right(
        Tool.json[PostgresTransactionalWriteExecutor, I, AgentError, O](
          name,
          description,
          inputSchema,
          outputSchema,
          metadata.copy(sideEffect = SideEffect.TransactionalOutboxWrite)
        )((input, context) =>
          ZIO.serviceWithZIO[PostgresTransactionalWriteExecutor](_.execute(input, context, mutation))
        )
      )

final private case class StoredOperation(
    operationId: BusinessOperationId,
    requestHash: String,
    status: String,
    resultJson: Option[String]
)
final private case class BusinessFailure(error: AgentError) extends RuntimeException(error.message)
