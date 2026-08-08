package com.zyblw.agent.evals.cli

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import com.zyblw.agent.persistence.postgres.PostgresEvalTrendStoreConfig
import java.nio.file.Path
import zio.*

/** 发布门禁可选择的趋势事实源。
  *
  * `File` 适合单节点 CI artifact 或本地预发布；`Postgres` 适合多个流水线/worker 共享不可变基线。枚举不会直接使用 Scala case
  * 名作为环境变量协议，解析器接受稳定的小写字符串，避免以后重命名源码破坏部署配置。
  */
enum EvalReleaseStoreBackend:
  case File
  case Postgres

/** 一次性 PostgreSQL CLI 的连接与 Store 容量配置。
  *
  * 正式常驻业务服务应复用宿主监控过的连接池；该 CLI 每次只执行“查一个基线 + 追加一条快照”后退出，因此使用 PostgreSQL 驱动的 `PGSimpleDataSource`，不会为两个顺序 JDBC
  * 操作额外启动连接池线程。密码保持为 `Config.Secret`，直到最外层 DataSource Adapter 构造时才展开。
  *
  * @param jdbcUrl
  *   PostgreSQL JDBC URL；禁止在 URL query 中携带 password/sslpassword
  * @param user
  *   数据库最小权限账号，建议仅拥有 `agent_eval_snapshots` 的 SELECT/INSERT
  * @param password
  *   数据库密码；日志与 `toString` 永不打印其内容
  * @param connectTimeoutSeconds
  *   建连超时秒数
  * @param storeConfig
  *   单条快照与历史查询容量上限
  */
final case class EvalReleasePostgresConfig(
    jdbcUrl: String,
    user: String,
    password: Config.Secret,
    connectTimeoutSeconds: Int,
    storeConfig: PostgresEvalTrendStoreConfig
):
  /** 即使部署代码意外记录整个配置，也只显示 `<redacted>`。 */
  override def toString: String =
    s"EvalReleasePostgresConfig(jdbcUrl=$jdbcUrl, user=$user, password=<redacted>, " +
      s"connectTimeoutSeconds=$connectTimeoutSeconds, storeConfig=$storeConfig)"

/** 评测发布 CLI 的完整、已验证配置。
  *
  * @param artifact
  *   低敏快照 artifact 路径与容量
  * @param backend
  *   文件或 PostgreSQL 趋势事实源
  * @param fileStore
  *   文件模式配置；PostgreSQL 模式下为 None
  * @param postgresStore
  *   PostgreSQL 模式配置；文件模式下为 None
  * @param policy
  *   硬门禁、回归容忍度与显式 bootstrap 策略
  */
final case class EvalReleaseGateCliConfig(
    artifact: EvalSnapshotArtifactConfig,
    backend: EvalReleaseStoreBackend,
    fileStore: Option[FileEvalTrendStoreConfig],
    postgresStore: Option[EvalReleasePostgresConfig],
    policy: EvalRegressionPolicy
):
  /** 配置摘要只展示路径/容量/策略和数据库非秘密字段。 */
  override def toString: String =
    s"EvalReleaseGateCliConfig(artifact=$artifact, backend=$backend, fileStore=$fileStore, " +
      s"postgresStore=$postgresStore, policy=$policy)"

/** 使用 ZIO Config 加载发布门禁配置。
  *
  * 默认配置路径为 `zyblw.agent.eval.release`，例如：
  *
  * {{ ZYBLW_AGENT_EVAL_RELEASE_ARTIFACT_PATH=target/evals/candidate-snapshot.json
  * ZYBLW_AGENT_EVAL_RELEASE_STORE_BACKEND=postgres
  * ZYBLW_AGENT_EVAL_RELEASE_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/zyblw
  * ZYBLW_AGENT_EVAL_RELEASE_POSTGRES_USER=zyblw_eval_release ZYBLW_AGENT_EVAL_RELEASE_POSTGRES_PASSWORD=...
  * }}
  *
  * 描述与读取分离后，测试可用 `ConfigProvider.fromMap`，生产可使用环境变量、系统属性或宿主替换的配置后端。ZIO 默认 Provider 会把嵌套路径映射为大写下划线环境变量。
  */
object EvalReleaseGateCliConfig:
  /** 默认点分路径。
    *
    * 每个 path segment 只使用字母数字和下划线，不使用连字符。ZIO 2.1 默认环境 Provider 的真实规则是“把 path segment 用 `_` 连接并转大写”，它不会自动把
    * segment 内的 `-` 替换成 `_`；因此该路径能准确映射到 `ZYBLW_AGENT_EVAL_RELEASE_*`，而不是产生 shell 无法正常 export 的连字符变量名。
    */
  val DefaultPrefix: String = "zyblw.agent.eval.release"

  /** 创建纯配置描述，不在构造时访问环境。
    *
    * 两种 Store 的字段都声明为 optional，最后在 `mapAttempt` 中按 backend 做互斥校验；这样文件模式不会错误地要求数据库 密码，PostgreSQL
    * 模式也不会要求本地趋势路径。
    *
    * @param prefix
    *   配置根路径
    */
  def description(prefix: String = DefaultPrefix): Config[EvalReleaseGateCliConfig] =
    val base = (
      artifactDescription ++
        Config.string("backend").withDefault("file").nested("store") ++
        fileDescription ++
        postgresDescription ++
        policyDescription
    ).mapAttempt { case (artifact, backendRaw, file, postgres, policy) =>
      val backend = normalized(backendRaw) match
        case "file"     => EvalReleaseStoreBackend.File
        case "postgres" => EvalReleaseStoreBackend.Postgres
        case _          => throw IllegalArgumentException("store-backend 仅支持 file 或 postgres")

      backend match
        case EvalReleaseStoreBackend.File =>
          val path = file.path
            .filter(_.trim.nonEmpty)
            .map(value => safePath("file.path", value))
            .getOrElse(throw IllegalArgumentException("file.path 在 file 模式下必须配置"))
          EvalReleaseGateCliConfig(
            artifact,
            backend,
            Some(FileEvalTrendStoreConfig(path, file.maxFileBytes, file.maxRecordBytes)),
            None,
            policy
          )
        case EvalReleaseStoreBackend.Postgres =>
          val jdbcUrl  = required("postgres.jdbc-url", postgres.jdbcUrl)
          val user     = required("postgres.user", postgres.user)
          val password = postgres.password
            .filter(_.stringValue.nonEmpty)
            .getOrElse(throw IllegalArgumentException("postgres.password 在 postgres 模式下必须配置"))
          validateJdbcUrl(jdbcUrl)
          require(
            user.length <= 128 && !user.exists(_.isControl),
            "postgres.user 必须为 1..128 个无控制字符文本"
          )
          require(
            postgres.connectTimeoutSeconds >= 1 && postgres.connectTimeoutSeconds <= 60,
            "postgres.connect-timeout-seconds 必须位于 1..60"
          )
          EvalReleaseGateCliConfig(
            artifact,
            backend,
            None,
            Some(
              EvalReleasePostgresConfig(
                jdbcUrl,
                user,
                password,
                postgres.connectTimeoutSeconds,
                PostgresEvalTrendStoreConfig(postgres.maxSnapshotBytes, postgres.maxHistoryLimit)
              )
            ),
            policy
          )
    }
    ZioConfigPath.nested(base, prefix)

  /** 从当前 Fiber 的 ConfigProvider 加载配置。
    *
    * 错误统一收敛为稳定低敏 code，不把 Secret、JDBC URL 或本地绝对路径打印到通用 CI 日志。具体缺失字段可对照本文档和 `.env.example` 排查。
    */
  def load(prefix: String = DefaultPrefix): IO[AgentError.InvalidConfiguration, EvalReleaseGateCliConfig] =
    ZIO
      .config(description(prefix))
      .mapError(_ => AgentError.InvalidConfiguration("eval-release:configuration-invalid"))

  /** 低敏候选 artifact 配置。 */
  private lazy val artifactDescription: Config[EvalSnapshotArtifactConfig] =
    (
      Config.string("path") ++
        Config.int("max_bytes").withDefault(2 * 1024 * 1024)
    ).mapAttempt { case (path, maxBytes) =>
      require(maxBytes > 0 && maxBytes <= 16 * 1024 * 1024, "artifact.max-bytes 必须位于 1..16777216")
      EvalSnapshotArtifactConfig(safePath("artifact.path", path), maxBytes)
    }.nested("artifact")

  /** 文件 Store 的原始 optional 字段；backend 选择之后再决定 path 是否必填。 */
  private lazy val fileDescription: Config[RawFileConfig] =
    (
      Config.string("path").optional ++
        Config.long("max_file_bytes").withDefault(64L * 1024L * 1024L) ++
        Config.int("max_record_bytes").withDefault(2 * 1024 * 1024)
    ).map(RawFileConfig.apply).nested("file")

  /** PostgreSQL 的原始 optional 字段；密码始终使用 `Config.Secret`。 */
  private lazy val postgresDescription: Config[RawPostgresConfig] =
    (
      Config.string("jdbc_url").optional ++
        Config.string("user").optional ++
        Config.secret("password").optional ++
        Config.int("connect_timeout_seconds").withDefault(10) ++
        Config.int("max_snapshot_bytes").withDefault(2 * 1024 * 1024) ++
        Config.int("max_history_limit").withDefault(100000)
    ).map(RawPostgresConfig.apply).nested("postgres")

  /** 发布策略全部显式可配置，但默认保持 fail-closed。 */
  private lazy val policyDescription: Config[EvalRegressionPolicy] =
    (
      Config.double("max_pass_rate_drop").withDefault(0.0) ++
        Config.double("max_dimension_score_drop").withDefault(0.0) ++
        Config.boolean("require_candidate_hard_gates").withDefault(true) ++
        Config.boolean("require_all_baseline_cases").withDefault(true) ++
        Config.boolean("require_all_baseline_dimensions").withDefault(true) ++
        Config.boolean("allow_first_passing_baseline").withDefault(false)
    ).mapAttempt(EvalRegressionPolicy.apply).nested("policy")

  /** 外部路径必须非空、有限且无控制字符；文件类型与符号链接由具体 Reader/Store 再校验。 */
  private def safePath(name: String, value: String): Path =
    val trimmed = value.trim
    require(
      trimmed.nonEmpty && trimmed.length <= 4096 && !trimmed.exists(_.isControl),
      s"$name 必须为 1..4096 个无控制字符文本"
    )
    Path.of(trimmed)

  /** 读取 backend 条件必填字符串，并在返回前去除部署值首尾空格。 */
  private def required(name: String, value: Option[String]): String =
    value
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"$name 必须配置"))

  /** 限制 CLI JDBC URL 协议并禁止把密码塞入 URL。
    *
    * 密码若进入 URL，驱动异常、连接池指标或诊断工具可能完整打印它；必须只通过 `Config.Secret` 字段传入。
    */
  private def validateJdbcUrl(value: String): Unit =
    val lower = value.toLowerCase
    require(
      value.length <= 2048 && !value.exists(_.isControl) && lower.startsWith("jdbc:postgresql://"),
      "postgres.jdbc-url 必须是长度不超过 2048 的 PostgreSQL JDBC URL"
    )
    require(
      !lower.matches(".*(?:[?&;])(password|sslpassword)=.*"),
      "postgres.jdbc-url 禁止包含 password 或 sslpassword"
    )

  /** 部署字符串统一忽略大小写、首尾空格，并接受下划线作为连字符别名。 */
  private def normalized(value: String): String = value.trim.toLowerCase.replace('_', '-')

  /** Config 组合过程中的文件模式中间值，不进入公开 API。 */
  final private case class RawFileConfig(
      path: Option[String],
      maxFileBytes: Long,
      maxRecordBytes: Int
  )

  /** Config 组合过程中的 PostgreSQL 模式中间值，不进入公开 API。 */
  final private case class RawPostgresConfig(
      jdbcUrl: Option[String],
      user: Option[String],
      password: Option[Config.Secret],
      connectTimeoutSeconds: Int,
      maxSnapshotBytes: Int,
      maxHistoryLimit: Int
  )
