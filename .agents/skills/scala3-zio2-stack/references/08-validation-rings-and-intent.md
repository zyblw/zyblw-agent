# The Six Validation Rings & Intent-Driven Engineering

Inspired by Giovanni Ruggiero's (Agile Lab) *Type-Driven AI: High Quality Scala Code with LLM* and Krzysztof Romanowski's *AI Multiplies — Let Scala Hold the Line*, this guide establishes how Scala's type system and multi-layered verification rings serve as an automated, non-negotiable guardrail against AI hallucinations, test bloat, and cognitive debt.

---

## 1. Core Philosophy: "Shift Left" & "AI is a Multiplier"

> **"Speed without quality is debt."**  
> **"AI is a multiplier, not a fixer — point it at a mess, and it scales the mess up."**

When AI generates code at 100x human speed, **traditional line-by-line diff review breaks down**. Relying on "vibe coding" (blindly prompting until it appears to work) creates a fragile codebase where nobody understands the failure modes.

To stay in control:
1. **Shift Left (向西北推进)**: Formulate the business intent and invariants *before* writing code.
2. **Lean on the Compiler**: Scala's uncompromising compiler and pure functional types refuse to lie. The compiler is the first and most relentless line of defense.
3. **Decouple Oracle from Implementation**: Never ask an AI to generate the implementation and its verification tests in the same prompt. Doing so creates circular, tautological tests that pass by definition.

---

## 2. Intent Specification: EARS Syntax in Change Specs

When writing a Change Spec (as required by `AGENTS.md`), eliminate natural language ambiguity by formatting observable acceptance criteria using the **EARS (Easy Approach to Requirements Syntax)** patterns:

- **Ubiquitous (Universal Invariant)**:  
  `THE SYSTEM SHALL <action>`  
  *Example*: `The runtime shall record all tool executions in the durable ledger before dispatching effects.`
- **Event-Driven**:  
  `WHEN <event> THE SYSTEM SHALL <action>`  
  *Example*: `When a tool execution returns a 429 status and policy is IdempotentOnly, the driver shall schedule a backoff retry.`
- **State-Driven**:  
  `WHILE <state> THE SYSTEM SHALL <action>`  
  *Example*: `While Run status is WaitingForApproval, the scheduler shall reject incoming user step requests with SuspensionConflict.`
- **Unwanted Event (Defensive)**:  
  `IF <error/violation> THEN THE SYSTEM SHALL <mitigation>`  
  *Example*: `If an external LLM response contains malformed tool arguments, then the kernel shall record ToolInputInvalid without terminating the session.`

---

## 3. The Concepts Inventory (Preventing Reinvention)

AI models suffer from "lexical amnesia" and frequently reinvent redundant data structures (e.g., creating a new `struct ErrorInfo` when `AgentError` already exists).

**Before generating new types**:
1. Consult the **Concepts Inventory**:
   - Domain IDs: `TenantId`, `KnowledgeSpaceId`, `RunId`, `ToolName` (`com.zyblw.agent.core.*`).
   - Errors: Sealed hierarchy rooted at `E <: AgentError`.
   - Side-effects: `enum SideEffect`, `enum ToolRecoveryPolicy`.
   - Suspension: `enum Suspension` (`Approval`, `HumanInput`, `ExternalSignal`, `Timer`).
2. Map new business requirements to existing concept ADTs rather than inventing parallel wrappers.

---

## 4. The Six Concentric Validation Rings

Errors must be caught in concentric rings, where each ring intercepts defects that slip past the previous one:

```
[ Ring 5: Runtime Telemetry & Conformance Trajectory ]
  [ Ring 4: Pure Mathematical Kernel (AgentKernel) ]
    [ Ring 3: Mutation Resistance (Stryker4s Mindset) ]
      [ Ring 2: Independent Test Oracle & Property Tests ]
        [ Ring 1: Static Rules & Architecture Lints ]
          [ Ring 0: The Compiler & Refined Types ]
```

### Ring 0: The Compiler & Refined Opaque Types
- Enforce strict compiler flags: `-Werror`, `-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`.
- Use Scala 3 `opaque type` with smart constructors (e.g., `PositiveInt`, `NonEmptyString`) so invalid inputs are rejected at compile time or initialization.

### Ring 1: Static Analysis & Architecture Boundaries
- Package boundaries: `agent-core` never depends on `agent-postgres` or `agent-zio-http`.
- Immutability checks: `var` and mutable collections are strictly prohibited in domain layers.
- Effect isolation: All side-effecting operations must return `ZIO` or be wrapped in `attemptBlocking`.

### Ring 2: Independent Test Suite & Property-Based Testing
- **Decoupled Generation Flow**:
  1. Generate Types (`opaque type`, ADT `enum`).
  2. Generate Property-Based Tests / Boundary Specs (`ScalaCheck` or property invariants).
  3. Generate Implementation that satisfies the tests.
- Feed failing counterexamples directly back to the Agent as targeted bug reports.

### Ring 3: Mutation Testing Resistance (Anti-Happy-Path)
- LLMs routinely write happy-path tests that pass without actually verifying edge cases.
- **Stryker4s Mindset**: Ask: *"If I replace `<` with `<=`, invert a boolean, or drop this line, will the tests fail?"*
- Any test that passes despite mutant logic is a fake test and must be strengthened.

### Ring 4: Pure Functional Kernel (Mirror & Bridge)
- In `zyblw-agent`, this is the **Functional Kernel Pattern** (`AgentKernel.scala`):
  - Pure state transition: `(AgentState, Input) => (AgentState, Chunk[AgentEvent], Decisions)`.
  - Zero side-effects, no Clock, no Store, no Fiber.
  - Mathematical correctness can be verified deterministically without setting up mock databases or servers.

### Ring 5: Runtime Telemetry & Trajectory Conformance
- OpenTelemetry traces (`agent-opentelemetry`) and event streams emit structured facts.
- `TrajectoryReplay` and `PublicEvalClosedLoop`: Production telemetry and historical runs are replayed against the kernel to verify that live runtime behavior conforms to formal contracts.
