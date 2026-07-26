package com.zyblw.agent.core

import zio.*
import zio.test.*

/** 防止配置路径再次退化为含连字符的单一 segment。
  *
  * 真实 fork CLI 已覆盖默认环境 Provider；这里用可替换 Map Provider 精确验证点分路径展开，并确保不安全 prefix 在应用启动 描述构造阶段就失败。
  */
object ZioConfigPathSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ZioConfigPath")(
    test("点分 prefix 展开为多个真实 segment，snake_case 叶子保持稳定") {
      val description =
        ZioConfigPath.nested(Config.string("service_name"), "zyblw.agent.http.host")
      ZIO
        .config(description)
        .provide(
          Runtime.setConfigProvider(
            ConfigProvider.fromMap(
              Map("zyblw.agent.http.host.service_name" -> "knowledge-agent")
            )
          )
        )
        .map(value => assertTrue(value == "knowledge-agent"))
    },
    test("空段、连字符和把点分路径误当成单一不安全 segment 都会被拒绝") {
      val invalidPrefixes = Chunk(
        "",
        "zyblw..agent",
        "zyblw-agent",
        "zyblw.agent.http-host"
      )
      ZIO
        .foreach(invalidPrefixes)(prefix =>
          ZIO.attempt(ZioConfigPath.nested(Config.string("value"), prefix)).exit
        )
        .map(exits => assertTrue(exits.forall(_.isFailure)))
    }
  )
