# ZIO 2 Effects, Error Channels & Concurrency

## 1. Precision Error Channels (No `Throwable` Soup)
- In domain services and kernel interfaces, `E` MUST be a typed domain ADT (typically `E <: AgentError` or a localized `enum`).
- Never return `Task[A]` (which is `ZIO[Any, Throwable, A]`) in public domain traits.
- External boundary exceptions (JDBC `SQLException`, HTTP `IOException`, Netty defects) must be caught and translated immediately at the infrastructure boundary using `.mapError` or `.refineOrDie`.

```scala
// ❌ BAD: Leaking raw Throwable, swallowing fatal JVM errors, and hiding domain causes
def executePrompt(prompt: String): Task[String] =
  ZIO.attempt {
    externalClient.call(prompt)
  }.catchAll(e => ZIO.succeed("fallback")) // Swallows InterruptedException and OutOfMemoryError!

// ✅ GOOD: Explicit domain error ADT and precise translation
enum ProviderError extends AgentError:
  case RateLimited(retryAfter: Duration)
  case ContextWindowExceeded(tokens: Int, maxTokens: Int)
  case UpstreamFailure(statusCode: Int, message: String)

def executePrompt(prompt: String): IO[ProviderError, String] =
  ZIO.attemptBlocking(externalClient.call(prompt))
    .mapError {
      case e: RateLimitException => ProviderError.RateLimited(e.retryAfter)
      case e: ContextExceededException => ProviderError.ContextWindowExceeded(e.tokens, e.max)
      case e => ProviderError.UpstreamFailure(500, e.getMessage)
    }
```

## 2. Bounded Concurrency & Backpressure
Never invoke unbounded parallel combinators (`ZIO.foreachPar`, `ZIO.collectAllPar`) on dynamic collections or external requests. Unbounded parallelism rapidly exhausts database connection pools, HTTP client worker pools, and LLM rate limits.

```scala
// ❌ BAD: Unbounded parallel processing can spike thousands of fibers concurrently
ZIO.foreachPar(chunks)(vectorStore.indexChunk)

// ✅ GOOD: Always bound parallelism explicitly
ZIO.foreachPar(chunks)(vectorStore.indexChunk)
  .withParallelism(8)
```

## 3. Resource Lifecycle & Fiber Interruption Safety
Every closeable resource (JDBC `Connection`, `ResultSet`, file stream, HTTP streaming connection) MUST be managed via `ZIO.acquireRelease` within a `Scope`.

```scala
// ✅ GOOD: Guaranteed cleanup even if the parent Fiber is interrupted or canceled
def managedConnection(ds: DataSource): ZIO[Scope, StorageError, Connection] =
  ZIO.acquireRelease(
    ZIO.attemptBlocking(ds.getConnection).mapError(StorageError.fromSqlException)
  )(conn => ZIO.attemptBlocking(conn.close()).orDie)
```

## 4. Effect Discard Vigilance (`-Wvalue-discard` & `-Wnonunit-statement`)
In pure functional programming with ZIO, an effect is a lazy description of execution. It does NOT run unless bound in a `for`-comprehension or `flatMap`.

```scala
// ❌ CRITICAL BUG: Unexecuted effect (silently ignored in Scala without compiler guardrails!)
for
  user   <- loadUser(userId)
  _      = ZIO.logInfo(s"Loaded user ${user.id}") // BUG: '=' creates a pure unexecuted effect!
  result <- process(user)
yield result

// ✅ GOOD: Correctly bound into the monadic effect chain '<-'
for
  user   <- loadUser(userId)
  _      <- ZIO.logInfo(s"Loaded user ${user.id}") // Executed in order
  result <- process(user)
yield result
```
The codebase enforces `-Wvalue-discard` and `-Wnonunit-statement`. Any un-bound effect or discarded value will immediately fail or warn during `sbt compile`.
