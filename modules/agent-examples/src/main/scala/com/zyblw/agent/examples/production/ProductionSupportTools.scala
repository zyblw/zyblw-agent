package com.zyblw.agent.examples.production

import com.zyblw.agent.core.*
import com.zyblw.agent.persistence.postgres.{
  PostgresBusinessMutation,
  PostgresReliableWriteTool,
  PostgresTransactionalWriteExecutor
}
import com.zyblw.agent.sideeffects.{BusinessIdempotencyKey, OutboxEventDraft}
import com.zyblw.agent.tools.*
import java.sql.Connection
import javax.sql.DataSource
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 客服场景的只读查询与审批后退款工具。SQL 固定，模型参数不能变成语句。 */
object ProductionSupportTools:
  final case class LookupOrderInput(orderId: String) derives JsonCodec
  final case class LookupOrderOutput(orderId: String, status: String, amountCents: Long) derives JsonCodec
  final case class IssueRefundInput(orderId: String, refundId: String, amountCents: Long) derives JsonCodec
  final case class IssueRefundOutput(refundId: String, accepted: Boolean) derives JsonCodec

  val lookupSchema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "orderId" -> Json.Obj("type" -> Json.Str("string"), "description" -> Json.Str("已授权订单号"))
    ),
    "required"             -> Json.Arr(Json.Str("orderId")),
    "additionalProperties" -> Json.Bool(false)
  )

  val refundSchema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "orderId"     -> Json.Obj("type" -> Json.Str("string")),
      "refundId"    -> Json.Obj("type" -> Json.Str("string")),
      "amountCents" -> Json.Obj("type" -> Json.Str("integer"))
    ),
    "required"             -> Json.Arr(Json.Str("orderId"), Json.Str("refundId"), Json.Str("amountCents")),
    "additionalProperties" -> Json.Bool(false)
  )

  def lookupOrder(
      dataSource: DataSource
  ): Tool[Any, LookupOrderInput, AgentError.ToolExecutionFailed, LookupOrderOutput] =
    Tool.json[Any, LookupOrderInput, AgentError.ToolExecutionFailed, LookupOrderOutput](
      ToolName("lookup_order"),
      "Read one authorized customer order. The SQL is fixed; model arguments never become SQL.",
      lookupSchema,
      None,
      ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
    ) { (input, context) =>
      ZIO
        .attemptBlocking {
          val connection = dataSource.getConnection
          try
            val statement = connection.prepareStatement(
              """SELECT order_id, status, amount_cents
              |FROM support_orders
              |WHERE order_id = ? AND tenant_id = ?""".stripMargin
            )
            try
              statement.setString(1, input.orderId)
              statement.setString(2, context.runContext.tenantId.getOrElse(""))
              val result = statement.executeQuery()
              try
                if !result.next() then throw IllegalStateException("order not found in tenant scope")
                LookupOrderOutput(result.getString(1), result.getString(2), result.getLong(3))
              finally result.close()
            finally statement.close()
          finally connection.close()
        }
        .mapError(error => AgentError.ToolExecutionFailed("lookup_order", error.getClass.getSimpleName))
    }

  def inMemoryLookup: Tool[Any, LookupOrderInput, Nothing, LookupOrderOutput] =
    Tool.json[Any, LookupOrderInput, Nothing, LookupOrderOutput](
      ToolName("lookup_order"),
      "Read one authorized customer order in the contract fixture.",
      lookupSchema,
      None,
      ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
    )((input, _) => ZIO.succeed(LookupOrderOutput(input.orderId, "paid", 2599L)))

  def inMemoryRefund: Tool[Any, IssueRefundInput, Nothing, IssueRefundOutput] =
    Tool.json[Any, IssueRefundInput, Nothing, IssueRefundOutput](
      ToolName("issue_refund"),
      "Issue a customer refund after human approval. The business idempotency key is order plus refund id.",
      refundSchema,
      None,
      ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.IdempotentWrite)
    )((input, _) => ZIO.succeed(IssueRefundOutput(input.refundId, accepted = true)))

  def durableRefund: Either[AgentError.InvalidConfiguration, Tool[
    PostgresTransactionalWriteExecutor,
    IssueRefundInput,
    AgentError,
    IssueRefundOutput
  ]] =
    PostgresReliableWriteTool.make(
      ToolName("issue_refund"),
      "Issue a customer refund after human approval. The mutation, idempotency row and outbox share one PostgreSQL transaction.",
      refundSchema,
      None,
      ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.TransactionalOutboxWrite),
      IssueRefundMutation
    )

  /** 框架核心迁移之外的业务表；由参考宿主的 migrate 命令创建，不进入 Flyway 核心 history。 */
  val businessSchemaSql: String =
    """
      |CREATE TABLE IF NOT EXISTS support_orders (
      |  tenant_id TEXT NOT NULL,
      |  order_id TEXT NOT NULL,
      |  status TEXT NOT NULL,
      |  amount_cents BIGINT NOT NULL CHECK (amount_cents >= 0),
      |  PRIMARY KEY (tenant_id, order_id)
      |);
      |CREATE TABLE IF NOT EXISTS support_refunds (
      |  tenant_id TEXT NOT NULL,
      |  refund_id TEXT NOT NULL,
      |  order_id TEXT NOT NULL,
      |  amount_cents BIGINT NOT NULL CHECK (amount_cents >= 0),
      |  PRIMARY KEY (tenant_id, refund_id)
      |);
      |INSERT INTO support_orders(tenant_id, order_id, status, amount_cents)
      |VALUES ('tenant-demo', 'A-100', 'paid', 2599)
      |ON CONFLICT DO NOTHING;
      |""".stripMargin

object IssueRefundMutation
    extends PostgresBusinessMutation[
      ProductionSupportTools.IssueRefundInput,
      ProductionSupportTools.IssueRefundOutput
    ]:
  val operationName: String = "issue_refund"

  def idempotencyKey(
      input: ProductionSupportTools.IssueRefundInput,
      context: ToolExecutionContext
  ): Either[AgentError, BusinessIdempotencyKey] =
    val tenant = context.runContext.tenantId.getOrElse("")
    BusinessIdempotencyKey
      .fromString(s"refund:$tenant:${input.refundId}")
      .left
      .map(error => AgentError.ToolInputInvalid(operationName, error))

  def mutate(
      connection: Connection,
      input: ProductionSupportTools.IssueRefundInput,
      context: ToolExecutionContext
  ): Either[AgentError, ProductionSupportTools.IssueRefundOutput] =
    context.runContext.tenantId match
      case None         => Left(AgentError.PermissionDenied(operationName, "退款必须绑定可信租户"))
      case Some(tenant) =>
        val statement = connection.prepareStatement(
          """INSERT INTO support_refunds(tenant_id, refund_id, order_id, amount_cents)
            |VALUES (?, ?, ?, ?)
            |ON CONFLICT (tenant_id, refund_id) DO NOTHING""".stripMargin
        )
        try
          statement.setString(1, tenant)
          statement.setString(2, input.refundId)
          statement.setString(3, input.orderId)
          statement.setLong(4, input.amountCents)
          statement.executeUpdate()
          Right(ProductionSupportTools.IssueRefundOutput(input.refundId, accepted = true))
        catch case error: Throwable => Left(AgentError.PersistenceFailure("退款 mutation 失败", Some(error)))
        finally statement.close()

  override def outbox(
      output: ProductionSupportTools.IssueRefundOutput,
      context: ToolExecutionContext
  ): Either[AgentError, Chunk[OutboxEventDraft]] =
    Right(
      Chunk(
        OutboxEventDraft(
          destination = "support.refunds",
          eventType = "support.refund.accepted.v1",
          aggregateType = "refund",
          aggregateId = output.refundId,
          partitionKey = output.refundId,
          payload = Json.Obj("refundId" -> Json.Str(output.refundId)),
          headers = Map("tenant" -> context.runContext.tenantId.getOrElse("missing"))
        )
      )
    )
