# Contributing to zyblw-agent

> Status: contribution runbook
> Last verified: 2026-07-29
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

The unreleased `main` branch is the next minor evolution line. Experimental APIs and unreleased
schema may be replaced without compatibility shims when the new contract is simpler and better
tested, but the breaking change must be explicit in `CHANGELOG.md`, canonical docs and the pull
request. Published tags and migrations remain immutable; a patch release from a published minor
line keeps that line's public contracts.

## Branch and merge policy

- Branch from an up-to-date `main` and use one focused `codex/<scope>` or contributor feature
  branch per vertical slice.
- Keep refactors, schema changes, tests, examples and canonical documentation for that slice in the
  same pull request. Do not mix editor settings or unrelated cleanup.
- For stacked work, state the parent pull request. Rebase the child after the parent merges and
  verify the resulting diff contains only the child scope.
- Rebase on current `main` before final review, rerun the relevant release gates, then use squash
  merge. The squash message becomes the durable change record.
- Do not push directly to `main`. Delete merged branches; create release tags only from a green
  `main`.
- Resolve conflicts from contracts outward: ADT/schema first, persistence and adapters second,
  runtime third, tests/examples/docs last. Never accept a generated or migration conflict without
  checking its semantic result.

## Pull requests

Describe:

- the user or maintainer problem;
- public API and data changes;
- risk and rollback;
- tests/evals actually run;
- whether the change is Stable, Beta or Experimental.
- branch ancestry when the pull request is stacked, and the intended merge order.

Keep unrelated formatting or generated files out of the pull request.
