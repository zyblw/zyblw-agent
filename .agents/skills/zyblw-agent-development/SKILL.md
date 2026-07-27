---
name: zyblw-agent-development
description: Develop, review, test, document, or release the public zyblw-agent Scala 3/ZIO 2 framework. Use for runtime loops, tools, permissions, providers, context, memory, RAG, persistence, HTTP, observability, evals, public API compatibility, Maven publishing, examples, and source-learning work in this repository.
---

# zyblw Agent Development

Work from the checked-in implementation, not from an imagined framework.

## Establish the current contract

1. Read `AGENTS.md`.
2. Enter through `docs/README.md`; follow the task-specific path instead of reading every document.
3. Before changing public APIs or modules, also read `docs/architecture.md`,
   `docs/maturity-and-roadmap.md`, `docs/compatibility.md`, and `docs/releasing.md`.
4. Inspect the relevant build, source, tests, examples, migrations, and generated POM. Code and tests
   take precedence over roadmap prose.

## Preserve framework boundaries

- Keep this repository independently cloneable, testable, and publishable.
- Keep private product types, database schemas, fixtures, credentials, and release automation out.
- Keep provider-neutral ADTs, runtime, tools, permissions, context, and memory in `agent-core`.
- Add an artifact only for a real dependency, lifecycle, protocol, security, or license boundary.
- Let the model propose intent; validate, authorize, execute, trace, and stop in the harness.
- Prefer one bounded agent loop. Add orchestration only after replayable evals demonstrate a need.

## Implement in this order

1. Define or amend the domain contract and typed error/stop reason.
2. Add deterministic unit tests for success, denial, malformed input, timeout, cancellation, budget
   exhaustion, and redaction as relevant.
3. Implement provider/infrastructure adapters behind narrow traits.
4. Compose resources with scoped `ZLayer`; preserve interruption and cancellation semantics.
5. Add an executable example or consumer test when a public API changes.
6. Update an existing canonical document and `CHANGELOG.md`; add an ADR only for a durable
   cross-cutting decision.

## Verify version-sensitive facts

- For ZIO APIs, start at `https://zio.dev/llms.txt`, open the exact official page, and adapt it to
  the versions pinned in `project/Dependencies.scala`.
- For ZIO HTTP APIs, start at `https://ziohttp.com/llms.txt`.
- For Scala or sbt behavior, prefer the official documentation and the repository's actual build.
- Do not paste remembered signatures when compilation can prove the contract.

## Release and compatibility gate

Treat Scala APIs, serialized state, HTTP/schema contracts, Maven coordinates, and Flyway migrations
as separate compatibility surfaces. During `0.x`, preserve public Scala APIs within a minor line for
patch releases. Never edit a released migration.

Run the smallest relevant checks during development and the full gates before release:

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.1.0-local"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.1.0-local sbt -batch compile
```

Live-provider tests remain opt-in. Never expose secrets, raw private prompts, provider payloads, or
production traces in source, fixtures, logs, documentation, or CI artifacts.

## Deliver evidence

Report the public contract changed, compatibility impact, tests executed, consumer verification,
documentation updated, rollback path, and any experimental limitations that remain.
