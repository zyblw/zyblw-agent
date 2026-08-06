package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 一条评测趋势线的身份。
  *
  * 字段与 `agent-evals` 的 `EvalTrendIdentity` 一一对应，但保持 provider-neutral，使管理面 HTTP 层不必依赖评测模块。
  */
final case class EvalSuiteIdentityView(
    kind: String,
    suiteId: String,
    datasetId: String,
    datasetVersion: String
) derives JsonCodec:
  /** 供前端做列表 key 与 URL 参数的稳定标识。 */
  def key: String = s"$kind/$suiteId/$datasetId/$datasetVersion"

/** 趋势线上的一个数据点。
  *
  * `dimensionScores` 与 `dimensionGates` 分开，是因为发布门禁的判定依据是布尔 `passed` 而不是分数：一个维度可以
  * 分数很高但仍然没通过硬门禁，管理台把两者画在一起才不会误导。
  */
final case class EvalTrendPointView(
    evaluationId: String,
    harnessVersion: String,
    commitSha: Option[String],
    provider: Option[String],
    model: Option[String],
    finishedAtEpochMilli: Long,
    passed: Boolean,
    passRate: Double,
    caseCount: Int,
    dimensionScores: Map[String, Double],
    dimensionGates: Map[String, Boolean]
) derives JsonCodec

/** 一条趋势线的完整历史。 */
final case class EvalTrendSeries(
    identity: EvalSuiteIdentityView,
    points: Chunk[EvalTrendPointView]
) derives JsonCodec

/** 评测趋势的管理面只读 SPI。
  *
  * 它不提供“发现所有存在的趋势线”能力：`EvalTrendStore` 的发布契约里没有枚举方法，为管理台给已发布 trait 增加抽象 方法会让所有外部实现无法编译。跟踪哪些套件由部署配置显式声明，这也更符合
  * “管理台展示的是运维关心的少数几条线，而不是数据库里碰巧有的一切”。
  */
trait EvalTrendReader:
  /** 列出本部署声明跟踪的趋势线。 */
  def suites: IO[AgentError, Chunk[EvalSuiteIdentityView]]

  /** 读取一条趋势线的最近若干个数据点，按时间升序。 */
  def history(identity: EvalSuiteIdentityView, limit: Int): IO[AgentError, EvalTrendSeries]

object EvalTrendReader:
  /** 单条趋势线返回的数据点上限。 */
  val MaxHistoryLimit: Int = 500

  /** 未接入评测趋势仓库时的显式空实现。 */
  val empty: ULayer[EvalTrendReader] = ZLayer.succeed(new EvalTrendReader:
    def suites: IO[AgentError, Chunk[EvalSuiteIdentityView]] = ZIO.succeed(Chunk.empty)

    def history(identity: EvalSuiteIdentityView, limit: Int): IO[AgentError, EvalTrendSeries] =
      ZIO.succeed(EvalTrendSeries(identity, Chunk.empty)))

/** 外部可观测系统的深链配置。
  *
  * 管理台不应硬编码 Langfuse 或 Grafana 的地址：同一份前端构建会被部署到开发、预发和生产，各自的观测后端不同。 由后端返回链接模板，前端只负责填充
  * traceId，可以让一次部署配置同时纠正所有页面的跳转目标。
  *
  * @param langfuseBaseUrl
  *   Langfuse 实例根地址，例如 `https://cloud.langfuse.com`；None 表示未接入
  * @param langfuseProjectId
  *   Langfuse 项目 ID；自托管单项目部署可留空
  * @param grafanaBaseUrl
  *   Grafana 根地址
  * @param grafanaDashboardUid
  *   Agent 运行面板的 dashboard UID
  * @param otlpTracesEndpoint
  *   当前配置的 OTLP trace 端点，仅用于管理台展示“遥测发往何处”
  * @param traceIdDerivation
  *   traceId 的推导规则说明，取值 `run-id-hex` 表示 traceId 是 RunId 去掉连字符后的 32 位十六进制
  */
final case class ObservabilityLinks(
    langfuseBaseUrl: Option[String] = None,
    langfuseProjectId: Option[String] = None,
    grafanaBaseUrl: Option[String] = None,
    grafanaDashboardUid: Option[String] = None,
    otlpTracesEndpoint: Option[String] = None,
    traceIdDerivation: String = ObservabilityLinks.RunIdHexDerivation
) derives JsonCodec:
  /** 按框架的 traceId 约定推导某个 Run 的 trace 标识。
    *
    * 该规则必须与 `agent-opentelemetry` 中 RunId → traceId 的实现保持一致：traceId 是 RunId 去掉连字符后的 32 位小写十六进制。管理台据此把 Run
    * 列表直接链接到对应 trace，而不需要在响应里额外存一份 traceId。
    */
  def traceIdFor(runId: String): Option[String] =
    val hex = runId.replace("-", "").toLowerCase
    Option.when(traceIdDerivation == ObservabilityLinks.RunIdHexDerivation && hex.matches("[0-9a-f]{32}"))(
      hex
    )

  /** 构造某个 Run 的 Langfuse trace 深链；未配置 Langfuse 时返回 None。 */
  def langfuseTraceUrl(runId: String): Option[String] =
    for
      base    <- langfuseBaseUrl.map(_.stripSuffix("/"))
      traceId <- traceIdFor(runId)
    yield langfuseProjectId.fold(s"$base/trace/$traceId")(project =>
      s"$base/project/$project/traces/$traceId"
    )

object ObservabilityLinks:
  /** traceId 由 RunId 去连字符得到的推导规则名。 */
  val RunIdHexDerivation: String = "run-id-hex"

  /** ZIO Config 描述；环境变量前缀为 `ZYBLW_AGENT_OBSERVABILITY_`。 */
  val config: Config[ObservabilityLinks] =
    ZioConfigPath.nested(
      (Config.string("langfuse_base_url").optional ++
        Config.string("langfuse_project_id").optional ++
        Config.string("grafana_base_url").optional ++
        Config.string("grafana_dashboard_uid").optional ++
        Config.string("otlp_traces_endpoint").optional).map {
        case (langfuseBase, langfuseProject, grafanaBase, grafanaUid, otlp) =>
          ObservabilityLinks(langfuseBase, langfuseProject, grafanaBase, grafanaUid, otlp)
      },
      "zyblw.agent.observability"
    )

  /** 从环境加载深链配置；未设置任何变量时返回全空配置而不是失败。 */
  val layer: ZLayer[Any, Config.Error, ObservabilityLinks] =
    ZLayer.fromZIO(ZIO.config(config).orElseSucceed(ObservabilityLinks()))
