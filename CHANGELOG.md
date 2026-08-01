# Changelog

All notable user-visible changes will be recorded here. The project follows
[Semantic Versioning](https://semver.org/) with early-semver compatibility during `0.x`.

## Unreleased - 0.3.0 development line

### Added

- Durable command Worker now supports configurable bounded Run parallelism (default 4, hard limit 256). Different Runs can progress
  concurrently while each Run remains serialized by the dispatcher fence; all lanes are supervised as one fail-fast ZIO lifecycle.
- The independent Maven consumer now compiles the production-facing Agent definition, Worker config, PostgreSQL control plane,
  knowledge store and durable `AgentApplication` wiring instead of checking only two core ADTs.
- Durable Workflow timer/signal contract: `NodeOutcome.Awaiting`, absolute deadlines, typed wakeups, atomic wait registration and
  consumption in the execution/checkpoint commit, bounded signal payloads, stable signal IDs, duplicate/conflicting retry handling,
  and a database-clock timeout race with one winner.
- In-memory and PostgreSQL implementations of `currentWait`, `signal` and `expireDue`, with deterministic ZIO TestClock coverage and
  real PostgreSQL 16 cross-Store integration tests.
- `agent_workflow_waits` and `agent_workflow_signals`, including one-active-wait-per-Run, execution foreign keys, due-work indexes,
  payload checksums and low-sensitivity receipt state.
- `WorkflowWakeWorker`, `WorkflowWakeSupervisor` and low-sensitivity observer/config APIs. Resolved wait rows now act as durable wake
  commands with scoped heartbeat, delayed retry release, typed lease loss, and atomic `resumeClaimed` completion.
- In-memory and PostgreSQL wake leases with owner/token/generation/expiry fencing. PostgreSQL uses database time and
  `FOR UPDATE SKIP LOCKED`; real two-Store tests prove unique claim, expired reclaim and stale-worker rejection.
- Executable `DurableWorkflowWakeExample` covering wait registration, idempotent signal delivery, Worker claim and completed state.

### Changed

- **Breaking:** the development branch now targets `0.3.0` and intentionally drops `0.2.x` source, binary, persisted-outcome and
  Flyway-history compatibility. All framework tables are described by one `V001__zyblw_agent_0_3_baseline.sql` fresh-install
  migration; adopters must use an empty schema/new database and rebuild derived RAG indexes.
- PostgreSQL Workflow outcome schema is version 2 so an Awaiting result and its absolute deadline survive prepare/reclaim without
  recomputing time after a crash.

### Remaining before release

- Exercise database restart, process kill and long-running multi-Worker soak; establish backlog, claim-latency, lease-loss and recovery SLOs.
- Complete RAG block/page/bbox lineage, parent-child retrieval and adjacent-block expansion against the new baseline.

## 0.2.1 - 2026-07-30

### Added

- Additive `WorkflowExecutionStore`, `WorkflowExecutionPolicy` and `WorkflowEngine.makeDurable` APIs with node
  Running/Prepared/Committed ledger state, scoped lease heartbeat, owner/token/generation/expiry fencing, recoverable pending outcomes,
  and atomic fan-out execution/checkpoint commits.
- V009 `agent_workflow_node_executions` plus PostgreSQL 16 contracts for active-owner exclusion, expired Prepared outcome recovery,
  stale-worker rejection, checksum/domain validation, and transactionally aligned ledger/checkpoint completion.
- Failure-injection coverage proving a process failure after outcome preparation and before checkpoint commit resumes under a new
  generation without invoking the node twice.
- Low-sensitivity `WorkflowExecutionStore.timeline` projection with exclusive `(step, nodeId)` cursor pagination in the official
  in-memory and PostgreSQL Adapters. The projection excludes application state, pending outcomes and lease tokens; third-party Stores
  retain a concrete typed-failure default for patch-line source compatibility.
- Run-level Workflow/version/session identity arbitration across different execution steps. The PostgreSQL Adapter serializes
  concurrent first claims for the same Run with a transaction-scoped advisory lock, preventing a split identity without adding a
  long-lived process lock.
- A canonical compatibility matrix and `0.2.1` upgrade guide covering Scala APIs, HTTP/schema contracts, persisted state, Flyway
  migrations, Workflow rolling upgrades and custom Store responsibilities.

### Changed

- `GraphWorkflowExample` now demonstrates the durable in-memory execution API; checkpoint-only `WorkflowEngine.make` remains available
  and unchanged for compatible 0.2.x consumers.
- Reorganized the root README and documentation map around execution-mode selection, five-minute startup, production adoption,
  architecture, capability maturity and source-learning paths; added a Chinese public-contract commenting standard.
- ZIO HTTP contract stubs now let the bound Server allocate an open port and install routes inside the managed Scope, removing the
  probe-close-bind race from concurrent Anthropic, Gemini and Langfuse tests.
- Release tags now fail closed unless they are annotated, match the latest CHANGELOG and upgrade guide, and point to a commit already
  contained in remote `main`.

### Upgrade

- Existing `0.2.0` Agent Runtime, Provider, HTTP v1 and checkpoint-only Workflow callers require no source migration.
- Applications enabling durable Workflow execution must apply the new, append-only V009 migration before constructing
  `PostgresWorkflowCheckpointStore` as a `WorkflowExecutionStore`.
- Custom `WorkflowExecutionStore` implementations continue to compile because `timeline` has a concrete typed-failure default; implement
  it before exposing Workflow inspection in production. Recompile and run application contract tests against all consumed `0.2.1`
  artifacts.

## 0.2.0 - 2026-07-29

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
- `AgentEvalRunner.runRepeated` and reliability reports with one bounded ZIO job set, deterministic case/attempt ordering,
  observed success rate, estimated `pass@k` / `pass^k`, and an all-trials hard signal.
- Agent Application Runtime architecture decision: Agent/Harness/Workflow boundaries, eight capability planes, and an evidence-gated
  order for durable execution, RAG, Harness, interoperability and multi-agent work.

### Changed

- **Breaking, Experimental API:** Workflow nodes now return `NodeOutcome` only; control flow moved from the former `NodeResult` into
  `WorkflowDefinition`, which now requires `WorkflowId` and `WorkflowVersion`. `WorkflowCheckpointStore` now persists definition/session
  identity, cursor, state, step and visit counters as one monotonic checkpoint. No compatibility shim is provided in `0.2.0`;
  immutable `0.1.0` artifacts remain unchanged.
- **Breaking, Experimental RAG API:** `SourceDocument` records `DocumentRepresentation`, every `Chunker` exposes a stable
  parameter-complete `strategyId`, and `KnowledgeIndexer` derives its default manifest strategy from the actual Chunker instead of the
  former hard-coded sliding-window label.

### Upgrade

- See [`docs/upgrading-to-0.2.0.md`](docs/upgrading-to-0.2.0.md). Applications using custom Workflow nodes/checkpoint stores or custom
  RAG Chunkers must migrate and rebuild their index version. The stable Agent Runtime, Tool, Provider and HTTP v1 paths do not require
  an intentional API migration, but every consumer must recompile and run its own contract tests.

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
