package com.zyblw.agent.observability.otlp

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import scala.util.Try
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Langfuse Scores REST API 配置。
  *
  * Score 与 OTLP trace 使用同一 Langfuse project credentials，但它们是两种不同协议：trace 进入 OTLP ingestion，Score 进入
  * `/api/public/scores`。分开配置可以独立设置超时、重试与正文策略，也避免把 Basic credential 误发给 OTel Collector。
  *
  * @param host
  *   Langfuse 根地址，生产默认必须是 HTTPS
  * @param publicKey
  *   Project public key，作为 HTTP Basic username
  * @param secretKey
  *   Project secret key，作为 HTTP Basic password；绝不出现在 toString 或错误正文
  * @param requestTimeout
  *   单次 HTTP 请求硬超时
  * @param maxResponseBytes
  *   Langfuse 响应最大字节数，防止异常网关返回无界 HTML
  * @param maxAttempts
  *   包含首次调用在内的最大尝试次数；只有 408/409/425/429/5xx 和 transport 错误会重试
  * @param initialBackoff
  *   首次重试退避
  * @param maxBackoff
  *   退避上限
  * @param allowInsecureHttp
  *   仅本地 stub 测试可打开；生产必须保持 false
  * @param allowTextScores
  *   是否允许自由文本 Score；默认关闭，避免评语携带提示词、病历或模型正文
  * @param allowComments
  *   是否允许 comment；默认关闭，确定性 eval 只上传名称、值和稳定关联 ID
  * @param allowedScoreNames
  *   可选名称白名单；非空时名称不在集合内会 fail-closed
  */
final case class LangfuseScoresConfig(
    host: String,
    publicKey: String,
    secretKey: String,
    requestTimeout: Duration = 5.seconds,
    maxResponseBytes: Int = 64 * 1024,
    maxAttempts: Int = 3,
    initialBackoff: Duration = 100.millis,
    maxBackoff: Duration = 2.seconds,
    allowInsecureHttp: Boolean = false,
    allowTextScores: Boolean = false,
    allowComments: Boolean = false,
    allowedScoreNames: Set[String] = Set.empty
):
  /** Scores 创建端点；v3 目前是读取 API，官方创建端点仍为 `/api/public/scores`。 */
  val scoresUrl: String = s"${host.stripSuffix("/")}/api/public/scores"

  /** 配置渲染永远省略两把 project key。 */
  override def toString: String =
    s"LangfuseScoresConfig(host=$host, credentials=<redacted>, timeout=$requestTimeout, maxAttempts=$maxAttempts, " +
      s"allowTextScores=$allowTextScores, allowComments=$allowComments, allowedScoreNames=${allowedScoreNames.size})"

  /** 在构造 HTTP Authorization 前完成 URL、凭据和资源上限校验。 */
  def validated: IO[AgentError, LangfuseScoresConfig] =
    val uri           = Try(URI.create(host)).toOption
    val endpointValid = uri.exists(value =>
      Option(value.getHost).exists(_.nonEmpty) &&
        value.getUserInfo == null && value.getQuery == null && value.getFragment == null &&
        (value.getScheme == "https" || (allowInsecureHttp && value.getScheme == "http"))
    )
    val backoffValid = initialBackoff > Duration.Zero && maxBackoff >= initialBackoff
    val namesValid   = allowedScoreNames.forall(LangfuseScoreValidation.validName)
    if !endpointValid then
      ZIO.fail(AgentError.InvalidConfiguration("Langfuse Scores host 必须是无凭据/query/fragment 的 HTTPS 根地址"))
    else if publicKey.trim.isEmpty || secretKey.trim.isEmpty then
      ZIO.fail(AgentError.InvalidConfiguration("Langfuse Scores publicKey 与 secretKey 不能为空"))
    else if requestTimeout <= Duration.Zero || maxResponseBytes <= 0 || maxAttempts <= 0 || !backoffValid then
      ZIO.fail(AgentError.InvalidConfiguration("Langfuse Scores timeout、响应上限、尝试次数和退避配置无效"))
    else if !namesValid then ZIO.fail(AgentError.InvalidConfiguration("Langfuse Scores 名称白名单含非法名称"))
    else ZIO.succeed(this)

/** Score 只能绑定一个明确目标，避免同时填写 trace/session 后由服务端猜测。 */
enum LangfuseScoreTarget:
  /** 端到端 Run/Trace 评分。 */
  case Trace(traceId: String)

  /** 某次 generation/tool/retrieval observation 评分；必须同时提供所属 trace。 */
  case Observation(traceId: String, observationId: String)

  /** 多轮会话整体评分。 */
  case Session(sessionId: String)

/** Langfuse 官方支持的四类 Score value。 */
enum LangfuseScoreValue:
  /** 有限浮点分数，例如正确率 0.9。 */
  case Numeric(value: Double)

  /** 可聚合类别，例如 pass/partial/fail。 */
  case Categorical(value: String)

  /** 布尔硬门禁；线协议按官方要求编码为 1 或 0，并显式声明 BOOLEAN。 */
  case BooleanValue(value: Boolean)

  /** 1 到 500 字符的自由文本；默认策略禁止。 */
  case Text(value: String)

/** 一条完整、可幂等重放的 Score。
  *
  * Langfuse 只有在 `id`、`name` 和 timestamp 的日期都保持一致时才覆盖既有 Score。因此 timestamp 必须在首次评测时确定并 与本地报告一起保存；重试不能重新调用
  * `Clock.instant`。客户端始终发送完整对象，不依赖已弃用的部分字段合并。
  *
  * @param id
  *   稳定幂等键
  * @param name
  *   稳定低基数 Score 名称
  * @param timestamp
  *   首次计算该 Score 的固定时间
  * @param target
  *   Trace、Observation 或 Session 三选一
  * @param value
  *   类型化值
  * @param configId
  *   可选 Langfuse ScoreConfig ID，用于服务端范围/类别约束
  * @param comment
  *   可选说明；生产默认策略禁止，且绝不能放入隐藏推理或原始业务正文
  */
final case class LangfuseScore(
    id: String,
    name: String,
    timestamp: Instant,
    target: LangfuseScoreTarget,
    value: LangfuseScoreValue,
    configId: Option[String] = None,
    comment: Option[String] = None
)

/** 服务端接受后返回的低敏回执；不保存响应正文。 */
final case class LangfuseScoreReceipt(id: String)

/** Langfuse Score 发送 SPI；业务测试可替换为 Recording 实现。 */
trait LangfuseScoreClient:
  /** 发送完整 Score；retry 使用相同 id/name/timestamp，因而不会因网络重试产生重复。 */
  def publish(score: LangfuseScore): IO[AgentError, LangfuseScoreReceipt]

/** 基于 ZIO HTTP 的 Langfuse Scores 客户端。 */
final class ZioHttpLangfuseScoreClient(client: Client, config: LangfuseScoresConfig)
    extends LangfuseScoreClient:
  def publish(score: LangfuseScore): IO[AgentError, LangfuseScoreReceipt] =
    for
      validated <- config.validated
      payload   <- LangfuseScoreWire.encode(score, validated)
      receipt <- attempt(score.id, payload.toJson, validated, validated.maxAttempts, validated.initialBackoff)
    yield receipt

  /** 仅对明确 retryable 的 HTTP/transport 失败退避重试。
    *
    * 4xx schema/认证失败通常需要人工修复，立即重试只会放大流量；429 和服务端故障可以依赖稳定幂等键安全重放。
    */
  private def attempt(
      scoreId: String,
      payload: String,
      validated: LangfuseScoresConfig,
      remaining: Int,
      delay: Duration
  ): IO[AgentError, LangfuseScoreReceipt] =
    post(scoreId, payload, validated).catchAll {
      case AgentError.ExternalProtocolFailure(_, _, _, _, true, _) if remaining > 1 =>
        ZIO.sleep(delay) *> attempt(
          scoreId,
          payload,
          validated,
          remaining - 1,
          (delay * 2).min(validated.maxBackoff)
        )
      case error => ZIO.fail(error)
    }

  /** 执行单次 POST；认证和 payload 不会进入任何错误消息。 */
  private def post(
      scoreId: String,
      payload: String,
      validated: LangfuseScoresConfig
  ): IO[AgentError, LangfuseScoreReceipt] =
    val credentials = Base64.getEncoder.encodeToString(
      s"${validated.publicKey.trim}:${validated.secretKey.trim}".getBytes(StandardCharsets.UTF_8)
    )
    val request = Request
      .post(validated.scoresUrl, Body.fromString(payload))
      .addHeader("Authorization", s"Basic $credentials")
      .addHeader(Header.ContentType(MediaType.application.json))
    for
      response <- client
        .batched(request)
        .timeoutFail(
          protocolFailure("post", "Langfuse Score request timed out", "timeout", retryable = true)
        )(
          validated.requestTimeout
        )
        .mapError {
          case error: AgentError => error
          case error             =>
            protocolFailure(
              "post",
              "Langfuse Score transport failed",
              "transport_failure",
              retryable = true,
              Some(error)
            )
        }
      _ <- readBounded(response, validated.maxResponseBytes)
      _ <- ZIO.fail(httpFailure(response.status.code)).unless(response.status.isSuccess)
    yield LangfuseScoreReceipt(scoreId)

  /** 完整排空并限制响应；内容既不解析为诊断，也不进入错误。 */
  private def readBounded(response: Response, limit: Int): IO[AgentError, Unit] =
    response.body.asStream
      .take(limit.toLong + 1L)
      .runCollect
      .mapError(error =>
        protocolFailure(
          "read",
          "Failed to read Langfuse Score response",
          "response_read_failed",
          retryable = true,
          Some(error)
        )
      )
      .flatMap(bytes =>
        ZIO
          .fail(
            protocolFailure("read", "Langfuse Score response exceeded configured limit", "response_too_large")
          )
          .when(bytes.length > limit)
          .unit
      )

  /** 根据状态码给可靠性层稳定分类，不拼接服务端可能含敏感内容的 body。 */
  private def httpFailure(status: Int): AgentError =
    protocolFailure(
      "post",
      s"Langfuse Score API returned HTTP $status",
      s"http_$status",
      retryable = status == 408 || status == 409 || status == 425 || status == 429 || status >= 500
    )

  /** 创建统一低敏协议错误。 */
  private def protocolFailure(
      operation: String,
      message: String,
      code: String,
      retryable: Boolean = false,
      cause: Option[Throwable] = None
  ): AgentError =
    AgentError.ExternalProtocolFailure("langfuse-scores", operation, message, Some(code), retryable, cause)

object LangfuseScoreClient:
  /** 从共享 ZIO HTTP Client 与配置构造可替换 Layer。 */
  val layer: URLayer[Client & LangfuseScoresConfig, LangfuseScoreClient] =
    ZLayer.fromFunction(ZioHttpLangfuseScoreClient.apply)

  /** 已有确定配置时只要求宿主提供共享 Client。 */
  def configured(config: LangfuseScoresConfig): URLayer[Client, LangfuseScoreClient] =
    ZLayer.succeed(config) >>> layer

/** Score 线协议编码与本地安全校验。 */
private[otlp] object LangfuseScoreWire:
  /** 把类型化 Score 编码为官方 camelCase JSON，拒绝策略不允许的正文。 */
  def encode(score: LangfuseScore, config: LangfuseScoresConfig): IO[AgentError, Json.Obj] =
    for
      _ <- LangfuseScoreValidation.validate(score, config)
      fields = Chunk(
        Some("id"        -> Json.Str(score.id)),
        Some("name"      -> Json.Str(score.name)),
        Some("timestamp" -> Json.Str(score.timestamp.toString)),
        Some("dataType"  -> Json.Str(dataType(score.value))),
        Some("value"     -> encodeValue(score.value)),
        score.configId.map(value => "configId" -> Json.Str(value)),
        score.comment.map(value => "comment" -> Json.Str(value))
      ) ++ targetFields(score.target).map(Some(_))
    yield Json.Obj(Chunk.fromIterable(fields.flatten))

  /** 映射官方大写 Score dataType。 */
  private def dataType(value: LangfuseScoreValue): String = value match
    case LangfuseScoreValue.Numeric(_)      => "NUMERIC"
    case LangfuseScoreValue.Categorical(_)  => "CATEGORICAL"
    case LangfuseScoreValue.BooleanValue(_) => "BOOLEAN"
    case LangfuseScoreValue.Text(_)         => "TEXT"

  /** BOOLEAN 按官方写入协议编码为数值 1/0；读取 v3 API 时服务端再返回 boolean。 */
  private def encodeValue(value: LangfuseScoreValue): Json = value match
    case LangfuseScoreValue.Numeric(number)      => Json.Num(BigDecimal(number))
    case LangfuseScoreValue.Categorical(label)   => Json.Str(label)
    case LangfuseScoreValue.BooleanValue(passed) => Json.Num(if passed then BigDecimal(1) else BigDecimal(0))
    case LangfuseScoreValue.Text(text)           => Json.Str(text)

  /** 目标 ADT 保证只生成一组关联字段。 */
  private def targetFields(target: LangfuseScoreTarget): Chunk[(String, Json)] = target match
    case LangfuseScoreTarget.Trace(traceId)                      => Chunk("traceId" -> Json.Str(traceId))
    case LangfuseScoreTarget.Observation(traceId, observationId) =>
      Chunk("traceId" -> Json.Str(traceId), "observationId" -> Json.Str(observationId))
    case LangfuseScoreTarget.Session(sessionId) => Chunk("sessionId" -> Json.Str(sessionId))

/** 所有本地输入约束集中在一个纯验证器中，便于 wire test 覆盖。 */
private object LangfuseScoreValidation:
  private val namePattern = raw"[A-Za-z0-9][A-Za-z0-9._-]{0,127}".r

  /** Score 名称必须是低基数协议字段，不能直接使用用户输入或整段问题。 */
  def validName(value: String): Boolean = namePattern.matches(value)

  /** 验证 id/name/target/value/comment 与部署策略。 */
  def validate(score: LangfuseScore, config: LangfuseScoresConfig): IO[AgentError, Unit] =
    val idValid     = score.id.nonEmpty && score.id.length <= 256 && !score.id.contains('\u0000')
    val nameAllowed =
      validName(score.name) && (config.allowedScoreNames.isEmpty || config.allowedScoreNames(score.name))
    val targetValid = score.target match
      case LangfuseScoreTarget.Trace(traceId)                      => validId(traceId)
      case LangfuseScoreTarget.Observation(traceId, observationId) =>
        validId(traceId) && validId(observationId)
      case LangfuseScoreTarget.Session(sessionId) => validId(sessionId)
    val valueValid = score.value match
      case LangfuseScoreValue.Numeric(value)     => value.isFinite
      case LangfuseScoreValue.Categorical(value) =>
        value.nonEmpty && value.length <= 500 && !value.contains('\u0000')
      case LangfuseScoreValue.BooleanValue(_) => true
      case LangfuseScoreValue.Text(value)     =>
        config.allowTextScores && value.nonEmpty && value.length <= 500 && !value.contains('\u0000')
    val metadataValid = score.configId.forall(validId) && score.comment.forall(value =>
      config.allowComments && value.nonEmpty && value.length <= 500 && !value.contains('\u0000')
    )
    ZIO
      .fail(
        AgentError.InvalidConfiguration("Langfuse Score violates id, name, target, value or content policy")
      )
      .unless(idValid && nameAllowed && targetValid && valueValid && metadataValid)
      .unit

  /** 外部关联 ID 只要求低敏、非空和有界，不假设 Langfuse 自定义 ID 一定是 UUID。 */
  private def validId(value: String): Boolean =
    value.nonEmpty && value.length <= 256 && !value.contains('\u0000')

/** 将确定性 Agent/Context 评测报告投影为 Langfuse Trace Scores。
  *
  * 该适配器不上传 eval input、答案、压缩摘要、`EvalGrade.details`、工具参数或引用正文；只上传固定低基数维度数值和 case 级布尔门禁。Langfuse 是可查询反馈视图，本地/CI
  * 报告仍是发布门禁事实源。
  */
final class LangfuseEvalScorePublisher(client: LangfuseScoreClient):
  /** 发布 Agent 级评测；调用方若要崩溃后重放，应持久化 evaluatedAt 并改用对应 `publishAt`。 */
  def publish(runId: RunId, report: AgentEvalReport): IO[AgentError, Unit] =
    Clock.instant.flatMap(publishAt(runId, report, _))

  /** 以固定时间发布完整评分集合。
    *
    * @param runId
    *   与 OTel/Langfuse trace 关联的运行 ID
    * @param report
    *   本地确定性评测报告
    * @param evaluatedAt
    *   首次评测时间；可靠重放必须保持不变
    */
  def publishAt(runId: RunId, report: AgentEvalReport, evaluatedAt: Instant): IO[AgentError, Unit] =
    publishGrades(
      traceId = runId.asString,
      caseId = report.caseId,
      datasetVersion = report.datasetVersion,
      grades = report.grades,
      passed = report.passed,
      gateDimension = "case-passed",
      gateName = "agent_eval_case_passed",
      scoreName = agentDimensionName,
      evaluatedAt = evaluatedAt
    )

  /** 发布 Context 压缩评测。
    *
    * `ContextCompressionEvalReport.attempts` 包含的哈希、Token 和成本仍只留在本地报告；Langfuse 仅接收六个 grade 数值和
    * 一个布尔门禁，进一步缩小外部数据面。
    */
  def publish(runId: RunId, report: ContextCompressionEvalReport): IO[AgentError, Unit] =
    Clock.instant.flatMap(publishAt(runId, report, _))

  /** 以首次评测时间发布 Context 压缩评分；崩溃重放必须复用同一个 `evaluatedAt`。
    *
    * @param runId
    *   与 Langfuse trace 对齐的运行 ID；离线数据集应先创建专用评测 trace
    * @param report
    *   本地 Context 压缩硬门禁报告
    * @param evaluatedAt
    *   首次完成评测的稳定时间
    */
  def publishAt(
      runId: RunId,
      report: ContextCompressionEvalReport,
      evaluatedAt: Instant
  ): IO[AgentError, Unit] =
    publishGrades(
      traceId = runId.asString,
      caseId = report.caseId,
      datasetVersion = report.datasetVersion,
      grades = report.grades,
      passed = report.passed,
      gateDimension = "context-compression-case-passed",
      gateName = "context_compression_eval_case_passed",
      scoreName = contextCompressionDimensionName,
      evaluatedAt = evaluatedAt
    )

  /** 公共低敏投影。
    *
    * @param traceId
    *   Langfuse Trace 目标
    * @param caseId
    *   只参与稳定哈希，不作为 Score name/comment 上传
    * @param datasetVersion
    *   只参与稳定哈希
    * @param grades
    *   本地确定性评分；details 不会读取
    * @param passed
    *   case 级硬门禁
    * @param gateDimension
    *   生成 gate 幂等 ID 的内部稳定维度
    * @param gateName
    *   上传到 Langfuse 的低基数 gate 名
    * @param scoreName
    *   把框架维度折叠为白名单 Score 名称
    * @param evaluatedAt
    *   首次评测时间
    */
  private def publishGrades(
      traceId: String,
      caseId: String,
      datasetVersion: String,
      grades: Chunk[EvalGrade],
      passed: Boolean,
      gateDimension: String,
      gateName: String,
      scoreName: String => String,
      evaluatedAt: Instant
  ): IO[AgentError, Unit] =
    val dimensions = grades.map { grade =>
      LangfuseScore(
        id = stableId(traceId, caseId, datasetVersion, grade.dimension),
        name = scoreName(grade.dimension),
        timestamp = evaluatedAt,
        target = LangfuseScoreTarget.Trace(traceId),
        value = LangfuseScoreValue.Numeric(grade.score)
      )
    }
    val gate = LangfuseScore(
      id = stableId(traceId, caseId, datasetVersion, gateDimension),
      name = gateName,
      timestamp = evaluatedAt,
      target = LangfuseScoreTarget.Trace(traceId),
      value = LangfuseScoreValue.BooleanValue(passed)
    )
    ZIO.foreachDiscard(dimensions :+ gate)(client.publish)

  /** 将 Agent 固定维度映射为低基数 Langfuse 名称，未知插件维度折叠为 other。 */
  private def agentDimensionName(dimension: String): String = dimension match
    case "tool-selection"       => "agent_eval_tool_selection"
    case "citation-correctness" => "agent_eval_citation_correctness"
    case "recovery-correctness" => "agent_eval_recovery_correctness"
    case "resource-budget"      => "agent_eval_resource_budget"
    case _                      => "agent_eval_other"

  /** Context 压缩的固定六维投影；未知扩展维度折叠，避免模型或数据集制造高基数名称。 */
  private def contextCompressionDimensionName(dimension: String): String = dimension match
    case "context-compression-completion"          => "context_compression_eval_completion"
    case "context-compression-evidence-retention"  => "context_compression_eval_evidence_retention"
    case "context-compression-reference-retention" => "context_compression_eval_reference_retention"
    case "context-compression-forbidden-content"   => "context_compression_eval_forbidden_content"
    case "context-compression-stability"           => "context_compression_eval_stability"
    case "context-compression-resource-budget"     => "context_compression_eval_resource_budget"
    case _                                         => "context_compression_eval_other"

  /** 对 run/case/dataset/dimension 做 SHA-256，得到不泄漏业务用例名称的稳定幂等 ID。
    *
    * 哈希不是加密匿名化；输入 ID 仍必须是非敏感业务标识，但可避免在 Langfuse 主键中直接暴露内部命名。
    */
  private def stableId(
      traceId: String,
      caseId: String,
      datasetVersion: String,
      dimension: String
  ): String =
    val source = s"$traceId\u0000$caseId\u0000$datasetVersion\u0000$dimension"
    val digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))
    "zyblw-eval-" + digest.map(byte => f"${byte & 0xff}%02x").mkString

object LangfuseEvalScorePublisher:
  /** 依赖注入 Layer，业务只需要提供 LangfuseScoreClient。 */
  val layer: URLayer[LangfuseScoreClient, LangfuseEvalScorePublisher] =
    ZLayer.fromFunction(LangfuseEvalScorePublisher.apply)
