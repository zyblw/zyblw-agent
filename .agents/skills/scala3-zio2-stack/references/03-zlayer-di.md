# ZLayer Dependency Injection & Lifecycle Management

## 1. Composition with `ZLayer`
- Services are declared as traits with clear, narrow responsibilities.
- Implementations are provided via `ZLayer.fromFunction`, `ZLayer.fromZIO`, or `ZLayer.scoped`.
- Do NOT hide global singletons inside companion objects or application builders.
- Never fall back to in-memory fake stores in production entry points when the real database connection fails.

```scala
// Service contract
trait KnowledgeIndexService:
  def search(query: KnowledgeQuery): IO[RetrievalError, Chunk[DocumentChunk]]

// Implementation
final class PostgresKnowledgeIndexService(
  vectorStore: VectorStore,
  config: KnowledgeConfig
) extends KnowledgeIndexService:
  def search(query: KnowledgeQuery): IO[RetrievalError, Chunk[DocumentChunk]] =
    vectorStore.similaritySearch(query.embedding, query.topK, query.scope)

// Idiomatic Layer wiring
object PostgresKnowledgeIndexService:
  val live: ZLayer[VectorStore & KnowledgeConfig, Nothing, KnowledgeIndexService] =
    ZLayer.fromFunction(new PostgresKnowledgeIndexService(_, _))
```

## 2. Explicit Dependencies for Determinism
Never hardcode non-deterministic Java static methods in business or kernel logic. Always rely on ZIO's built-in, test-overridable services:

- **Time**: Use `Clock.instant` or `Clock.currentTime(TimeUnit.MILLISECONDS)` instead of `System.currentTimeMillis()` or `Instant.now()`.
- **Randomness**: Use `Random.nextUUID` or `Random.nextIntBounded` instead of `java.util.UUID.randomUUID()` or `scala.util.Random`.
- **Configuration**: Load configuration via typed `ZLayer` or `Config`, not via unparsed `sys.env.getOrElse(...)` scattered across deep methods.

```scala
// ❌ BAD: Hardcoded non-deterministic side-effects make tests non-reproducible
def createRun(agentId: AgentId): UIO[AgentRun] =
  ZIO.succeed(AgentRun(
    id = java.util.UUID.randomUUID().toString,
    createdAt = java.time.Instant.now()
  ))

// ✅ GOOD: Pure ZIO services can be deterministically frozen or advanced in ZIO Test
def createRun(agentId: AgentId): URIO[Random & Clock, AgentRun] =
  for
    uuid <- Random.nextUUID
    now  <- Clock.instant
  yield AgentRun(
    id = RunId(uuid.toString),
    createdAt = now
  )
```
