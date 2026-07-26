package com.zyblw.agent.knowledge

import com.zyblw.agent.core.*
import zio.*

final case class Entity(
    id: String,
    kind: String,
    label: String,
    properties: Map[String, String],
    tenantId: TenantId
)
final case class Relation(
    id: String,
    from: String,
    to: String,
    kind: String,
    properties: Map[String, String],
    tenantId: TenantId
)
final case class GraphNeighborhood(entities: Chunk[Entity], relations: Chunk[Relation])

trait KnowledgeGraph:
  /** 批量 upsert 实体；实现主键必须包含 tenantId。 */
  def upsertEntities(values: Chunk[Entity]): IO[StoreError, Unit]

  /** 批量 upsert 实体关系。 */
  def upsertRelations(values: Chunk[Relation]): IO[StoreError, Unit]

  /** 在租户内查询指定实体的有限深度邻域。 */
  def neighborhood(
      entityId: String,
      tenantId: TenantId,
      depth: Int,
      limit: Int
  ): IO[RetrievalError, GraphNeighborhood]

object KnowledgeGraph:
  val inMemory: ULayer[KnowledgeGraph] = ZLayer.fromZIO {
    for
      entities  <- Ref.Synchronized.make(Map.empty[(TenantId, String), Entity])
      relations <- Ref.Synchronized.make(Map.empty[(TenantId, String), Relation])
    yield new KnowledgeGraph:
      def upsertEntities(values: Chunk[Entity]): UIO[Unit] =
        entities.update(_ ++ values.map(value => (value.tenantId -> value.id) -> value))
      def upsertRelations(values: Chunk[Relation]): UIO[Unit] =
        relations.update(_ ++ values.map(value => (value.tenantId -> value.id) -> value))
      def neighborhood(
          entityId: String,
          tenantId: TenantId,
          depth: Int,
          limit: Int
      ): IO[RetrievalError, GraphNeighborhood] =
        if depth < 0 || depth > 4 then ZIO.fail(AgentError.RetrievalFailed("知识图谱 depth 必须在 0..4"))
        else
          for
            allEntities  <- entities.get
            allRelations <- relations.get
            tenantRelations = Chunk.fromIterable(allRelations.values.filter(_.tenantId == tenantId))
            connectedIds    = tenantRelations
              .filter(r => r.from == entityId || r.to == entityId)
              .flatMap(r => Chunk(r.from, r.to))
              .toSet
            selected = Chunk.fromIterable(
              allEntities.values
                .filter(entity => entity.tenantId == tenantId && connectedIds.contains(entity.id))
                .take(limit)
            )
          yield GraphNeighborhood(
            selected,
            tenantRelations
              .filter(r => connectedIds.contains(r.from) || connectedIds.contains(r.to))
              .take(limit)
          )
  }
