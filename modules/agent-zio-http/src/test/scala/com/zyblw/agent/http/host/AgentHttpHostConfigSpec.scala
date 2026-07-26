package com.zyblw.agent.http.host

import zio.*
import zio.test.*

/** 验证 Host 的 ZIO Config 路径、默认值和启动期边界。 */
object AgentHttpHostConfigSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentHttpHostConfig")(
    test("从默认前缀加载发布标签与 readiness 超时") {
      val values = Map(
        "zyblw.agent.http.host.service_name"      -> "knowledge-agent",
        "zyblw.agent.http.host.service_version"   -> "2026.07.15",
        "zyblw.agent.http.host.environment"       -> "staging",
        "zyblw.agent.http.host.readiness_timeout" -> "1500ms"
      )
      AgentHttpHostConfig
        .load()
        .provide(provider(values))
        .map(config =>
          assertTrue(
            config.serviceName == "knowledge-agent",
            config.serviceVersion == "2026.07.15",
            config.environment == "staging",
            config.readinessTimeout == 1500.millis
          )
        )
    },
    test("拒绝控制字符、空标签与过长 readiness 超时") {
      val badLabel   = Map("zyblw.agent.http.host.service_name" -> "bad\nheader")
      val badTimeout = Map("zyblw.agent.http.host.readiness_timeout" -> "31s")
      for
        labelExit   <- AgentHttpHostConfig.load().provide(provider(badLabel)).exit
        timeoutExit <- AgentHttpHostConfig.load().provide(provider(badTimeout)).exit
      yield assertTrue(labelExit.isFailure, timeoutExit.isFailure)
    },
    test("自定义前缀允许一个进程隔离多套 Host 配置") {
      AgentHttpHostConfig
        .load("research.host")
        .provide(
          provider(Map("research.host.service_name" -> "research-agent"))
        )
        .map(config => assertTrue(config.serviceName == "research-agent"))
    }
  )

  /** 安装 Fiber-local 测试配置源，不修改真实环境变量。 */
  private def provider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
