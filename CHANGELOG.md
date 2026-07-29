# Changelog

All notable user-visible changes will be recorded here. The project follows
[Semantic Versioning](https://semver.org/) with early-semver compatibility during `0.x`.

## Unreleased (target: 0.2.0)

### Added

- Experimental `core.artifacts` SPI: trusted session/user scopes, append-only immutable versions, safe artifact names, content SHA-256,
  bounded media type/metadata/capacity policy, and an in-memory development/test Adapter. Artifact bytes are deliberately excluded from
  `AgentState`, JSON descriptors, prompts, telemetry and event streams.
- Experimental declarative Workflow Graph: explicit transitions, pre-run structural validation, bounded cycles, complete/suspended
  checkpoints, structured fan-out/fan-in events, `AllSucceeded` sibling cancellation, and an executable diamond example.
- V008 `agent_workflow_checkpoints` and `PostgresWorkflowCheckpointStore`: bounded/checksummed full snapshots, monotonic step writes,
  workflow/version/session identity validation, cross-Adapter resume, and PostgreSQL 16 contract tests.
- Optional Docling Serve v1 `DocumentLoader` for bounded PDF-to-Markdown multipart conversion with HTTPS/API-key controls, response
  limits, typed retryability, cancellation and redacted failures.
- `MarkdownStructureChunker` with heading-path context, fenced-code/table preservation, Unicode-safe hard splitting, source-line metadata,
  content-addressed stable chunk IDs, and executable RAG example coverage.
- `DocumentLoaderRegistry`/`DocumentIngestionService` ZLayers and a single-document `ingestOne` convenience path.
- `RagApplication` as the recommended business-facing ingestion/query facade, with pre-provider query/top-k limits and one
  ZLayer graph for jobs, controllers and tools.
- Shared in-memory and PostgreSQL `KnowledgeIndexStore & VectorStore` layers so indexing and retrieval use the same active
  knowledge snapshot without application-side vector copying.

### Changed

- **Breaking, Experimental API:** Workflow nodes now return `NodeOutcome` only; control flow moved from the former `NodeResult` into
  `WorkflowDefinition`, which now requires `WorkflowId` and `WorkflowVersion`. `WorkflowCheckpointStore` now persists definition/session
  identity, cursor, state, step and visit counters as one monotonic checkpoint. No compatibility shim is provided on the unreleased
  `0.2.0` evolution line; immutable `0.1.0` artifacts remain unchanged.
- **Breaking, Experimental RAG API:** `SourceDocument` records `DocumentRepresentation`, every `Chunker` exposes a stable
  parameter-complete `strategyId`, and `KnowledgeIndexer` derives its default manifest strategy from the actual Chunker instead of the
  former hard-coded sliding-window label.

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
