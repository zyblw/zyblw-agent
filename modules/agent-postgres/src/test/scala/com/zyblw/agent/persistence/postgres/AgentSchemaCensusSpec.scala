package com.zyblw.agent.persistence.postgres

import zio.Chunk
import zio.test.*

object AgentSchemaCensusSpec extends ZIOSpecDefault:
  def spec = suite("AgentSchemaCensus")(
    test("死投影表与权威表互斥，且包含 ModelCall/Artifact 权威面") {
      val dead   = AgentSchemaInventory.DeadProjections.toSet
      val living = AgentSchemaInventory.Authoritative.toSet
      assertTrue(
        dead.intersect(living).isEmpty,
        living.contains("model_call_executions"),
        living.contains("agent_artifacts"),
        dead.contains("model_calls"),
        AgentSchemaInventory.relations.size == dead.size + living.size
      )
    },
    test("census checksum 对相同行稳定，占用死表可被识别") {
      val emptyDead = SchemaCensusRow("model_calls", SchemaRelationKind.DeadProjection, true, Some(0L))
      val usedDead  = emptyDead.copy(rowCount = Some(3L))
      val run       = SchemaCensusRow("agent_runs", SchemaRelationKind.Authoritative, true, Some(10L))
      val first     = SchemaCensus(Chunk(run, emptyDead), "x")
      val occupied  = SchemaCensus(Chunk(run, usedDead), "y")
      assertTrue(
        first.deadOccupied.isEmpty,
        occupied.deadOccupied.map(_.name) == Chunk("model_calls"),
        occupied.missingAuthoritative.isEmpty
      )
    },
    test("SchemaManifest 导入核对拒绝死投影占用和权威行数漂移") {
      val run      = SchemaCensusRow("agent_runs", SchemaRelationKind.Authoritative, true, Some(10L))
      val empty    = SchemaCensusRow("model_calls", SchemaRelationKind.DeadProjection, true, Some(0L))
      val source   = SchemaCensus(Chunk(run, empty), "abc")
      val expected = AgentSchemaManifest.fromCensus(source, 1L)
      val drifted  = SchemaCensus(Chunk(run.copy(rowCount = Some(11L)), empty), "def")
      val occupied = SchemaCensus(Chunk(run, empty.copy(rowCount = Some(2L))), "ghi")
      assertTrue(
        AgentSchemaManifest.verifyImport(expected, source).isRight,
        AgentSchemaManifest.verifyImport(expected, drifted).isLeft,
        AgentSchemaManifest.verifyImport(expected, occupied).left.exists(_.contains("死投影"))
      )
    }
  )
