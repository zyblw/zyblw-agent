package com.zyblw.agent.examples.production

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.scheduler.WorkerHostConfig
import com.zyblw.agent.tools.*
import zio.*

/** 生产参考宿主的启动期配置。密钥只以环境变量名出现，从不写入本对象的字符串字段。 */
final case class ProductionSupportConfig(
    command: ProductionSupportCommand,
    mode: ProductionSupportMode,
    httpPort: Int,
    workerId: String,
    jdbcUrl: Option[String],
    dbUser: Option[String],
    dbPasswordEnv: Option[String],
    authMode: ProductionAuthMode,
    otlpEndpoint: Option[String],
    worker: WorkerHostConfig = WorkerHostConfig()
):
  val application: AgentApplicationConfig = AgentApplicationConfig(
    toolPolicy = ToolPolicyConfig(
      allowedTools = Set(ToolName("lookup_order"), ToolName("issue_refund")),
      approvalPolicy = ApprovalPolicy.RiskBased
    ),
    worker = worker
  )

  def requireDurableDatabase: IO[AgentError.InvalidConfiguration, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration("live 模式必须提供 ZYBLW_AGENT_JDBC_URL、ZYBLW_AGENT_DB_USER 与密码环境变量"))
      .unless(jdbcUrl.exists(_.nonEmpty) && dbUser.exists(_.nonEmpty) && dbPasswordEnv.exists(_.nonEmpty))
      .unit

enum ProductionSupportCommand:
  case Migrate, Serve, Status, All

enum ProductionSupportMode:
  case Live, Contract

enum ProductionAuthMode:
  case TrustedHeaders, AnonymousContract

object ProductionSupportConfig:
  def load(args: Chunk[String]): IO[AgentError.InvalidConfiguration, ProductionSupportConfig] =
    ZIO
      .attempt {
        val command = args.headOption.map(_.trim.toLowerCase) match
          case None | Some("serve") => ProductionSupportCommand.Serve
          case Some("migrate")      => ProductionSupportCommand.Migrate
          case Some("status")       => ProductionSupportCommand.Status
          case Some("all")          => ProductionSupportCommand.All
          case Some(other)          =>
            throw IllegalArgumentException(s"未知命令 $other；仅支持 migrate、serve、status 或 all")
        val mode = env("ZYBLW_AGENT_RUNTIME_MODE").getOrElse("live").toLowerCase match
          case "live"     => ProductionSupportMode.Live
          case "contract" => ProductionSupportMode.Contract
          case other      =>
            throw IllegalArgumentException(s"ZYBLW_AGENT_RUNTIME_MODE 仅支持 live 或 contract，实际为 $other")
        val auth = env("ZYBLW_AGENT_AUTH_MODE").getOrElse(
          if mode == ProductionSupportMode.Live then "trusted-headers" else "anonymous-contract"
        ) match
          case "trusted-headers"    => ProductionAuthMode.TrustedHeaders
          case "anonymous-contract" => ProductionAuthMode.AnonymousContract
          case other                =>
            throw IllegalArgumentException(
              s"ZYBLW_AGENT_AUTH_MODE 仅支持 trusted-headers 或 anonymous-contract，实际为 $other"
            )
        if mode == ProductionSupportMode.Live && auth == ProductionAuthMode.AnonymousContract then
          throw IllegalArgumentException("live 模式禁止匿名身份；请由可信反代写入已验签身份头")
        ProductionSupportConfig(
          command = command,
          mode = mode,
          httpPort = env("ZYBLW_AGENT_HTTP_PORT").map(_.toInt).getOrElse(8080),
          workerId = env("ZYBLW_AGENT_WORKER_ID")
            .filter(_.nonEmpty)
            .getOrElse(s"support-host-${java.util.UUID.randomUUID()}"),
          jdbcUrl = env("ZYBLW_AGENT_JDBC_URL"),
          dbUser = env("ZYBLW_AGENT_DB_USER"),
          dbPasswordEnv = env("ZYBLW_AGENT_DB_PASSWORD_ENV").orElse(Some("ZYBLW_AGENT_DB_PASSWORD")),
          authMode = auth,
          otlpEndpoint = env("OTEL_EXPORTER_OTLP_ENDPOINT")
        )
      }
      .mapError(error => AgentError.InvalidConfiguration(error.getMessage))

  private def env(name: String): Option[String] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty)
