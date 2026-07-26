package com.zyblw.agent.http.host

import com.zyblw.agent.core.{AgentError, ZioConfigPath}
import zio.*

/** Agent HTTP 宿主自身的低敏配置。
  *
  * 端口、TLS、压缩、空闲超时和 graceful shutdown 属于 ZIO HTTP `Server.Config`，不在这里重复定义；本配置只描述健康 响应中的发布信息和 readiness
  * 探测预算。它不允许保存 API Key、数据库 URL、Authorization 或用户正文。
  *
  * @param serviceName
  *   服务稳定名称，例如 `zyblw-agent-worker`
  * @param serviceVersion
  *   发布版本或镜像 digest 的低敏标签
  * @param environment
  *   部署环境，例如 `development`、`staging`、`production`
  * @param readinessTimeout
  *   每次 readiness 依赖检查的硬超时，防止健康探针占满数据库连接池
  */
final case class AgentHttpHostConfig(
    serviceName: String = "zyblw-agent",
    serviceVersion: String = "development",
    environment: String = "development",
    readinessTimeout: Duration = 2.seconds
):
  AgentHttpHostConfig.validateLabel("serviceName", serviceName)
  AgentHttpHostConfig.validateLabel("serviceVersion", serviceVersion)
  AgentHttpHostConfig.validateLabel("environment", environment)
  require(
    readinessTimeout > Duration.Zero && readinessTimeout <= 30.seconds,
    "readinessTimeout 必须位于 (0, 30s]"
  )

object AgentHttpHostConfig:
  /** 默认 shell-safe 点分路径，对应环境变量前缀 `ZYBLW_AGENT_HTTP_HOST_`。 */
  val DefaultPrefix: String = "zyblw.agent.http.host"

  /** 构造可替换的 ZIO Config 描述。
    *
    * @param prefix
    *   配置根路径；测试可使用自定义前缀隔离多个宿主
    */
  def description(prefix: String = DefaultPrefix): Config[AgentHttpHostConfig] =
    ZioConfigPath.nested(
      (
        Config.string("service_name").withDefault("zyblw-agent") ++
          Config.string("service_version").withDefault("development") ++
          Config.string("environment").withDefault("development") ++
          Config.duration("readiness_timeout").withDefault(2.seconds)
      ).mapAttempt(AgentHttpHostConfig.apply),
      prefix
    )

  /** 从当前 ConfigProvider 加载并把错误收敛为框架 typed configuration error。 */
  def load(prefix: String = DefaultPrefix): IO[AgentError.InvalidConfiguration, AgentHttpHostConfig] =
    ZIO
      .config(description(prefix))
      .mapError(error => AgentError.InvalidConfiguration(s"Agent HTTP Host 配置无效: $error"))

  /** 配置加载层；宿主也可以直接 `ZLayer.succeed` 注入程序化配置。 */
  def layer(prefix: String = DefaultPrefix): Layer[AgentError.InvalidConfiguration, AgentHttpHostConfig] =
    ZLayer.fromZIO(load(prefix))

  /** 健康响应标签必须短小、无控制字符，避免响应拆分和高基数诊断。 */
  private def validateLabel(name: String, value: String): Unit =
    require(
      value.trim.nonEmpty && value.length <= 128 && !value.exists(_.isControl),
      s"$name 必须为 1..128 个无控制字符文本"
    )
