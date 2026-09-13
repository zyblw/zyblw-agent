package com.zyblw.agent.persistence.postgres

import zio.test.*
import zio.test.Assertion.*

/** 不依赖 Docker 的迁移计划契约；真实建表、扩展版本和向量类型由 PostgreSQL 集成套件验证。 */
object AgentPostgresMigrationsSpec extends ZIOSpecDefault:

  def spec = suite("AgentPostgresMigrations")(
    test("核心与知识库使用独立 location、schema 和 history") {
      val knowledge = AgentPostgresMigrationConfig.knowledge1024
      assertTrue(
        knowledge.locations == List(AgentPostgresMigrations.OptionalPgVector1024Location),
        AgentPostgresMigrations.Knowledge1024Schema == "zyblw_agent_knowledge",
        knowledge.historyTable == AgentPostgresMigrations.Knowledge1024HistoryTable,
        knowledge.historyTable != AgentPostgresMigrations.DefaultHistoryTable
      )
    },
    test("拒绝把两个 V001 放进同一个 Flyway history") {
      val invalid = AgentPostgresMigrationConfig(
        locations = List(
          AgentPostgresMigrations.DefaultLocation,
          AgentPostgresMigrations.OptionalPgVector1024Location
        )
      )
      assertZIO(
        AgentPostgresMigrations.migrate(null, invalid).exit
      )(fails(isSubtype[IllegalArgumentException](anything)))
    },
    test("拒绝从通用入口绕过知识库专属 schema") {
      val invalid = AgentPostgresMigrationConfig(
        locations = List(AgentPostgresMigrations.OptionalPgVector1024Location)
      )
      assertZIO(
        AgentPostgresMigrations.migrate(null, invalid).exit
      )(fails(isSubtype[IllegalArgumentException](anything)))
    },
    test("拒绝用 baselineOnMigrate 接管未知 schema") {
      val invalid = AgentPostgresMigrationConfig(baselineOnMigrate = true)
      assertTrue(invalid.validated.isLeft)
    },
    test("宿主共享 public schema 只能使用受限的 version 0 baseline") {
      val shared = AgentPostgresMigrationConfig.sharedPublicSchema
      assertTrue(
        shared.validated.isRight,
        shared.isSharedPublicSchemaBaseline,
        shared.baselineVersion.contains("0")
      )
    },
    test("0.9 核心与知识库各只有一个版本化 baseline") {
      val coreV001 = Option(
        getClass.getResource(
          "/com/zyblw/agent/persistence/postgres/migration/V001__zyblw_agent_0_9_baseline.sql"
        )
      )
      val knowledgeV001 = Option(
        getClass.getResource(
          "/com/zyblw/agent/persistence/postgres/optional/pgvector_1024/V001__agent_knowledge_0_9_baseline.sql"
        )
      )
      val leftover = List(
        "/com/zyblw/agent/persistence/postgres/migration/V001__zyblw_agent_0_3_baseline.sql",
        "/com/zyblw/agent/persistence/postgres/migration/V001__zyblw_agent_0_8_baseline.sql",
        "/com/zyblw/agent/persistence/postgres/migration/V012__drop_dead_projection_tables.sql",
        "/com/zyblw/agent/persistence/postgres/migration/V013__artifact_blob_offload.sql",
        "/com/zyblw/agent/persistence/postgres/optional/pgvector_1024/V001__agent_knowledge_0_8_baseline.sql",
        "/com/zyblw/agent/persistence/postgres/optional/pgvector_1024_v0_6/V001__agent_knowledge_pgvector_1024_baseline.sql"
      ).flatMap(path => Option(getClass.getResource(path)))
      assertTrue(coreV001.nonEmpty, knowledgeV001.nonEmpty, leftover.isEmpty)
    },
    test("inspect 在接触 DataSource 前拒绝非法 history 表名") {
      val invalid = AgentPostgresMigrationConfig(historyTable = "not a table")
      assertZIO(AgentPostgresMigrations.inspect(null, invalid).exit)(
        fails(isSubtype[IllegalArgumentException](anything))
      )
    },
    test("知识 inspect 在接触 DataSource 前拒绝非法 history 表名") {
      val invalid = AgentPostgresMigrationConfig.knowledge1024.copy(historyTable = "not a table")
      assertZIO(AgentPostgresMigrations.inspectKnowledge1024(null, invalid).exit)(
        fails(isSubtype[IllegalArgumentException](anything))
      )
    }
  )
