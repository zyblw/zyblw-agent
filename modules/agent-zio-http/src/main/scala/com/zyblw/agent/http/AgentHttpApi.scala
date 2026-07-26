package com.zyblw.agent.http

import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.runtime.*
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

trait AgentRegistry:
  /** 按稳定 ID 读取宿主允许公开运行的 Agent 定义。
    * @param id
    *   URL 中解析出的 Agent ID
    */
  def get(id: AgentId): IO[AgentError, AgentDefinition]

object AgentRegistry:
  /** 构造不可变内存注册表，适合静态配置或测试；动态业务注册表可自行实现 trait。
    * @param agents
    *   宿主启动时已经校验的 Agent 定义集合
    */
  def fromAgents(agents: Iterable[AgentDefinition]): ULayer[AgentRegistry] = ZLayer.succeed {
    val byId = agents.iterator.map(agent => agent.id -> agent).toMap
    new AgentRegistry:
      def get(id: AgentId): IO[AgentError, AgentDefinition] =
        ZIO.fromOption(byId.get(id)).orElseFail(AgentError.InvalidConfiguration(s"Agent 不存在: ${id.value}"))
  }

/** 把 ZIO HTTP Request 中已经过认证中间件验证的信息转换成 Runtime 可信上下文。 框架故意不规定 JWT/session 方案；宿主必须从经过验签的 claim 或服务端 session
  * 构造结果，绝不能直接信任 用户提交的 userId、tenantId 或 scope header。
  */
trait AgentRequestContextResolver:
  /** 解析本次请求的可信身份与授权范围。
    * @param request
    *   原始 HTTP 请求；实现通常读取认证中间件写入的安全属性
    */
  def resolve(request: Request): IO[AgentError, RunContext]

object AgentRequestContextResolver:
  /** 仅用于公开匿名 Agent 或测试；所有身份与权限均为空。 */
  val anonymous: ULayer[AgentRequestContextResolver] =
    ZLayer.succeed((_: Request) => ZIO.succeed(RunContext()))

/** ZIO HTTP Adapter。HTTP 层只做编解码和状态映射，Agent 循环仍完全位于 runtime。
  *
  * `POST /api/v1/agents/{id}/runs` 只把初始状态与 Start 命令原子写入耐久队列并返回 202；模型、工具和审批推进全部由 WorkerHost
  * 执行。客户端可通过状态、命令和事件端点轮询或断点读取，不再让 HTTP 连接寿命决定 Run 寿命。
  */
final class AgentHttpApi(
    runtime: AgentRuntime,
    commands: AgentCommandService,
    agents: AgentRegistry,
    contexts: AgentRequestContextResolver,
    durableEvents: DurableRunEventStream
):
  /** Runtime 的 ZIO HTTP 路由集合：异步创建、状态、取消、审批恢复、崩溃恢复和持久事件查询。 Handler 只处理协议转换；所有状态推进仍委托给注入的 `runtime`。
    */
  val routes: Routes[Any, Nothing] = (Routes(
    AgentHttpContract.createRunPattern -> handler { (agentId: String, request: Request) =>
      (for
        body  <- HttpRequestBody.readJson(request)
        input <- ZIO.fromEither(body.fromJson[CreateRunRequest]).mapError(AgentError.InvalidConfiguration(_))
        _     <- validateText("agentId", agentId, AgentHttpLimits.AgentIdChars)
        parsedAgentId <- ZIO
          .fromEither(AgentId.fromString(agentId))
          .mapError(AgentError.InvalidConfiguration(_))
        _        <- validateText("threadId", input.threadId, AgentHttpLimits.ThreadIdChars)
        _        <- validateText("input", input.input, AgentHttpLimits.InputChars)
        threadId <- ZIO
          .fromEither(ThreadId.fromString(input.threadId))
          .mapError(AgentError.InvalidConfiguration(_))
        agent          <- agents.get(parsedAgentId)
        context        <- contexts.resolve(request)
        idempotencyKey <- ZIO
          .fromOption(request.rawHeader("Idempotency-Key").map(_.trim).filter(_.nonEmpty))
          .orElseFail(AgentError.InvalidConfiguration("缺少 Idempotency-Key 请求头"))
        _ <- validateText("Idempotency-Key", idempotencyKey, AgentHttpLimits.IdempotencyKeyChars)
        runRequest = RunRequest(threadId, AgentMessage.user(input.input), context)
        record <- commands.submitStart(agent, runRequest, idempotencyKey)
      yield accepted(record)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.getRunPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed     <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        state      <- runtime.inspect(parsed)
        actor      <- contexts.resolve(request)
        authorized <- RunAuthorization.read(state, actor)
        _ <- ZIO.fromOption(authorized.threadId).orElseFail(AgentError.PersistenceFailure("Run 缺少 threadId"))
      yield Response.json(AgentHttpProjection.run(authorized).toJson))
        .catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.cancelRunPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed  <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        actor   <- contexts.resolve(request)
        body    <- HttpRequestBody.readJson(request)
        command <- ZIO.fromEither(body.fromJson[CancelCommand]).mapError(AgentError.InvalidConfiguration(_))
        _       <- ZIO.foreachDiscard(command.reason)(reason =>
          validateText("reason", reason, AgentHttpLimits.ReasonChars)
        )
        record <- commands.submitCancel(parsed, command.reason, actor)
      yield accepted(record)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.approveRunPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed  <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        body    <- HttpRequestBody.readJson(request)
        command <- ZIO.fromEither(body.fromJson[ApprovalCommand]).mapError(AgentError.InvalidConfiguration(_))
        _       <- ZIO.foreachDiscard(command.reason)(reason =>
          validateText("reason", reason, AgentHttpLimits.ReasonChars)
        )
        decision <- command.decision.toLowerCase match
          case "approve" => ZIO.succeed(ApprovalDecision.Approve)
          case "reject"  => ZIO.succeed(ApprovalDecision.Reject(command.reason.getOrElse("rejected")))
          case other     => ZIO.fail(AgentError.InvalidConfiguration(s"未知审批决定: $other"))
        actor  <- contexts.resolve(request)
        record <- commands.submitApproval(parsed, decision, actor)
      yield accepted(record)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.recoverRunPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        actor  <- contexts.resolve(request)
        record <- commands.submitRecover(parsed, actor)
      yield accepted(record)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.retryRunPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed  <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        body    <- HttpRequestBody.readJson(request)
        command <- ZIO.fromEither(body.fromJson[RetryRunCommand]).mapError(AgentError.InvalidConfiguration(_))
        _       <- validateText("requestId", command.requestId, AgentHttpLimits.RequestIdChars)
        _       <- validateText("reason", command.reason, AgentHttpLimits.ReasonChars)
        actor   <- contexts.resolve(request)
        record  <- commands.submitRetry(parsed, command.requestId, command.reason, actor)
      yield accepted(record)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.getCommandPattern -> handler { (commandId: String, request: Request) =>
      (for
        parsed <- ZIO.fromEither(CommandId.fromString(commandId)).mapError(AgentError.InvalidConfiguration(_))
        actor  <- contexts.resolve(request)
        record <- commands.inspect(parsed, actor)
      yield Response.json(view(record).toJson)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.retryCommandPattern -> handler { (commandId: String, request: Request) =>
      (for
        parsed <- ZIO.fromEither(CommandId.fromString(commandId)).mapError(AgentError.InvalidConfiguration(_))
        actor  <- contexts.resolve(request)
        record <- commands.retryDeadLetter(parsed, actor)
      yield accepted(record)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.listRunCommandsPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed  <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        actor   <- contexts.resolve(request)
        records <- commands.list(parsed, actor)
      yield Response.json(records.map(view).toList.toJson))
        .catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.inspectRunPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed     <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        cursor     <- parseLastEventId(request)
        actor      <- contexts.resolve(request)
        state      <- runtime.inspect(parsed)
        authorized <- RunAuthorization.read(state, actor)
        _          <- validateCursor(cursor, authorized)
        events     <- runtime.persistedEvents(parsed, cursor)
      yield Response.json(AgentHttpProjection.inspection(authorized, events, cursor).toJson))
        .catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.streamRunEventsPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        cursor <- parseLastEventId(request)
        actor  <- contexts.resolve(request)
        state  <- runtime.inspect(parsed)
        _      <- RunAuthorization.read(state, actor)
        _      <- validateCursor(cursor, state)
      yield durableEventResponse(parsed, cursor)).catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    AgentHttpContract.listRunEventsPattern -> handler { (runId: String, request: Request) =>
      (for
        parsed <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
        cursor <- parseLastEventId(request)
        actor  <- contexts.resolve(request)
        state  <- runtime.inspect(parsed)
        _      <- RunAuthorization.read(state, actor)
        _      <- validateCursor(cursor, state)
        events <- runtime.persistedEvents(parsed, cursor)
      yield Response.json(events.map(AgentHttpProjection.event).toList.toJson))
        .catchAll(error => ZIO.succeed(errorResponse(error)))
    },
    Method.GET / "api" / "v1" / "openapi.json" -> handler {
      ZIO.succeed(Response.json(AgentHttpContract.openApiJson).addHeader("Cache-Control", "no-store"))
    }
  )) @@ HandlerAspect.addHeader(AgentHttpProtocol.ApiVersionHeader, AgentHttpProtocol.ApiVersionHeaderValue)

  /** 构造跨节点可恢复的 SSE Response。
    *
    * `id` 使用单调 sequence，`eventType` 使用稳定公共事件名，`data` 保存脱敏后的 `RunEventView` JSON，而不是内部
    * `PersistedAgentEvent`。流中途数据库故障时 HTTP 状态已经无法改变，因此发送一个不含内部错误详情的 `stream_error` 后结束；客户端可稍后携带最后成功 ID 重连。15 秒
    * heartbeat 只维持代理连接，不推进游标，也不写入 RunStore。
    */
  private def durableEventResponse(runId: RunId, afterSequence: Long): Response =
    val eventStream = durableEvents
      .events(runId, afterSequence)
      .map { persisted =>
        val publicEvent = AgentHttpProjection.event(persisted)
        ServerSentEvent(
          data = publicEvent.toJson,
          eventType = Some(publicEvent.eventType),
          id = Some(persisted.sequence.toString)
        )
      }
      .catchAll { error =>
        ZStream.succeed(
          ServerSentEvent(
            data = ErrorResponse(error.category.toString, safeMessage(error)).toJson,
            eventType = Some("stream_error")
          )
        )
      }
    val heartbeats = ZStream.repeatZIO(ZIO.sleep(15.seconds).as(ServerSentEvent.heartbeat))
    Response
      .fromServerSentEvents(eventStream.mergeHaltLeft(heartbeats))
      .addHeader("Cache-Control", "no-cache, no-transform")
      .addHeader("X-Accel-Buffering", "no")

  /** 解析浏览器/EventSource 重连游标。
    * @param request
    *   当前 HTTP 请求
    * @return
    *   缺失时为 -1；非法、负得小于 -1 或溢出的值在创建流之前返回 400
    */
  private def parseLastEventId(request: Request): IO[AgentError, Long] =
    request.rawHeader("Last-Event-ID").map(_.trim).filter(_.nonEmpty) match
      case None        => ZIO.succeed(-1L)
      case Some(value) =>
        ZIO
          .attempt(value.toLong)
          .mapError(_ => AgentError.InvalidConfiguration("Last-Event-ID 必须是事件 sequence 整数"))
          .flatMap(sequence =>
            if sequence >= -1L then ZIO.succeed(sequence)
            else ZIO.fail(AgentError.InvalidConfiguration("Last-Event-ID 不能小于 -1"))
          )

  /** 在响应头发出之前拒绝未来游标，避免把明显的客户端错误降级成 200 + stream_error。 */
  private def validateCursor(cursor: Long, state: AgentState): IO[AgentError, Unit] =
    ZIO
      .fail(
        AgentError.InvalidConfiguration(
          s"Last-Event-ID $cursor 超过 Run 当前最后序号 ${state.lastEventSequence}"
        )
      )
      .when(cursor > state.lastEventSequence)
      .unit

  /** 对公共字符串执行非空和字符数上限校验。
    *
    * 这里按 Unicode code point 而不是 UTF-16 `String.length` 计数，避免 emoji 等补充平面字符被错误算作两个业务字符。 `HttpRequestBody` 已提供
    * Adapter 内 256 KiB 字节上限；HTTP Server/网关仍应设置更早、更适合连接治理的 body 上限。本校验 负责稳定字段语义与进入 Runtime 前的第二道边界。
    *
    * @param field
    *   公共字段名，只用于低敏 validation 消息
    * @param value
    *   客户端输入；不会写入错误或日志
    * @param maxChars
    *   v1 契约允许的最大 Unicode 字符数
    */
  private def validateText(field: String, value: String, maxChars: Int): IO[AgentError, Unit] =
    val count = Option(value).fold(0)(text => text.codePointCount(0, text.length))
    if Option(value).forall(_.trim.isEmpty) then ZIO.fail(AgentError.InvalidConfiguration(s"$field 不能为空"))
    else if count > maxChars then ZIO.fail(AgentError.InvalidConfiguration(s"$field 超过最大长度 $maxChars"))
    else ZIO.unit

  /** 202 表示命令已耐久接收但尚未执行完成；客户端应使用 commandId 查询状态。 */
  private def accepted(record: RunCommandRecord): Response =
    val receipt = CommandReceipt(
      record.commandId.asString,
      record.runId.asString,
      record.payload.commandType,
      record.status.toString
    )
    Response(status = Status.Accepted, body = Body.fromString(receipt.toJson))
      .addHeader(Header.ContentType(MediaType.application.json))

  /** 将内部命令记录收敛为不泄露 payload 的公共视图。 */
  private def view(record: RunCommandRecord): CommandView =
    CommandView(
      record.commandId.asString,
      record.runId.asString,
      record.payload.commandType,
      record.status.toString,
      record.attempt,
      record.manualRetryCount,
      record.lastFailure
    )

  /** 把 typed `AgentError` 映射为稳定 HTTP 状态与安全 JSON，内部异常不会泄露给客户端。
    * @param error
    *   Runtime、注册表或请求解析产生的类型化错误
    */
  private def errorResponse(error: AgentError): Response =
    HttpErrorResponse.from(error)

  /** 根据错误的公开标记决定是否返回原始领域消息。
    * @param error
    *   待转换的类型化错误
    */
  private def safeMessage(error: AgentError): String =
    if error.safeToExpose then error.message else "智能体服务暂时无法完成请求"

object AgentHttpApi:
  /** 组装 HTTP Adapter；认证上下文解析器和耐久事件流都是强制依赖。 宿主可使用 `DurableRunEventStream.default`，也可注入较慢轮询配置保护较小的数据库连接池。
    */
  val layer: URLayer[
    AgentRuntime & AgentCommandService & AgentRegistry & AgentRequestContextResolver & DurableRunEventStream,
    AgentHttpApi
  ] =
    ZLayer.fromFunction(AgentHttpApi.apply)
