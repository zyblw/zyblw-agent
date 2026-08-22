package com.zyblw.agent.persistence.postgres

import zio.test.*

/** 用 JVM 环境变量或 `-DRUN_POSTGRES_INTEGRATION` 决定是否跑 Testcontainers。
  *
  * 不用 `TestAspect.ifEnvSet`：ZIO `TestSystem` 默认看不到真实环境。也不只读 `getenv`：复用中的 sbt server 可能在未导出该变量时启动。
  */
object PostgresIntegrationAspect:
  def enabled: TestAspect[Nothing, Any, Nothing, Any] =
    val flag =
      Option(java.lang.System.getenv("RUN_POSTGRES_INTEGRATION"))
        .orElse(Option(java.lang.System.getProperty("RUN_POSTGRES_INTEGRATION")))
        .exists(_.nonEmpty)
    if flag then TestAspect.identity else TestAspect.ignore
