# Domain Modeling in Scala 3

## 1. Opaque Types for Zero-Cost Domain Primitives
Never pass raw primitives (`String`, `Int`, `Long`, `UUID`) for domain identifiers, names, dimensions, or bounded quantities. Use Scala 3 `opaque type` with smart constructors and companion extension methods.

```scala
// ❌ BAD: Primitive obsession allows swapping arguments accidentally
def findChunks(
  tenantId: String,
  spaceId: String,
  profileId: String,
  query: String,
  topK: Int
): IO[AgentError, Chunk[DocumentChunk]]

// ✅ GOOD: Zero-cost opaque types with type-safe companions
object DomainTypes:
  opaque type TenantId = String
  object TenantId:
    def apply(value: String): TenantId = value
    extension (id: TenantId) def value: String = id

  opaque type KnowledgeSpaceId = String
  object KnowledgeSpaceId:
    def apply(value: String): KnowledgeSpaceId = value
    extension (id: KnowledgeSpaceId) def value: String = id

  opaque type PositiveInt = Int
  object PositiveInt:
    def make(v: Int): Either[String, PositiveInt] =
      Either.cond(v > 0, v, s"Expected positive integer, got $v")
    extension (p: PositiveInt) def value: Int = p

// Signature is completely immune to argument inversion:
def findChunks(
  tenantId: TenantId,
  spaceId: KnowledgeSpaceId,
  query: String,
  topK: PositiveInt
): IO[AgentError, Chunk[DocumentChunk]]
```

## 2. Eliminate Boolean Blindness & Illegal States
Functions or case classes with 2+ `Boolean` parameters (or single booleans with implicit side effects) are BANNED. Model the lifecycle and mutual exclusivity using Scala 3 `enum` (Algebraic Data Types).

```scala
// ❌ BAD: Boolean blindness allows impossible states (e.g., isFailed=true && isSucceeded=true)
final case class ToolExecution(
  id: ToolCallId,
  isCompleted: Boolean,
  isFailed: Boolean,
  isSuspendedForApproval: Boolean,
  failureReason: Option[String],
  output: Option[String]
)

// ✅ GOOD: Make illegal states unrepresentable via Scala 3 enum
enum ToolExecution:
  case Running(startedAt: Instant)
  case SuspendedForApproval(approvalId: ApprovalRequestId, requestedAt: Instant)
  case Succeeded(output: String, completedAt: Instant)
  case Failed(error: AgentError, failedAt: Instant)

// Pattern matching is checked exhaustively by the Scala 3 compiler:
def handle(exec: ToolExecution): UIO[Unit] = exec match
  case ToolExecution.Running(_)                => ZIO.unit
  case ToolExecution.SuspendedForApproval(id, _) => notifyOperator(id)
  case ToolExecution.Succeeded(out, _)         => recordSuccess(out)
  case ToolExecution.Failed(err, _)            => logFailure(err)
```

## 3. Automatic Schema & Codec Derivation (`zio-schema` & `zio-json`)
Never hand-write JSON serializers, string parsers, or manual OpenAPI/tool-call JSON schemas. Derive them directly from ADTs and case classes:

```scala
import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.schema.{DeriveSchema, Schema}

final case class SearchToolInput(
  query: String,
  maxResults: Int,
  minScore: Option[Double]
)

object SearchToolInput:
  // Compile-time automatic derivation: zero runtime reflection overhead
  given Schema[SearchToolInput] = DeriveSchema.gen[SearchToolInput]
  given JsonCodec[SearchToolInput] = DeriveJsonCodec.gen[SearchToolInput]
```
