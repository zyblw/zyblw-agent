# Testing & Verification Guidelines

## 1. ZIO Test Deterministic Testing
Never use real wall-clock sleeps (`Thread.sleep`, `ZIO.sleep(10.seconds)`) in tests. Rely on `TestClock.adjust` to simulate time passage instantly and deterministically.

```scala
// ❌ BAD: Sleeping real threads makes test suites slow, non-deterministic, and flaky
test("worker lease expires after timeout") {
  for
    _ <- worker.start
    _ <- ZIO.sleep(35.seconds) // Blocks CI thread for 35 seconds!
    status <- worker.status
  yield assertTrue(status == WorkerStatus.Expired)
}

// ✅ GOOD: Instant deterministic time advance via TestClock
test("worker lease expires after timeout") {
  for
    fiber  <- worker.run.fork
    _      <- TestClock.adjust(35.seconds)
    status <- worker.status
    _      <- fiber.interrupt
  yield assertTrue(status == WorkerStatus.Expired)
}
```

## 2. Test Minimalism: Reject AI Test Bloat
AI coding assistants have an acute tendency to generate dozens of bloated, low-value tests (testing case class getters, testing trivial string interpolations) to artificially boost test count.

**BANNED**:
- Tests that merely assert case class field assignment (`assertTrue(item.name == "test")`).
- Repetitive happy-path variations that test standard library or framework functions.

**REQUIRED (High-Signal Verification)**:
- **Failure Injection**: Network disconnection, malformed JSON, truncated streams.
- **Security & Authorization**: Scope denial, missing permission masks, sensitive field redaction in traces.
- **Concurrency & CAS Collisions**: Concurrent tool dispatch, optimistic lock / lease collision (`FencingTokenConflict`).
- **Cancellation & Interruption**: Ensure cleanup logic (`ZIO.acquireRelease`) runs cleanly when a parent fiber is interrupted.

## 3. Database & Container Integration Tests
- PostgreSQL integration tests use `testcontainers-scala-postgresql`.
- Run with `RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull`.
- An intermittently failing test is a defect, not noise: fix the underlying concurrency or coupling race rather than widening timeouts.
