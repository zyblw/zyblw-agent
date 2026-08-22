package com.zyblw.agent.persistence.postgres

import zio.*
import zio.json.*

/** 只读普查的可序列化清单。不含表正文，只保存关系名、种类、存在性和行数。 */
final case class SchemaManifestRow(
    name: String,
    kind: String,
    exists: Boolean,
    rowCount: Option[Long]
) derives JsonCodec

/** 切换下一 fresh-install 基线前的导出核对物。 */
final case class SchemaManifest(
    generatedAtEpochMilli: Long,
    checksum: String,
    rows: Chunk[SchemaManifestRow]
) derives JsonCodec:
  def authoritativeCounts: Map[String, Long] =
    rows.iterator
      .filter(row => row.kind == SchemaRelationKind.Authoritative.toString && row.exists)
      .flatMap(row => row.rowCount.map(row.name -> _))
      .toMap

  def deadOccupied: Chunk[SchemaManifestRow] =
    Chunk.fromIterable(
      rows.filter(row =>
        row.kind == SchemaRelationKind.DeadProjection.toString && row.rowCount.exists(_ > 0L)
      )
    )

object AgentSchemaManifest:
  def fromCensus(census: SchemaCensus, generatedAtEpochMilli: Long): SchemaManifest =
    SchemaManifest(
      generatedAtEpochMilli = generatedAtEpochMilli,
      checksum = census.checksum,
      rows = census.rows.map(row => SchemaManifestRow(row.name, row.kind.toString, row.exists, row.rowCount))
    )

  /** 导入后核对：权威表行数必须相等，死投影必须仍为空，缺失权威表立即失败。 */
  def verifyImport(expected: SchemaManifest, actual: SchemaCensus): Either[String, Unit] =
    val actualManifest = fromCensus(actual, expected.generatedAtEpochMilli)
    val missing        = actual.missingAuthoritative.map(_.name)
    val occupied       = actual.deadOccupied.map(_.name)
    val expectedCounts = expected.authoritativeCounts
    val actualCounts   = actualManifest.authoritativeCounts
    val drifted        = expectedCounts.collect {
      case (name, count) if actualCounts.get(name) != Some(count) =>
        s"$name expected=$count actual=${actualCounts.get(name).map(_.toString).getOrElse("missing")}"
    }.toList
    val problems =
      Option.when(missing.nonEmpty)(s"缺失权威表: ${missing.mkString(",")}") ++
        Option.when(occupied.nonEmpty)(s"死投影仍有数据: ${occupied.mkString(",")}") ++
        Option.when(drifted.nonEmpty)(s"权威表行数不一致: ${drifted.mkString(";")}")
    Either.cond(problems.isEmpty, (), problems.mkString(" | "))
