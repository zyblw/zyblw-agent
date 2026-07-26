# Changelog

All notable user-visible changes will be recorded here. The project follows
[Semantic Versioning](https://semver.org/) with early-semver compatibility during `0.x`.

## 0.1.0 - 2026-07-27

### Added

- Maven Central publishing metadata and tag-driven release workflow.
- Standalone public-repository CI, release environment, and Maven consumer contract.
- Apache-2.0 license scoped to `zyblw-agent`.
- Dedicated framework Flyway resource path and opt-in `AgentPostgresMigrations` API.
- Source-versus-published dependency mode for the `zyblw-server` reference consumer.
- Public module, release, database adoption and contribution documentation.
- Versioned System/Developer instruction blocks with deterministic, content-safe fingerprints.
- Provider-neutral cached-input and reasoning-output token details across runtime state, OpenAI adapters and observability.

### Changed

- Maven group ID is `io.github.zyblw`; Scala packages remain `com.zyblw.agent`.
- Source and SCM metadata now target the public `zyblw/zyblw-agent` repository while the private
  product remains in `zyblw/zyblw-platform`.
- Framework Flyway resources no longer use the host application's generic `db/migration` path.
- Public release surface is consolidated from more than thirty thin artifacts to eleven purposeful artifacts:
  core, providers, RAG, document loaders, rerank, PostgreSQL, ZIO HTTP, MCP, OpenTelemetry, evals and testkit.
- Frequently co-evolving runtime capabilities now live as separate packages inside `zyblw-agent-core`; HTTP contract, routes and host
  now live as separate packages inside `zyblw-agent-zio-http`.
- `zyblw-server` consumes the new artifact names in both source-development and Maven dependency modes.
- Tool registries now reject duplicate names during ZLayer construction instead of silently keeping the last implementation.

### Removed

- Obsolete thin sbt projects whose boundaries represented one implementation concept rather than a useful dependency or release choice.
- Old Maven coordinates such as `zyblw-agent-runtime`, `zyblw-agent-app`, `zyblw-agent-http-host` and per-provider artifacts.

Published as 11 signed Scala 3 artifacts under `io.github.zyblw` on Maven Central. The tag and
Central artifacts are immutable; follow-up fixes use a new patch version.
