package com.zyblw.agent.app

import com.zyblw.agent.core.*
import com.zyblw.agent.scheduler.WorkerHostConfig
import com.zyblw.agent.tools.*
import zio.*

/** `AgentApplicationConfig` 的 ZIO Config 描述与启动期加载入口。
  *
  * 这个对象只读取不含密钥的框架治理参数。模型 API Key、数据库密码和遥测认证头应由对应 Adapter 的 `Config.Secret` 或部署平台 Secret Manager
  * 负责，不能混入本配置后被日志、配置快照或错误页意外打印。
  *
  * 默认路径是 `zyblw.agent`。使用 ZIO 默认环境变量 Provider 时，例如：
  *
  * {{ ZYBLW_AGENT_TOOL_ALLOWED_TOOLS=knowledge_search,article_draft
  * ZYBLW_AGENT_TOOL_APPROVAL_POLICY=risk-based ZYBLW_AGENT_WORKER_LEASE_DURATION=30s
  * ZYBLW_AGENT_WORKER_HEARTBEAT_EVERY=10s }}
  *
  * ZIO Config 的描述和值加载保持分离：测试可使用 `ConfigProvider.fromMap`，生产可使用环境变量、系统属性， 或由宿主替换成 HOCON/YAML/Secret
  * backend，而本模块无需知道配置究竟来自哪里。
  */
object AgentApplicationConfigLoader:
  /** 默认命名空间，避免与业务应用、数据库和 HTTP Server 的同名键发生碰撞。 */
  val DefaultPrefix: String = "zyblw.agent"

  /** 构造完整配置描述。
    *
    * @param prefix
    *   shell-safe 点分配置根路径；每段只使用字母、数字或下划线
    * @return
    *   可传给 `ZIO.config` 的纯描述值，不会在构造时读取环境
    */
  def description(prefix: String = DefaultPrefix): Config[AgentApplicationConfig] =
    ZioConfigPath.nested(
      (toolPolicyDescription ++ workerDescription).map(AgentApplicationConfig.apply),
      prefix
    )

  /** 从当前 Fiber 的 `ConfigProvider` 加载并校验配置。
    *
    * ZIO 原生 `Config.Error` 在模块边界被转换为框架的 typed `InvalidConfiguration`，这样业务启动程序只需处理统一的
    * `AgentError`；错误文本只包含配置路径和约束，不包含任何配置值。
    *
    * @param prefix
    *   与 [[description]] 相同的配置根路径
    */
  def load(prefix: String = DefaultPrefix): IO[AgentError.InvalidConfiguration, AgentApplicationConfig] =
    ZIO
      .config(description(prefix))
      .mapError(error => AgentError.InvalidConfiguration(s"Agent 应用配置无效: $error"))

  /** 把加载动作暴露成 ZLayer，便于宿主在启动图中先取得已验证配置，再调用 `AgentApplication.durable`。
    *
    * 注意：`AgentApplication.durable` 接收普通值而不是在 Layer 内偷偷读全局配置，目的是让装配代码和测试能够明确看到 最终生效策略；本 Layer 只是一个可选的加载辅助器。
    */
  def layer(prefix: String = DefaultPrefix): Layer[AgentError.InvalidConfiguration, AgentApplicationConfig] =
    ZLayer.fromZIO(load(prefix))

  /** 工具策略配置，路径位于 `<prefix>.tool.*`。 */
  private lazy val toolPolicyDescription: Config[ToolPolicyConfig] =
    val names =
      (
        Config.string("allowed_tools").withDefault("") ++
          Config.string("denied_tools").withDefault("")
      ).map(ToolNames.apply)
    val limits =
      (
        Config.int("max_calls_per_run").withDefault(32) ++
          Config.int("max_calls_per_step").withDefault(8) ++
          Config.int("max_parallelism").withDefault(4) ++
          Config.duration("default_timeout").withDefault(30.seconds) ++
          Config.long("max_result_bytes").withDefault(256L * 1024L)
      ).map(ToolLimits.apply)
    val policies = (approvalPolicyDescription ++ retryPolicyDescription).map(ToolPolicies.apply)

    (names ++ limits ++ policies)
      .mapAttempt { case (names, limits, policies) =>
        val allowed = parseToolNames(names.allowed)
        val denied  = parseToolNames(names.denied)
        require(allowed.intersect(denied).isEmpty, "同一工具不能同时出现在 allowed-tools 与 denied-tools")
        require(limits.defaultTimeout > Duration.Zero, "tool.default-timeout 必须大于零")
        ToolPolicyConfig(
          allowedTools = allowed,
          deniedTools = denied,
          maxCallsPerRun = limits.maxCallsPerRun,
          maxCallsPerStep = limits.maxCallsPerStep,
          maxParallelism = limits.maxParallelism,
          defaultTimeout = limits.defaultTimeout,
          maxResultBytes = limits.maxResultBytes,
          retryPolicy = policies.retry,
          approvalPolicy = policies.approval
        )
      }
      .nested("tool")

  /** 工具审批字符串采用稳定的小写协议，避免把 Scala enum 名直接暴露成部署契约。 */
  private lazy val approvalPolicyDescription: Config[ApprovalPolicy] =
    Config
      .string("approval_policy")
      .withDefault("risk-based")
      .mapAttempt {
        case value if normalized(value) == "never"      => ApprovalPolicy.Never
        case value if normalized(value) == "risk-based" => ApprovalPolicy.RiskBased
        case value if normalized(value) == "always"     => ApprovalPolicy.Always
        case _ => throw IllegalArgumentException("tool.approval-policy 仅支持 never、risk-based 或 always")
      }

  /** 工具自动重试策略。
    *
    * 即使 mode 为 `never`，其余字段仍会被解析和校验，使切换模式时不会突然暴露潜伏的非法值。Runtime 只会对工具元数据 明确声明为可自动重试的调用使用该策略；写副作用工具不能仅靠配置声称幂等。
    */
  private lazy val retryPolicyDescription: Config[ToolRetryPolicy] =
    (
      Config.string("mode").withDefault("never") ++
        Config.int("max_attempts").withDefault(3) ++
        Config.duration("initial_delay").withDefault(200.millis) ++
        Config.duration("max_delay").withDefault(5.seconds) ++
        Config.double("jitter").withDefault(0.2) ++
        Config.duration("max_elapsed").withDefault(20.seconds)
    ).mapAttempt { case (mode, maxAttempts, initialDelay, maxDelay, jitter, maxElapsed) =>
      require(initialDelay >= Duration.Zero, "tool.retry.initial-delay 不能为负")
      require(maxDelay >= initialDelay, "tool.retry.max-delay 不能小于 initial-delay")
      require(maxElapsed > Duration.Zero, "tool.retry.max-elapsed 必须大于零")
      val retry = RetryPolicy(maxAttempts, initialDelay, maxDelay, jitter, maxElapsed)
      normalized(mode) match
        case "never"           => ToolRetryPolicy.Never
        case "idempotent-only" => ToolRetryPolicy.IdempotentOnly(retry)
        case _ => throw IllegalArgumentException("tool.retry.mode 仅支持 never 或 idempotent-only")
    }.nested("retry")

  /** Worker 调度配置，路径位于 `<prefix>.worker.*`。 */
  private lazy val workerDescription: Config[WorkerHostConfig] =
    (
      Config.duration("lease_duration").withDefault(30.seconds) ++
        Config.duration("heartbeat_every").withDefault(10.seconds) ++
        Config.duration("poll_every").withDefault(500.millis) ++
        Config.duration("retry_delay").withDefault(5.seconds) ++
        Config.int("max_attempts").withDefault(8)
    ).mapAttempt(WorkerHostConfig.apply).nested("worker")

  /** 把逗号分隔的部署值转换为去重后的类型化工具名，并拒绝控制字符或异常长度。 */
  private def parseToolNames(raw: String): Set[ToolName] =
    raw
      .split(',')
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { value =>
        require(value.length <= 200 && !value.exists(_.isControl), "工具名不能超过 200 字符或包含控制字符")
        ToolName(value)
      }
      .toSet

  /** 配置协议统一忽略首尾空格、大小写，并接受下划线作为连字符的部署友好别名。 */
  private def normalized(value: String): String = value.trim.toLowerCase.replace('_', '-')

  /** 仅用于保持大型 Config 组合的静态类型，不会进入公开 API 或运行状态。 */
  final private case class ToolNames(allowed: String, denied: String)

  /** 工具资源硬限制的中间配置产品。 */
  final private case class ToolLimits(
      maxCallsPerRun: Int,
      maxCallsPerStep: Int,
      maxParallelism: Int,
      defaultTimeout: Duration,
      maxResultBytes: Long
  )

  /** 审批与重试策略的中间配置产品。 */
  final private case class ToolPolicies(approval: ApprovalPolicy, retry: ToolRetryPolicy)
