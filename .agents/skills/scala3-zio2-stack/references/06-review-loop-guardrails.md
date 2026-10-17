# Orca-Inspired Review Loop & Guardrails

Adam Warski's Orca demonstrated that complex code generation requires a deterministic, adversarial review loop with specialized roles. When reviewing or finalizing any PR or code edit in `zyblw-agent`, conduct this systematic five-perspective review:

---

## 1. Security Reviewer (The Scat & Redaction Gate)
- [ ] **No Secret Exposure**: Ensure no API keys, bearer tokens, or sensitive prompts are committed or logged. Credentials must only be referenced via `env:VARIABLE` plus a presence flag.
- [ ] **Data Redaction in Traces**: Are sensitive tool input/output fields properly registered in `ToolMetadata.sensitiveInputFields` / `sensitiveOutputFields`?
- [ ] **Untrusted Boundary Ingestion**: Are tool outputs, HTTP payloads, and external documents treated as untrusted data? Is input schema validated via `decodeInput` before execution?
- [ ] **SQL Injection Prevention**: Are all SQL statements strictly parameterized?

---

## 2. Performance & Concurrency Reviewer (The Loom & Fiber Gate)
- [ ] **Bounded Parallelism**: Does any `ZIO.foreachPar` / `collectAllPar` call omit `.withParallelism(n)` on dynamic lists?
- [ ] **Resource Leakage**: Are all JDBC connections, HTTP client streaming responses, and temporary file handles managed inside `ZIO.acquireRelease` within a `Scope`?
- [ ] **Blocking I/O Isolation**: Is blocking I/O (JDBC, disk operations) wrapped in `ZIO.attemptBlocking` to avoid starving the ZIO compute pool?
- [ ] **Conflict-Aware Tool Execution**: If a tool declares `ConflictAware`, has its static conflict group been properly reviewed?

---

## 3. Scala 3 & ZIO Correctness Reviewer (The Compiler & Effect Gate)
- [ ] **No Swallowed Exceptions**: Does any `catchAll` or `catchNonFatalOrDie` silently swallow cancellation or fatal defects?
- [ ] **Pure Signatures**: Does any domain interface leak `Task[A]` / `Throwable` instead of a typed `E <: AgentError`?
- [ ] **No Discarded Effects**: Are all statements inside `for`-comprehensions properly bound (`<-`)? Verify that `sbt compile` emits zero `-Wnonunit-statement` or `-Wvalue-discard` warnings.
- [ ] **Strict Immutability**: Are any `var`s or mutable Java collections introduced in domain state?

---

## 4. Code Structure & Minimalism Reviewer (The Anti-Bloat Gate)
- [ ] **Zero Primitive Obsession**: Are IDs and bounded quantities modeled as `opaque type` rather than raw `String` or `Int`?
- [ ] **No Boolean Blindness**: Are multiple boolean flags or optional states consolidated into a Scala 3 `enum` (Sum Type)?
- [ ] **No Over-Engineering**: Did the agent invent unnecessary wrapper classes, factories, or spec-kits? Prefer the simplest direct code that fulfills the requirement.

---

## 5. Test Quality Reviewer (The Signal-to-Noise Gate)
- [ ] **No AI Filler Tests**: Have useless getter/setter tests or trivial assertion duplicates been eliminated?
- [ ] **High-Signal Edge Cases**: Are failure injection, permission denial, fiber interruption, and CAS lease contention covered?
- [ ] **Zero Flakiness**: Does any test rely on real `sleep` or un-synchronized race conditions? Enforce `TestClock.adjust`.
