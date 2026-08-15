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
    }
  )
