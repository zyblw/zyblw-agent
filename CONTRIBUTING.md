# Contributing to zyblw-agent

> Status: contribution runbook  
> Last verified: 2026-07-24  
> Sources of truth: `build.sbt`, `.github/workflows/ci.yml`, `docs/maturity-and-roadmap.md`

Thank you for helping build a reliable Scala/ZIO agent framework. Contributions are accepted under
the Apache License 2.0 in [LICENSE](LICENSE).

## Before opening a change

1. Read [the architecture](docs/architecture.md) and
   [module maturity matrix](docs/maturity-and-roadmap.md).
2. Search existing issues and pull requests.
3. Keep the model/provider boundary separate from authorization, tools, persistence and business
   policy.
4. Discuss large public API, schema, protocol or module changes before implementation.

## Local verification

JDK 21 and sbt are required:

```bash
sbt -batch compile
sbt -batch testFull
sbt -batch 'set ThisBuild / version := "0.1.0-local"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.1.0-local sbt -batch compile
```

PostgreSQL contracts require Docker:

```bash
RUN_POSTGRES_INTEGRATION=1 sbt -batch testFull
```

Do not enable live provider tests in an ordinary pull request. They spend external quota and require
explicit, scoped secrets.

## Change rules

- Add a focused test for every behavior or bug fix.
- Add a regression eval for model/tool/safety failures.
- Preserve typed errors and structured tool results.
- Keep blocking JDBC and file work on blocking effects.
- Keep secrets, raw credentials and sensitive prompts out of source, fixtures and logs.
- Add Flyway versions; never edit a migration that has been released.
- Update the canonical document instead of adding a dated duplicate.
- Mark new modules Experimental until their failure and integration contracts have evidence.

## Public API and compatibility

The project follows early semantic versioning during `0.x`: patch releases preserve binary
compatibility within the same minor line. A breaking public API, wire contract or migration change
requires a minor version bump before `1.0`.

Avoid exposing provider SDK, Flyway or database-driver result types from stable APIs. Prefer small
framework-owned ADTs and service traits.

## Pull requests

Describe:

- the user or maintainer problem;
- public API and data changes;
- risk and rollback;
- tests/evals actually run;
- whether the change is Stable, Beta or Experimental.

Keep unrelated formatting or generated files out of the pull request.
