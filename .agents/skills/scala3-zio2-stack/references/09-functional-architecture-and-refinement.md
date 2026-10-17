# Functional Software Architecture & Refinement Invariants

Inspired by Michael Sperber's (iSAQB Functional Architecture Designer, CEO Active Group) *Teaching Software Architecture using Scala* and Matt Bovel's (EPFL / LAMP) *First-class Logical Refinement Types for Scala*, this guide establishes the macro-architectural foundations and micro-level typing invariants for `zyblw-agent` and `zyblw-server`.

---

## 1. HtDP Design Recipes: AI Mechanical Derivation

> **"If data modeling is right, function templates are derived mechanically without thinking."** — Michael Sperber

AI Coding Agents fail when asked to write complex logic from vague prompts, because they guess architecture. The single highest-leverage human-AI collaboration pattern is:
1. **Human / Architect designs the Algebraic Data Types (Sums & Products)**:
   - **Sum Types (OR)**: Distinct states modeled as Scala 3 `enum`.
   - **Product Types (AND)**: Cohesive bundles modeled as `final case class`.
2. **AI mechanically expands the Template**:
   - The model generates exhaustive `match` expressions covering all variants.
   - The Scala 3 compiler enforces exhaustiveness; no branch can be forgotten.

```scala
// 1. Architect defines Sum/Product ADT (Make illegal states unrepresentable)
enum StepExecution:
  case Scheduled(at: Instant)
  case InProgress(startedAt: Instant, timeout: Duration)
  case Suspended(reason: Suspension, since: Instant)
  case Succeeded(result: StepOutput, completedAt: Instant)
  case Failed(error: AgentError, failedAt: Instant)

// 2. AI mechanically expands template with 100% compiler-verified precision
def dispatch(step: StepExecution): URIO[DriverEnvironment, Unit] = step match
  case StepExecution.Scheduled(at)           => scheduleFiber(at)
  case StepExecution.InProgress(start, to)   => monitorTimeout(start, to)
  case StepExecution.Suspended(reason, _)    => persistSuspension(reason)
  case StepExecution.Succeeded(res, _)       => commitSuccess(res)
  case StepExecution.Failed(err, _)          => handleFailure(err)
```

---

## 2. Everything is Data: Commands & Events Mirror Pattern

Never mutate object state in-place or execute side-effects across module boundaries. Model all system interactions as **pure, immutable data**:

- **Command (Intent)**: What a caller *desires* to happen (e.g., `SubmitRunCommand`, `ApproveToolCall`). May be rejected.
- **Event (Fact)**: What *actually happened* after validation against business rules (e.g., `ToolCallApproved`, `RunSuspended`). Cannot be altered or rejected.

```
Caller ───(Command: Desired Intent)───► [ Domain State Machine ]
                                                 │
                                   (Rule Validation & Invariants)
                                                 │
                                                 ▼
Caller ◄───(Events: Historical Facts)──── Emits immutable facts
```

In `zyblw-agent`, this is enshrined in `AgentKernel`:
```scala
// Pure mathematical kernel: (State, Input) => (NextState, Events, Decisions)
def reduce(
  state: AgentState,
  input: AgentInput
): (AgentState, Chunk[AgentEvent], KernelDecisions)
```
Because events and commands are pure data, they can be serialized to JSON, stored in PostgreSQL outbox tables, replayed across network restarts, and audited without distributed locking.

---

## 3. Combinator Domain Modeling (The Lego Brick Pattern)

For complex domain logic (such as TCM symptom differentiation, tool permission policies, or dynamic pricing), **never write 1,000-line if-else spaghetti**. Use Simon Peyton Jones' and Michael Sperber's **Combinator Pattern**:

1. **Primitives**: Small, atomic predicate cases.
2. **Combinators**: Higher-order operators (`And`, `Or`, `Not`, `Threshold`, `When`).

```scala
// Composable rule engine as pure recursive data
enum Rule[A]:
  case Satisfies(predicate: A => Boolean, description: String)
  case And(left: Rule[A], right: Rule[A])
  case Or(left: Rule[A], right: Rule[A])
  case Not(rule: Rule[A])

  def eval(target: A): Boolean = this match
    case Satisfies(p, _) => p(target)
    case And(l, r)       => l.eval(target) && r.eval(target)
    case Or(l, r)        => l.eval(target) || r.eval(target)
    case Not(r)          => !r.eval(target)

  infix def &&(other: Rule[A]): Rule[A] = And(this, other)
  infix def ||(other: Rule[A]): Rule[A] = Or(this, other)
  def unary_! : Rule[A] = Not(this)
```

### Why Combinators Excel in AI Software Engineering:
- **Zero Code Generation Risk**: Complex policies are constructed by combining data nodes, not writing new mutable code.
- **Explainability**: The rule tree can generate user-facing reasoning trails or audit logs automatically.
- **Storage**: Combinators derive `zio.schema.Schema` and `zio.json.JsonCodec` out-of-the-box for persistence in PostgreSQL.

---

## 4. Refinement Types & "Parse, Don't Validate"

Matt Bovel's logical refinement types `{ x: Int => x > 0 }` prove that business invariants belong in the type system, not scattered runtime asserts.

In Scala 3.9, enforce this via **Opaque Types with Smart Constructors**:

```scala
// ❌ BAD: Defensive programming everywhere (validating in every service method)
def searchVectors(query: Array[Float], dim: Int): IO[StorageError, Results] =
  if dim != 1024 then ZIO.fail(StorageError.InvalidDimension) // Repeated in 20 places!
  else ...

// ✅ GOOD: Parse, don't validate at the ingress boundary
object EmbeddingVector1024:
  opaque type EmbeddingVector1024 = Array[Float]
  
  def fromArray(arr: Array[Float]): Either[String, EmbeddingVector1024] =
    if arr.length == 1024 then Right(arr)
    else Left(s"Expected 1024 dimensions, got ${arr.length}")

  extension (v: EmbeddingVector1024)
    def toArray: Array[Float] = v

// Domain core has 0 defensive checks; compiler guarantees 1024 dimensions:
def searchVectors(query: EmbeddingVector1024): IO[StorageError, Results]
```

### The Parse-at-Boundary Rule:
- Parse untrusted data (HTTP payloads, JDBC rows, LLM tool responses) into refined types **at the adapter edge**.
- Once inside domain services and the functional kernel, functions operate on guaranteed invariants with zero defensive runtime checks.

---

## 5. Decoupled Late Architecture (David Parnas Modularity)

> **"Architecture is not about making early irrevocable decisions. It is about hiding decisions so they can be changed late."** — David Parnas

In both `zyblw-agent` and `zyblw-server`:
- **Core Domain is Pure**: `com.zyblw.agent.core` and `com.zyblw.domain.*` have **zero** dependencies on JDBC, ZIO HTTP, Flyway, Netty, or AI vendor SDKs.
- **Infrastructure is an Adapter**: PostgreSQL, HTTP routes, Tika, and LLM APIs exist strictly in outer `adapter` or `infrastructure` modules.
- **Late Composition via `ZLayer`**: All technical decisions (connection pools, timeouts, retry backoff) are deferred to the outermost composition root (`AppModule.scala` / `AgentApplication.scala`).
