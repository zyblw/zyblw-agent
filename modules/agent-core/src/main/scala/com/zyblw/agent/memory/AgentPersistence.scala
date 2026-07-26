package com.zyblw.agent.memory

import zio.*

/** 单进程开发所需持久化 SPI 的便捷装配入口。
  *
  * `ZLayer.make` 会先构造唯一 RunStore/RunCommandStore，再把同一实例注入 RunSubmissionStore；因此业务测试不会因手工 `provide`
  * 不慎创建两套互不相识的内存状态。生产环境应改用 PostgreSQL 组合层。
  */
object AgentPersistence:
  /** 创建进程内状态、命令队列和 Start 原子提交服务。 每次提供该 Layer 都会得到一套隔离状态，适合测试，不适合作为多副本生产存储。
    */
  val inMemory: ULayer[RunStore & RunCommandStore & RunSubmissionStore] =
    ZLayer.make[RunStore & RunCommandStore & RunSubmissionStore](
      RunStore.inMemory,
      RunCommandStore.inMemory,
      RunSubmissionStore.inMemory
    )
