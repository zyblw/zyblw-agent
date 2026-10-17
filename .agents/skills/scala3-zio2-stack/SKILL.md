---
name: scala3-zio2-stack
description: Core engineering rules, anti-imperative guardrails, and reference routing for Scala 3, ZIO 2, and PostgreSQL multi-model persistence in zyblw-agent. Always consult before writing or reviewing framework code.
---

# Scala 3 + ZIO 2 Stack Engineering Guidelines

## 1. Tooling & Verification First (CRITICAL)
- **NEVER use raw `bash` (`grep`, `find`, or cold `sbt`) if language server / MCP tools are available.**
- **Compiler Truth First**: Follow `AGENTS.md`. Never assume an IDE red highlight ("爆红") is a code error without checking `sbt "<module>/compile"`.
- **Compile & Check**: Verify types via `sbt -batch "<module>/compile"` or `metals-mcp:compile_full`.
- **Symbol Lookup**: Use IDE symbol search or `metals-mcp:symbol_search` instead of grepping files.
- **Dependency Inspection**: Inspect library signatures via source or pinned `build.sbt`; NEVER unpack or decompile `.jar` files manually.

## 2. Non-Negotiable Core Rules
1. **Strict Immutability**: `var`, mutable collections, and shared mutable state are BANNED. For concurrent state, use `zio.Ref` or `zio.stm.TRef`.
2. **Zero Primitive Obsession**: Never pass raw `String`, `UUID`, or `Int` for domain identifiers or bounded values. Use Scala 3 `opaque type` with companion extensions and smart constructors.
3. **No Boolean Blindness**: Functions or Case Classes with 2+ `Boolean` parameters (or ambiguous single booleans) are BANNED. Use Scala 3 `enum` (ADTs) to make illegal states unrepresentable.
4. **Pure Signatures (`ZIO[R, E, A]`)**:
   - Never throw raw exceptions; encode domain failures in the `E` channel using sealed ADTs (`E <: AgentError`).
   - Reserve defects (`ZIO.die`) strictly for fatal unrecoverable bugs (assertion failures, corrupted invariants).
   - Never call `Unsafe.unsafe` outside application bootstrap (`ZIOAppDefault`).
   - Prevent effect discarding: compiler flags `-Wvalue-discard` and `-Wnonunit-statement` are enforced. Every ZIO effect must be composed with `flatMap`, `for`-comprehension, or explicitly evaluated.
5. **Explicit Dependencies**: Never hardcode `System.currentTimeMillis()`, `Random`, or global singletons. Use ZIO built-in services (`Clock.instant`, `Random.nextUUID`) and `ZLayer`.

## 3. Progressive Deep-Dive Routing (Load ONLY when relevant)
Before implementing or modifying specific layers, read the corresponding reference guide:
- **Domain Modeling & Schemas**: Creating models, opaque types, enums, or `zio-schema`/`zio-json` codecs -> Read `references/01-domain-modeling.md`
- **Effects, Error Channels & Concurrency**: Writing business logic, fibers, bounded parallelism, or error handling -> Read `references/02-zio-effects-errors.md`
- **Dependency Injection & Lifecycle**: Wiring services via `ZLayer`, managing `Scope` resources, or config -> Read `references/03-zlayer-di.md`
- **PostgreSQL & Vector Storage**: Writing SQL, `pgvector` cosine similarity (`<=>`), HNSW index hints, or durable outbox/inbox/checkpoint stores -> Read `references/04-postgres-storage.md`
- **Testing & Verification**: Writing deterministic ZIO Test specs, stubs, or testcontainers integration -> Read `references/05-testing.md`
- **Orca-Inspired Review Loop**: Conducting multi-perspective review (security, performance, minimalism, error purity) -> Read `references/06-review-loop-guardrails.md`
- **Quality Pipelines & Human-in-the-Loop**: Green Gate, read-only reviewers, single adjudicator, questions.md decision queues -> Read `references/07-quality-pipeline.md`
- **Validation Rings & EARS Intent**: Six validation rings, EARS requirement syntax, concepts inventory, mutation resistance -> Read `references/08-validation-rings-and-intent.md`
- **Functional Architecture & Refinements**: HtDP mechanical derivation, Commands/Events mirror, combinators, late architecture -> Read `references/09-functional-architecture-and-refinement.md`
