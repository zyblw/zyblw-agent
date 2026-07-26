package com.zyblw.agent.evals.cli

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import com.zyblw.agent.persistence.postgres.PostgresEvalTrendStore
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.json.*

/** CI 能稳定判断的门禁结果类型。
  *
  * `Rejected` 表示程序、配置和趋势仓库都工作正常，但候选质量不满足发布策略；`Error` 表示输入或基础设施不可用，不能把 它误报成“模型回归”。三者分别映射到稳定进程退出码。
  */
enum EvalReleaseCliStatus derives JsonCodec:
  case Passed
  case Rejected
  case Error

/** CLI 唯一输出的低敏 JSON。
  *
  * @param schemaVersion
  *   输出协议版本
  * @param status
  *   通过、质量拒绝或执行错误
  * @param exitCode
  *   进程将使用的稳定退出码
  * @param decision
  *   成功完成门禁比较时的低敏决策；执行错误时为空
  * @param errorCode
  *   稳定错误码；不会包含路径、Secret、SQL、业务输入或 Provider 正文
  */
final case class EvalReleaseCliOutput(
    schemaVersion: Int,
    status: EvalReleaseCliStatus,
    exitCode: Int,
    decision: Option[EvalReleaseDecision],
    errorCode: Option[String]
) derives JsonCodec

/** 一次 CLI 执行结果。
  *
  * 该类型把“业务门禁结果”和“如何退出进程”作为普通不可变值，核心程序因此可以被 ZIO Test 直接调用，而无需在测试 JVM 中真的执行 `System.exit`。
  */
final case class EvalReleaseCliResult(
    output: EvalReleaseCliOutput,
    exitCode: ExitCode
)

object EvalReleaseCliResult:
  /** 候选通过；Unix 约定的成功退出码。 */
  val PassedExitCode: Int = 0

  /** 候选质量或回归策略拒绝。 */
  val RejectedExitCode: Int = 2

  /** 配置、artifact、schema 或调用参数错误。 */
  val ConfigurationExitCode: Int = 3

  /** PostgreSQL/文件系统等持久化基础设施不可用。 */
  val InfrastructureExitCode: Int = 4

  /** 其他已建模的框架错误；Defect 仍由 ZIOApp 以普通异常退出处理。 */
  val FrameworkExitCode: Int = 5

  /** 把门禁决策映射为稳定输出与退出码。 */
  def fromDecision(decision: EvalReleaseDecision): EvalReleaseCliResult =
    val code   = if decision.passed then PassedExitCode else RejectedExitCode
    val status = if decision.passed then EvalReleaseCliStatus.Passed else EvalReleaseCliStatus.Rejected
    EvalReleaseCliResult(
      EvalReleaseCliOutput(1, status, code, Some(decision), None),
      ExitCode(code)
    )

  /** 把 typed framework error 映射为不会泄密的 CLI 错误。
    *
    * 只有完全由 `[a-z0-9:-]` 组成的短消息才可作为稳定 code 输出；其他消息降级为错误分类，避免未来 Adapter 把 URL、SQL 或外部正文混入错误后被 CI 直接打印。
    */
  def fromError(error: AgentError): EvalReleaseCliResult =
    val code = error.category match
      case ErrorCategory.Configuration | ErrorCategory.Validation => ConfigurationExitCode
      case ErrorCategory.Persistence | ErrorCategory.Unavailable  => InfrastructureExitCode
      case _                                                      => FrameworkExitCode
    val safeCode =
      Option
        .when(error.message.length <= 160 && error.message.matches("[a-z0-9:-]+"))(error.message)
        .getOrElse(s"agent-error:${error.category.toString.toLowerCase}")
    EvalReleaseCliResult(
      EvalReleaseCliOutput(1, EvalReleaseCliStatus.Error, code, None, Some(safeCode)),
      ExitCode(code)
    )

/** 可测试的发布门禁核心程序。
  *
  * 依赖装配保持显式：先严格加载低敏 artifact，再根据配置创建 Store，最后调用统一 Gate。文件锁、JDBC 阻塞线程、 checksum、幂等和 bootstrap
  * 语义均由下层组件负责，本程序不复制这些规则。
  */
object EvalReleaseGateCliProgram:
  /** 执行一次完整发布门禁。
    *
    * @param config
    *   已由 ZIO Config 完成互斥与 Secret 校验的配置
    * @return
    *   可直接打印并退出的低敏结果
    */
  def run(config: EvalReleaseGateCliConfig): IO[AgentError, EvalReleaseCliResult] =
    for
      snapshot <- EvalSnapshotArtifact.load(config.artifact)
      store    <- makeStore(config)
      decision <- EvalReleaseGate.evaluateAndAppend(store, snapshot, config.policy)
    yield EvalReleaseCliResult.fromDecision(decision)

  /** 根据 backend 创建可替换趋势 Store。 */
  private def makeStore(config: EvalReleaseGateCliConfig): IO[AgentError, EvalTrendStore] =
    config.backend match
      case EvalReleaseStoreBackend.File =>
        config.fileStore match
          case Some(fileConfig) => FileEvalTrendStore.make(fileConfig)
          case None             => ZIO.fail(invalid("file-config-missing"))
      case EvalReleaseStoreBackend.Postgres =>
        config.postgresStore match
          case Some(postgresConfig) => makePostgresStore(postgresConfig)
          case None                 => ZIO.fail(invalid("postgres-config-missing"))

  /** 创建一次性 PostgreSQL DataSource。
    *
    * JDBC 对象构造本身不打开网络连接；真正的 `getConnection` 在 `PostgresEvalTrendStore` 内部通过 `attemptBlockingInterrupt` 和
    * `Scope` 执行与释放。密码只在这里短暂展开并交给驱动。
    */
  private def makePostgresStore(config: EvalReleasePostgresConfig): IO[AgentError, EvalTrendStore] =
    ZIO
      .attempt {
        val dataSource = PGSimpleDataSource()
        dataSource.setUrl(config.jdbcUrl)
        dataSource.setUser(config.user)
        dataSource.setPassword(config.password.stringValue)
        dataSource.setConnectTimeout(config.connectTimeoutSeconds)
        PostgresEvalTrendStore(dataSource, config.storeConfig)
      }
      .mapError(_ => invalid("postgres-datasource-invalid"))

  /** CLI 装配错误使用稳定低敏 code。 */
  private def invalid(code: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(s"eval-release:$code")

/** 正式 CI/部署入口。
  *
  * ZIO 官方 `ZIOAppDefault` 负责 Scope、信号处理与 Fiber 生命周期；`exit(ExitCode)` 只存在于最外层 shell adapter，
  * 核心程序返回普通值，便于确定性测试。错误 JSON 写 stderr，门禁通过/拒绝 JSON 写 stdout。
  *
  * 运行示例：
  *
  * {{ sbt "evalCli/runMain com.zyblw.agent.evals.cli.EvalReleaseGateCli" }}
  */
object EvalReleaseGateCli extends ZIOAppDefault:
  /** SIGINT/SIGTERM 后最多等待十秒执行文件锁、JDBC 连接等 Scope finalizer。 */
  override def gracefulShutdownTimeout: Duration = 10.seconds

  /** 加载配置、执行门禁、打印单行 JSON，并使用稳定退出码结束独立 CLI 进程。
    *
    * Defect 不会被 `fold` 捕获后伪装成配置或回归；它仍由 ZIO runtime 记录 Cause 并以非零状态退出。
    */
  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for
      result <- EvalReleaseGateCliConfig
        .load()
        .flatMap(EvalReleaseGateCliProgram.run)
        .fold(EvalReleaseCliResult.fromError, identity)
      json = result.output.toJson
      _ <- result.output.status match
        case EvalReleaseCliStatus.Error => Console.printLineError(json)
        case _                          => Console.printLine(json)
      _ <- exit(result.exitCode)
    yield ()
