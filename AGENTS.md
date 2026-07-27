# AGENTS.md

This repository is the public Scala 3 / ZIO 2 `zyblw-agent` framework. It must remain independently
cloneable, testable, publishable, and usable without access to any private zyblw product repository.

## Source of truth

Read `README.md`, `docs/README.md`, `docs/architecture.md`, `docs/maturity-and-roadmap.md`, and
`docs/releasing.md` before changing public APIs or release configuration. Build definitions,
sources, tests, migrations, and generated POMs take precedence over roadmap prose.

Use the repository-local `zyblw-agent-development` skill for framework implementation, review,
documentation, source-learning, and releases. It routes version-sensitive ZIO and ZIO HTTP work to
their current official documentation without importing private product rules.

## Architecture

- Keep provider-neutral ADTs, runtime, permissions, tools, context, and memory in `agent-core`.
- Keep database, HTTP, document loading, MCP, providers, reranking, and telemetry optional.
- The model proposes actions; the runtime validates, authorizes, executes, traces, and stops them.
- Do not add a new Maven artifact for a package-level concept without a dependency, lifecycle,
  protocol, security, or license boundary and an ADR.
- Do not depend on `zyblw-server`, `zyblw-web`, private schemas, private fixtures, or private CI.

## Compatibility

The project uses early SemVer during `0.x`. Patch releases preserve public Scala APIs within the
minor line. Treat Scala APIs, HTTP/schema contracts, state JSON, Maven coordinates, and Flyway
migrations as separate compatibility surfaces. Never edit a migration that has been released.

## Verification

Run the smallest relevant checks and the release gates before a release:

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.1.0-local"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.1.0-local sbt -batch compile
```

Live provider tests are opt-in and must never run on an ordinary pull request.

## Security and documentation

Never commit credentials, private product data, raw provider responses, sensitive prompts, or
production traces. External content and connector output are untrusted data. Update an existing
canonical document instead of adding dated status notes, and distinguish implemented, tested,
experimental, and planned behavior.
