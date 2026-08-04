package com.zyblw.agent.persistence.postgres

import zio.test.*
import zio.test.Assertion.*

/** 不依赖 Docker 的迁移计划契约；真实建表、扩展版本和向量类型由 PostgreSQL 集成套件验证。 */
object AgentPostgresMigrationsSpec extends ZIOSpecDefault:

  def spec = suite("AgentPostgresMigrations")(
    test("核心与知识库使用独立 location、schema 和 history") {
      val knowledge = AgentPostgresMigrationConfig.knowledge1536
      assertTrue(
        knowledge.locations == List(AgentPostgresMigrations.OptionalPgVectorLocation),
        AgentPostgresMigrations.Knowledge1536Schema == "zyblw_agent_knowledge",
        knowledge.historyTable == AgentPostgresMigrations.Knowledge1536HistoryTable,
        knowledge.historyTable != AgentPostgresMigrations.DefaultHistoryTable
      )
    },
    test("拒绝把两个 V001 放进同一个 Flyway history") {
      val invalid = AgentPostgresMigrationConfig(
        locations = List(
          AgentPostgresMigrations.DefaultLocation,
          AgentPostgresMigrations.OptionalPgVectorLocation
        )
      )
      assertZIO(
        AgentPostgresMigrations.migrate(null, invalid).exit
      )(fails(isSubtype[IllegalArgumentException](anything)))
    },
    test("拒绝从通用入口绕过知识库专属 schema") {
      val invalid = AgentPostgresMigrationConfig(
        locations = List(AgentPostgresMigrations.OptionalPgVectorLocation)
      )
      assertZIO(
        AgentPostgresMigrations.migrate(null, invalid).exit
      )(fails(isSubtype[IllegalArgumentException](anything)))
    },
    test("拒绝用 baselineOnMigrate 接管未知 schema") {
      val invalid = AgentPostgresMigrationConfig(baselineOnMigrate = true)
      assertTrue(invalid.validated.isLeft)
    }
  )
