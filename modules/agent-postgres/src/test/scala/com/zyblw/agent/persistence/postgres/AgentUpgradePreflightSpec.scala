package com.zyblw.agent.persistence.postgres

import zio.Chunk
import zio.test.*

/** 升级预检的纯判定契约；真实 SQL 由 PostgreSQL 集成套件覆盖。 */
object AgentUpgradePreflightSpec extends ZIOSpecDefault:
  private val emptySchema = AgentPostgresSchemaStatus(
    historyPresent = false,
    currentVersion = None,
    pendingCount = 0,
    pendingVersions = Chunk.empty,
    appliedCount = 0
  )

  def spec = suite("AgentUpgradePreflight")(
    test("空库不要求 drain") {
      val readiness = AgentUpgradeReadiness(emptySchema, 0L, 0L, 0L, 0L)
      assertTrue(!readiness.drainRequired, !readiness.schema.hasPending)
    },
    test("进行中 Run、排队或已领取命令都要求 drain") {
      assertTrue(
        AgentUpgradeReadiness(emptySchema, 1L, 0L, 0L, 0L).drainRequired,
        AgentUpgradeReadiness(emptySchema, 1L, 1L, 0L, 0L).drainRequired,
        AgentUpgradeReadiness(emptySchema, 0L, 0L, 2L, 0L).drainRequired,
        AgentUpgradeReadiness(emptySchema, 0L, 0L, 0L, 1L).drainRequired
      )
    },
    test("schema 状态把 pending 列表和计数绑在一起") {
      val pending = AgentPostgresSchemaStatus(
        historyPresent = true,
        currentVersion = Some("3"),
        pendingCount = 2,
        pendingVersions = Chunk("4", "5"),
        appliedCount = 3
      )
      assertTrue(pending.hasPending, pending.pendingVersions == Chunk("4", "5"))
    }
  )
