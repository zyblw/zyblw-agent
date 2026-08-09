# Changelog

All notable user-visible changes will be recorded here. The project follows
[Semantic Versioning](https://semver.org/) with early-semver compatibility during `0.x`.

## 0.6.0 - 2026-08-09

This release candidate establishes the fresh-install 1024-dimensional knowledge-index baseline used by the rebuilt platform RAG
integration. It is intentionally a minor release rather than a 0.5.x patch: it adds public migration entry points and a new
pgvector physical contract. It must be released to Maven Central before a server using `migrateCoreAndKnowledge1024` is built for
CI or production; see [the 0.6.0 RAG upgrade guide](docs/upgrading-to-0.6.0.md).

## 0.5.0 - 2026-08-07

Adds an optional administration sub-surface and the runtime resolver paths that make its overrides observable without a restart.
The Agent runtime, durable commands, business HTTP v1, workflow outcome v2 and the 0.4 knowledge schema are unchanged. Upgrading
without wiring any admin capability mounts no new routes, but the `V002` migration and two layer signature changes still apply —
see [docs/upgrading-to-0.5.0.md](docs/upgrading-to-0.5.0.md).

### Added

- An optional administration API sub-surface under `/api/v1/admin/**` backs a browser-only operations console. Every capability is
  an `Option` supplied by the host: unwired capabilities mount no routes, and `GET /api/v1/admin/capabilities` reports what is
  actually available so the console degrades by hiding tabs instead of rendering panels that only ever return 404. The sub-surface is
  deliberately **Beta** and stays outside the stable `AgentHttpContract` OpenAPI promise, because admin view shapes follow what the
  console needs to display.
- Administration endpoints require explicit scopes rather than reusing the business-side "ownership implies read" rule, since an
  operator sees cross-tenant aggregates rather than a single run owner's view. `agent:admin:read` covers aggregates,
  `agent:admin:write` covers changes to deployment behaviour and implies read, and `agent:admin:debug` covers the retrieval sandbox
  and document ingestion. Debug is **not** implied by write because those two operations bill real provider calls.
- `RuntimeSettingsService` persists a bounded whitelist of runtime configuration overrides with compare-and-set writes, an
  append-only audit history and periodic cross-replica refresh. Overrides are sparse patches, so removing one is equivalent to never
  having set it. Every setting declares its effect boundary, and values fixed as immutable resources at assembly time
  (`maxParallelism`) reject overrides outright rather than offering a switch that saves successfully and does nothing.
- `ToolPolicySource` and `RetrievalPolicySource` let the runtime read tool governance and the retrieval working point through a
  resolver instead of a value frozen at startup, which is what makes those overrides observable without a restart. Both have
  baseline-returning defaults, so deployments that do not wire them keep their current behaviour.
- Narrow admin SPIs (`RunDirectory`, `RuntimeOverrideStore`, `IngestionJobStore`, `OpsAdminService`, `KnowledgeAdminService`,
  `KnowledgeIndexDirectory`, `EvalTrendReader`) with PostgreSQL, RAG and evals adapters. They are separate traits rather than new
  abstract methods on published store traits, which would break every external implementation.
- Document ingestion is an asynchronous endpoint returning `202` and a job id, accepting raw bytes rather than base64 JSON. Its
  background fiber is bound to the application scope, not the request scope, so progress survives the response being written.
- The run directory pages by keyset cursor rather than `OFFSET`, because runs keep updating while an operator pages through them.
- `modules/agent-dashboard` implements seven panels (runs, knowledge, queue, configuration, security, evaluation, models) against the
  real wire contract. Run listings carry metadata only: prompts, model output and tool arguments are business data, and a cross-tenant
  operations view should not become an export channel for them. Langfuse and Grafana deep links come from the backend so one
  deployment setting corrects every link target.
- `RunEventAdminService` and `GET /api/v1/admin/runs/{runId}/events/stream` give the console a resumable server-sent event stream for
  a single run, and the console renders it as an explicitly started debugger. It is a separate endpoint from the business-side run
  stream rather than an alias, because the two differ in both authorization and projection: the admin view requires
  `agent:admin:read` instead of ownership, and `AdminRunEventView` is an allow-list that drops `output` and `message` so a
  cross-tenant operations surface cannot become an export channel for business text. Missing runs and cursors beyond the run's last
  sequence are rejected as ordinary 4xx **before** the response head is written, since a `200 OK` followed by a `stream_error` would
  make "this run does not exist" indistinguishable from a transient disconnect. Resumption uses the standard `Last-Event-ID` header
  carrying the event `sequence`, so a reconnect can land on any HTTP replica; terminal and awaiting-approval states end the stream
  normally rather than holding an idle connection that polls the database.
- `ModelPolicySource` lets the runtime resolve the provider, model name, temperature and output ceiling per call instead of reading
  values frozen into `AgentDefinition.modelSettings` at assembly time. Overrides are sparse: switching provider alone does not blank
  the model name, and `toolChoice`, `providerOptions` and `metadata` deliberately cannot be overridden because they are agent
  behaviour contracts rather than deployment working points.
- `RuntimeOverrides` gains `modelProvider`, `modelName`, `modelTemperature` and `modelMaxOutputTokens`, so a provider outage can be
  routed around without a redeploy. Model switching reuses the existing config write path and therefore inherits its compare-and-set,
  audit history and cross-replica refresh; a second versioned write surface would produce two configuration facts that can disagree.
- `ModelCatalog` is the write-time validation authority, not just a display API. Overrides naming an unregistered provider are
  rejected before they reach storage, because a persisted bad override reloads on every restart and turns one dropdown mistake into a
  permanent `ProviderNotFound` for every call while the console reports success. Model names validate against the *effective*
  provider, so a model belonging to a different provider is rejected too. Deployments that wire no catalog cannot write model
  overrides at all: `ModelCatalog.empty` is fail-closed because without a catalog there is no basis to judge whether a provider name
  is routable.
- `GET /api/v1/admin/models` exposes the registered provider and model catalog with capabilities, credential status and pricing.
  Credentials are reported as `present` plus a display reference such as `env:DEEPSEEK_API_KEY`; **no endpoint accepts, returns or
  stores a key value.** Writing keys into the application database would add encryption-at-rest, rotation, backup redaction and a
  `pg_dump` exposure surface, none of which are problems an agent framework should own. The consequence is a deliberate boundary:
  switching between already-registered providers is immediate, but adding a wholly new provider still requires a restart.
- `POST /api/v1/admin/models/probe` performs a minimal connectivity check against a registered combination and requires
  `agent:admin:debug` because it bills a real provider call. It returns latency, token usage and a stable framework failure code, but
  never the model's output text: echoing output would turn a debug-scoped endpoint into a channel for asking arbitrary questions of
  any configured provider. Combinations absent from the catalog fail without issuing a network request and distinguish an unknown
  provider from an unknown model.
- `ModelHttpFailure` gives chat adapters a provider-neutral, redacted HTTP failure contract. Authentication, authorization, timeout,
  conflict, rate limit and unavailable remain distinct categories while retryability is preserved independently. Raw provider
  response bodies never enter the error; only a short low-cardinality code/type may be retained.
- `ModelPriceBook` turns token usage into `UsageSummary.estimatedCost`, which was structurally present but always zero. The framework
  ships **no** vendor prices: they change with time, contract and region, and a guessed table would render a cost dashboard that looks
  precise while being wrong with no signal to the operator. Missing entries estimate to zero, consistent with the existing contract
  that unknown cost stays zero rather than fabricating a billing fact. Two easy-to-get-wrong billing semantics are handled:
  `cachedInputTokens` is a subset of `inputTokens` so multiplying both by their rates double-charges cache hits, and
  `reasoningOutputTokens` is a subset of `outputTokens` billed at the output rate. Mixed currencies are rejected at construction
  because `estimatedCost` is a single scalar that would otherwise sum incomparable amounts.
- The embedding model is surfaced read-only with the reason it cannot change at runtime. Vector dimension is pinned by migration and
  existing vectors are only comparable to the model that produced them, so a switch that saves successfully would silently collapse
  knowledge-base recall. A console warning fires when the model dimension and the index dimension disagree, since ingestion fails
  before writing in that state.
- `ModelCatalogLive` and `ModelAdminLive` in `agent-providers` implement the catalog and probe SPIs from the already-assembled
  providers, so the catalog cannot drift from what is actually routable. The host declares each provider once through
  `ProviderRegistration`, which supplies the three facts the `ChatModel` SPI deliberately does not expose: the deployment default
  model, where the credential comes from and whether it is present. Declarations are checked against the real routing topology at
  assembly time in both directions, because a missing declaration hides a usable provider while a surplus one advertises an option
  that fails on every call. Reflection over provider config types was rejected: it would work for the four built-in adapters and
  silently degrade every custom `ChatModel` to "no default model, credential unknown".
- Provider configuration objects now declare which environment variable supplies their API key (`ApiKeyVariable` and
  `credentialReference`), and their loaders read that declaration instead of a duplicated literal. The console therefore shows a
  credential reference that is derived from the loader rather than guessed from a provider id — which matters because OpenAI,
  DeepSeek and GLM share one config type but three different variables.
- Embedding and Cohere rerank configuration gain ZIO Config loaders, so the `EMBEDDING_*` and `COHERE_*` variables that
  `.env.example` already declared can actually be read symmetrically with the chat providers. `EMBEDDING_MODEL` and
  `EMBEDDING_DIMENSION` are required with no default, because a plausible-looking default turns a missed setting into degraded
  recall across the whole knowledge base instead of a startup failure. `allowInsecureHttp` is deliberately not readable from
  configuration: a switch that sends a bearer token over cleartext HTTP will eventually be turned on in production "temporarily".

### Changed

- `AgentRuntimeLive` now requires `ModelPolicySource` in its environment, alongside the existing `ToolPolicySource`. Deployments using
  the `AgentApplication` assembly layers need no change; those wiring the runtime directly must add `ModelPolicySource.defaultLayer`,
  which preserves current behaviour exactly (each agent keeps its own `modelSettings` and no cost is estimated).
- `RuntimeSettingsService.layer` additionally requires `ModelCatalog`. Supply `ModelCatalog.emptyLayer` to keep the previous
  behaviour, which also means model overrides are rejected — see the fail-closed rationale above.
- `V002__zyblw_agent_admin_surface.sql` promotes tenant, user and approval-pending to generated columns on `agent_runs` with
  supporting indices, and adds the runtime override and ingestion job tables. Generated columns leave every write path untouched, so
  the read model cannot drift from authoritative state; the cost is a table rewrite that large deployments must schedule.

### Fixed

- Keyset cursors carry microsecond timestamps, matching the precision of the `TIMESTAMPTZ` columns they sort by. A
  millisecond cursor is truncated below every actual timestamp in the same millisecond, so the row-value comparison excluded the
  whole millisecond along with the cursor row itself and the next page silently vanished. This affected the run directory and the
  knowledge manifest directory; the manifest case was reachable on every republish, because superseding the old version and
  readying the new one write both rows at one transaction timestamp. Fixtures built from millisecond-aligned instants cannot
  reproduce it, so the regression tests now use sub-millisecond timestamps.
- Concurrent runtime override writes now surface as an optimistic-lock conflict rather than a database failure. The
  `MAX(version)` guard inside the insert cannot see an uncommitted concurrent insert under `READ COMMITTED`, so the race is settled
  by the version primary key; classifying that unique violation as a generic database error turned an ordinary simultaneous edit
  into a 500, and the console only prompts for a reload on 409.
- The OpenAI-compatible embedding HTTP contract no longer fails intermittently. One test asserted a client-side timeout and
  cancellation propagation through a single shared `Client`: the timeout scenario deliberately abandons an in-flight request, and
  whether that connection stays in the pool depends on the client's reclamation timing, so the cancellation request could be sent
  on it and lost. Both contracts are still asserted, each with its own client, which removes the coupling rather than widening a
  timeout around it.

### Verification

- Runtime settings and run directory suites cover sparse-patch merging, override removal, compare-and-set conflicts, baseline
  clamping (including NaN), policy-source propagation and cursor pagination. The admin HTTP suite covers the authorization boundary
  directly: missing scopes are rejected before adapters are reached, write implies read, debug is not implied by write, and unwired
  capabilities return 404 while capability discovery reports them as unavailable.
- The PostgreSQL admin suite runs against a real database and covers generated-column extraction, keyset pagination agreeing with
  the in-memory implementation, UUID tie-breaking, sub-millisecond cursor advance, append-only override history, and eight
  concurrent writers resolving to exactly one success with optimistic-lock failures. A knowledge manifest directory suite covers
  cross-tenant listing, tenant scoping, paging across a republished document's two versions, limit clamping and past-the-end
  cursors. The dashboard passes type checking, lint and a production build.
- Model governance is verified end to end rather than per unit: a scripted model asserts that an override reaches the actual
  `ChatRequest`, that unoverridden fields still come from the agent definition, and that the price book lands in `estimatedCost`.
  Catalog validation is tested for unregistered providers, models belonging to another provider, and the empty fail-closed catalog.
  Probe tests assert the security-relevant behaviours specifically: an unregistered target issues no network call, a write-only
  scope cannot probe, cancellation is not reported as a provider failure, and the serialized catalog contains no credential value.
- The dashboard now has a Playwright browser contract using intercepted admin responses rather than live credentials. It proves the
  credential gate issues no admin request, model rows are keyboard-selectable, probe failures provide an actionable explanation,
  embedding dimension drift is visible, bearer authentication is forwarded, and no token or key value is rendered.
- The run event debugger is covered for both the happy path and recovery. One scenario asserts the bearer-authenticated `fetch`
  carries `Accept: text/event-stream` and an initial `Last-Event-ID`; a second interrupts the stream with `stream_error` after one
  event and asserts the reconnect resumes from the last confirmed sequence rather than replaying, since replayed events would be
  rejected by the client's own contiguity check.
- The dashboard type check runs `next typegen` first. Route-aware helpers such as `LayoutProps` are generated into
  `.next/types`, so a bare `tsc --noEmit` silently passed on machines that had already built and failed on a clean CI
  checkout. `next typegen` produces those declarations without a full build, which keeps type checking a real gate instead
  of a step that only reports the state of a local build directory.
- Release gates re-run on the tagged tree: format checks and `testFull` pass with no failures, `RUN_POSTGRES_INTEGRATION=1
  postgres/testFull` applies core `V001 → V002` on a real PostgreSQL 16 container with 54 passing contracts, `publishM2` produces
  POM, binary, sources and Scaladoc JARs for all eleven published modules with no unresolved Scaladoc links, and the independent
  Maven consumer compiles against those artifacts alone.

## 0.4.0 - 2026-08-02

### Added

- Docling Serve v1 loader now requests both Markdown and lossless JSON and projects document blocks, parent references, heading paths,
  pages, bounding boxes and block IDs into provider-neutral RAG types with explicit capacity limits.
- `DocumentStructureChunker` performs structure-first peer merging, oversized-block splitting and stable parent/previous/next lineage;
  plain Markdown remains an explicit fallback without fabricated geometry.
- `LocalDocumentDirectorySource` turns a confined directory into a bounded, symlink-safe stream of lazy `DocumentInput` values.
- The 0.4 pgvector location has one fresh-install V001 containing manifest, staging, active vectors, FTS/HNSW, and complete
  parent/neighbor/heading/page/bbox/block lineage. Knowledge objects and their Flyway history live in the dedicated
  `zyblw_agent_knowledge` schema, while vector types are explicitly resolved from `public`; post-migration probes and opt-in
  auto-migrating ZLayers fail startup on drift.
- A production-oriented usage guide, PDF RAG pipeline, 0.4 upgrade guide, current compatibility contract and source-reading path now
  connect dependency selection, ZLayer wiring, database ownership, ingestion, retrieval, deployment and release verification.

### Changed

- **Breaking (next minor):** `SourceDocument`, `DocumentChunk` and `Citation` carry optional structured provenance. The development
  branch targets the next `0.4.x` minor rather than a `0.3.x` patch so the published `0.3.0` Scala API remains frozen.

### Verification

- RAG and document-loader suites cover structure decoding, bbox lineage, structural chunking, directory confinement and expansion
  authorization. The PostgreSQL integration harness applies the core baseline first, then creates and idempotently replays the
  dedicated 0.4 knowledge baseline before verifying atomic publication, composite document/chunk identity and complete lineage
  round-trip.

## 0.3.0 - 2026-08-02

### Added

- Durable command Worker now supports configurable bounded Run parallelism (default 4, hard limit 256). Different Runs can progress
  concurrently while each Run remains serialized by the dispatcher fence; all lanes are supervised as one fail-fast ZIO lifecycle.
- `RunCommandQueueSnapshot` and `AgentApplication.queueSnapshot` expose a database-clock, low-sensitivity operational view of queued
  commands, dispatchable Runs, active/expired leases, DeadLetters and oldest dispatchable age without exposing tenant data or payloads.
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

### Reliability evidence

- PostgreSQL 16 integration tests now drive the formal `WorkerHost` through three instances and six bounded lanes over 48 independent
  Runs, proving one Runtime invocation per command and a fully drained queue.
- A Worker Fiber interruption leaves the ambiguous lease fenced; after expiry a new owner receives the next generation, completes the
  command, and the stale owner is rejected. PostgreSQL pause/unpause and `pg_dump`/`pg_restore` scenarios verify typed unavailability,
  connection recovery and durable data restoration.

### Known limitations

- The core Agent command path is a production baseline for staged and limited-production adoption, not a universal throughput promise.
  Every deployment still needs its own sustained soak, HikariCP/PgBouncer saturation curve, SLO, backup/RTO and Provider failure drills.
- Workflow remains Experimental; RAG remains Beta and does not yet preserve Docling block/page/bbox lineage or provide parent-child and
  adjacent-block retrieval. MCP/Sandbox, Artifact, Multimodal and Harness also remain Experimental.

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
