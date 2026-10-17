# PostgreSQL & pgvector Storage Guidelines

## 1. Strict Parameterization & Boundary Mapping
- **NEVER** interpolate raw strings into SQL queries (`s"SELECT * FROM ... WHERE id = '$id'"`). Always use parameterized `PreparedStatement` or type-safe SQL interpolators.
- Map `opaque type` and `enum` strictly at the Repository boundary. Do NOT leak JDBC `ResultSet` or raw database rows outside the Repository implementation.
- All database I/O must be executed via `ZIO.attemptBlocking` and translated to typed `StorageError`.

## 2. pgvector Similarity Search Rules
- **Explicit Dimension Enforcement**: Embedding vectors must validate dimension length at construction time before reaching SQL persistence (`require(dimension > 0)`).
- **Tenant & Permission Pre-filtering**: In multi-tenant systems, `tenant_id = ?` and `permissions <@ caller_permissions` MUST take effect in the `WHERE` clause BEFORE vector ordering, preventing unauthorized candidates from consuming index scan slots.
- **Distance Operator Consistency**:
  - Use `<=>` (Cosine distance) for normalized text embeddings.
  - Always pair with `ORDER BY embedding <=> ? LIMIT ?` so pgvector HNSW / IVFFlat indexes are actively leveraged.

```scala
// ✅ GOOD: Parameterized pgvector search with tenant security and HNSW index ordering
def searchSimilarChunks(
  connection: Connection,
  scope: RetrievalScope,
  queryVector: EmbeddingVector,
  limit: Int,
  maxDistance: Double
): Chunk[ScoredChunk] =
  val sql =
    """SELECT chunk_id, document_id, chunk_text, (embedding <=> ?::vector) AS distance
      |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
      |WHERE tenant_id = ?
      |  AND knowledge_space_id = ?
      |  AND (embedding <=> ?::vector) <= ?
      |ORDER BY embedding <=> ?::vector
      |LIMIT ?""".stripMargin

  val statement = connection.prepareStatement(sql)
  try
    statement.setString(1, queryVector.asPgVectorString)
    statement.setString(2, scope.tenantId.value)
    statement.setString(3, scope.spaceId.value)
    statement.setString(4, queryVector.asPgVectorString)
    statement.setDouble(5, maxDistance)
    statement.setString(6, queryVector.asPgVectorString)
    statement.setInt(7, limit)

    val rs = statement.executeQuery()
    try
      val builder = ChunkBuilder.make[ScoredChunk]()
      while rs.next() do
        builder += ScoredChunk(
          chunkId = ChunkId(rs.getString("chunk_id")),
          documentId = DocumentId(rs.getString("document_id")),
          text = rs.getString("chunk_text"),
          score = 1.0 - rs.getDouble("distance")
        )
      builder.result()
    finally rs.close()
  finally statement.close()
```

## 3. Durable Control Plane & Transactional Patterns
- **Transactional Outbox / Inbox**: Write business mutations, tool state changes, and outbox messages within the SAME local JDBC transaction (`connection.setAutoCommit(false)` -> `commit()` / `rollback()`).
- **Fencing & Leases**: For distributed workers and command processing, always enforce optimistic fencing or monotonic version leases (`version = version + 1 WHERE version = expectedVersion`) to prevent split-brain execution across nodes.
- **Workflow Checkpointing**: State snapshots must be serialized into versioned JSON alongside monotonic event streams.
