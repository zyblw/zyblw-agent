# Changelog

All notable user-visible changes will be recorded here. The project follows
[Semantic Versioning](https://semver.org/) with early-semver compatibility during `0.x`.

## 0.9.0 - Unreleased

### Durable Worker reliability

- Normal cancellation, lease preemption, and generation takeover now stop only the affected command. The claim lane remains available for unrelated Runs while stale completion remains fenced.
- Retryable claim/store failures back off inside the lane; unrecoverable Worker/store failures, defects, and unexpected Worker termination still fail the host.

### Embedded HTTP boundaries

- `AgentHttpApi` now exposes additive submission, run-read, event, control, command, and metadata route groups. Existing `routes` still serves the complete stable v1 contract; embedded product hosts can mount only the minimum surface they own.

### RAG Runtime 绿场脊柱

- 知识 V001 替换为 Space / Profile / census / chunks / audit / withdrawn；检索只读 pinned `activeProfileId`。
- 唯一 Embedding SPI 为 `EmbeddingModel.embed(EmbeddingRequest)`；删除 `EmbeddingService`。
- `DocumentChunk` 只存 `ChunkRepresentations`；citation 只用 `displayText`。
- 默认检索 1024 dense + PostgreSQL FTS + weighted RRF + rerank；sparse / planner assist 默认关闭。
- 安全硬门禁与消融 A–D runner 进入 `agent-evals`；宿主中医校准标 deferred。
- 摄入主路径写入 checkpoint；质量不足进入 quarantine（`Failed` + `ingestion.quarantine`），不得 `activate`。
- 文档发布显式写 space/profile；换模写入 building Profile，只有空间级 `activateProfile(expectedRevision)` 才 CAS。请求内 `pinnedProfileId` 钉死，中途切指针不影响进行中查询。
- Profile 切换要求可信评测绑定完整目标 census，并在 Space 锁内与当前 active corpus 对齐；缺文档、失败文档、质量未通过或 revision 漂移均 fail-closed。切换审计独立保存 evaluation/census/count，旧 Profile 与 chunks 保留供回滚，已评测发布的 Profile 封闭写入。
- 文档 retire 仅删除当前 active Space/Profile 的 chunks，不再误删旧 Profile 回滚快照；内存 Store 与 PostgreSQL 使用相同的 active Profile 选择和封存语义。
- 自动 Profile 身份同时绑定 embedding 与 indexing strategy；重建使用来源修订生成稳定幂等键并保留每份 manifest 的原 ACL，避免重试重复计费或把批量操作者权限写回索引。
- OpenAI-compatible embedding 将单次逻辑请求上限 `maxTextsPerRequest` 与 HTTP 分批上限 `maxBatchSize` 分离；大请求可按 adapter 上限有界分批，同时继续在发网前拒绝超出 Provider 总量能力的输入。
- `ContextAssembler` 按 token/来源多样性裁剪；legal-hold 文档排除 retention purge。sparse 仍默认关闭，不作为发布门禁。
- `EvidenceBundle` 的 Profile/Space、证据状态、降级阶段与逐候选取舍贯通 `RetrievalResult`、知识工具、管理调试 API 和 RAG Inspector；检索增加 assemble 阶段 span。
- Phrase / heading 过滤绑定与检索 SELECT 的 space/profile 列对齐，避免短查询在 planner Exact→Phrase 路径上 JDBC 参数不足。
- 检索沙盒迁到 `POST /api/v1/admin/debug/retrieve`（`agent:admin:debug` 且 `knowledge:read`）；删除 `/api/v1/admin/knowledge/**` 与平台 `POST /api/v2/ops/knowledge/retrieve` 副本。清单租户只来自会话；记忆装配与 `DefaultRetriever.acceptsSeed` 对齐。

### Model Routing（Experimental）

- 新增显式开启的 Fast/Standard/Reasoning 主调用路由；固定候选顺序，能力/敏感级/预算准入，保留显式模型选择。
- 路由决定与 ModelCall 同事务持久化；关闭正文采集仍保留执行账本；失败调用保留次数并按 Unknown 禁止自动恢复。
- 冻结路由和价格内容指纹，保存选中单价；未知价格不能通过费用硬限；实际 token 超限前先保存已知用量。
- 现有配置加载器可从启动期 JSON 加载路由政策；管理面与 Incident Pack 提供低敏路由解释，不包含 Prompt 或完整价目。
- 默认装配保持旧行为；公共 Scala case class 加字段仅面向当前开发 minor。未改 SQL migration、稳定 HTTP 或平台装配。
- Goal 对账遇到未结算模型调用时保留预留；内存/PostgreSQL 账本禁止在状态转换中改写路由决定。
- Qwen 升级为一级 OpenAI-compatible 档案：区域端点、模型和密钥均由部署显式配置，并纳入低成本真实 smoke 入口。
- Retry/Fallback、动态评分/限流、持久费用预留与 Planner 尚未启用。

0.9.0 is the current fresh-install baseline. There is no in-place upgrade from the frozen
published `0.8.0` artifact, or from 0.6.x / 0.7.0-candidate databases: hosts must create a new
PostgreSQL database and rebuild knowledge indexes. Core Flyway history is
`V001__zyblw_agent_0_9_baseline.sql`; the 1024 knowledge history is
`optional/pgvector_1024/V001__agent_knowledge_0_9_baseline.sql`. Citations remain in
`AgentState` JSON (schema v7); they are not SQL columns. `RunCitation` / `CitationView` add
optional allowlist `sourceKind`. `knowledge_search` returns preview excerpts; `knowledge_fetch`
returns the authorized chunk text. Read-only knowledge/web tools may declare conflict-aware
parallelism. HTTP OpenAPI stays `1.2.0`. The
pgvector **extension** requirement remains `>= 0.8.0`. See
[the 0.9.0 upgrade guide](docs/fresh-install-0.9.0.md).

## 0.8.0 - 2026-08-23

0.8.0 is a frozen published artifact, not the current install path. It was a fresh-install
baseline with no in-place upgrade from 0.6.x / 0.7.0-candidate databases: hosts must create a
new PostgreSQL database and rebuild knowledge indexes. Core Flyway history collapses V001–V013
into `V001__zyblw_agent_0_8_baseline.sql`; the 1024 knowledge history is
`optional/pgvector_1024/V001__agent_knowledge_0_8_baseline.sql` with pg_trgm, metadata/heading
GIN indexes, and phrase re-identification. `AgentPostgresMigrations.resetAll` rebuilds both
histories. Retrieval now has `Hybrid` / `VectorOnly` / `LexicalOnly` / `Phrase` modes plus
document/page/heading/metadata/chunk filters applied after ACL and before ranking. Chunking
defaults to cl100k BPE token packing. `knowledge_search` / `knowledge_fetch` are shipped tools.
Run state v7 stores bounded citations; HTTP OpenAPI is 1.2.0 with additive `citations` /
`evidence` on `RunView`, `GET /api/v1/runs/{runId}/citations`, and stable `/api/v1/knowledge/**`
(documents, search, ingestions, reindex). Admin knowledge routes are removed. `KnowledgeQaHost`
is the book Q&A composition root: JDBC `serve` uses the durable application and 1024 knowledge
schema, `status` reports both Flyway histories, and reindex is atomic per document. Indexed ACL
is the reader scope (`knowledge:read`); operator write/admin scopes are not written into chunks.
Live ingest/serve/reindex require `EMBEDDING_DIMENSION=1024` and do not fall back to hash
embeddings. Current empty-install path is [the 0.9.0 upgrade guide](docs/fresh-install-0.9.0.md).

## 0.7.0 - Superseded candidate

Removes the public `AgentQuickstart` / in-memory five-minute onboarding path and the unused
`ConversationStore` SPI. The official entry is `ProductionSupportHost`: PostgreSQL, a real
OpenAI-compatible provider, trusted identity headers, typed read/approval-write tools, ZIO HTTP,
and separate `migrate` / `serve` commands. Unregistered but allowlisted tools still fail before any
model call on the formal `AgentApplication` / `AgentRuntime` path. PostgreSQL persistence now
exposes `artifacts` / `layerWithArtifacts`; `AgentApplicationConfigLoader` can load
`zyblw.agent.role.bindings`. Docker/VM packaging lives in `deploy/docker/` and is the first
supported deployment shape; Kubernetes remains Preview until a real cluster is evidenced. Wave 0
host evidence items stay `deferred` until a dedicated hosted environment exists. The supported
business path is Docker talking directly to a self-hosted PostgreSQL; `scripts/verify-business-ready.sh`
is the adoption gate. The 0.7.0 candidate was superseded; do not tag `v0.7.0`. See
[the 0.9.0 upgrade guide](docs/fresh-install-0.9.0.md).
`ProductionSupportHost status` reports Flyway version and in-flight Run/command counts so hosts can
drain before switching processes.

MCP remains locked to the tested `2025-11-25` revision. The official `2026-07-28` stateless-core
revision is recognized and rejected with `unsupported_stateless_revision`; the client does not
silently downgrade or speak the new handshake. Artifact metadata already has a PostgreSQL adapter,
delete/expiry audit, and V011 tables; maturity docs no longer claim a missing durable store.
Removed multimodal and knowledge-graph shells stay deleted. Guardrails now have retrieval and
remote-message checkpoints; model-call lineage records the dispatched tool-definition fingerprint,
and Replayable reconstruction refuses a ledger whose frozen tool fingerprint does not match the
saved `CanonicalModelRequest`. Runtime checks retrieval snippets, non-metadata Context sections and Memory
before Context assembly, and remote tool-output excerpts after each tool batch.
Skill activation now honors a host allowlist and accepted trust set. `ProductionSupportHost` mounts Memory list/search/get/
correct/delete/export routes, a Memory retention worker, and a logging Outbox publisher on the
durable path. `AgentSchemaCensus` classifies dead projection tables (`model_calls`, `agent_messages`,
`agent_steps`, `usage_records`); `AgentSchemaManifest` is the checksum/row-count export used before a
fresh baseline switch. Flyway `V012` drops those tables when empty and fail-closes if they still
hold rows. Inspector can export a low-sensitivity `IncidentPack`. Artifact bytes can go to a
content-addressed `ArtifactBlobStore`. Workflow human tasks are `human.<taskType>` signals.
Skill catalogs expose a body-free catalog signature. Flyway `V013` lets artifact `bytes` be
NULL so PostgreSQL keeps metadata while `ArtifactBlobStore` holds content-addressed payloads.
`IncidentPackExporter` pages a Run timeline into a leak-checked incident pack.
Dedicated `Mcp2026Client` speaks the stateless `2026-07-28` contract (`server/discover`,
per-request `_meta`, `Mcp-Method`/`Mcp-Name`) without initialize/session; the 2025 client still
fail-closes that revision. `Mcp2026HttpTransport` is a separate POST-only Streamable HTTP
implementation: it sends `Mcp-Method`/`Mcp-Name`, rejects session/resume headers, and never
issues GET listener or DELETE. The 2026 client now has typed MRTR `input_required` retry
(echo `requestState`, never auto-complete), `subscriptions/listen` opt-in types, and
issuer/CIMD SSRF isolation. `IncidentPackCliApp` reads a pack from file or stdin.
Vision page transcription binds JPEG pages through `ArtifactBoundMedia` before the model. `ContentPart.ImageArtifact` is the durable image form;
`ArtifactBoundMedia.bind` produces only `data:` URIs, and Provider encoders reject remote
`ImageUrl`s. `ArtifactAccessGrant` keeps object-store reads tenant-scoped. A2A `1.0` Agent Card
parsing is fail-closed (HTTPS, securitySchemes, no private network) and does not grant local
tools. OTLP spans merge `GenAiSemanticMap` attributes and drop prompt keys. Workspace edits are inspect → validate → apply → rollback; OCI
sandbox rejects interactive shells. Handoff and agent-as-tool grants can only shrink tools,
budgets, and permissions. Workflow subgraphs use an isolated checkpoint store.
`IncidentPackCli` re-encodes leak-checked packs. Internal telemetry maps to versioned OpenTelemetry
GenAI operation names without prompts. Capability matrix fields now include prompt cache,
reasoning tokens, and server continuation.
`HumanTask.node` registers durable `human.<taskType>` waits; `WorkflowPromotionGate` refuses
to promote a workflow/multi-agent suite that does not beat a passing single-agent baseline on
outcome without safety or resource regression. `ArtifactBoundMedia` accepts only digest-matched
image/PDF artifacts and never turns them into remote `ImageUrl`s. Unused 1536 pgvector locations and
unrelated Vercel/React agent skills are removed. See ADR-0020.

Adds a second production-maturity wave that stays honest about host evidence. Local Docker
primary/standby failover lives in `integration-tests/failover-drill.sh` and is recorded as
`verified_local`; production RPO/RTO and soak remain `deferred`. Public PubMedQA/InjecAgent
72-case fixtures can pass a maintainer dual-review gate and a scripted `TestAgentRuntime` loop without claiming
domain-expert calibration.
Provider assembly now accepts a JSON multi-endpoint / relay declaration, `ModelRole` routing, and a
bounded `FallbackChatModel` that only retries typed outages. Artifact durability, Memory export,
MCP stdio command-digest allowlists, CapabilityMatrix vs `ProviderContract.verifySuite`, and a
low-evidence RAG refusal grade close the remaining peripheral holes. The admin console projects
composition, ModelCall, approval subjects, Harness budgets and Memory export without prompt or
artifact bodies. Observability adds `zyblw.agent.composition.drift.count`, Grafana panels and an
external-sink matrix.

Adds a durable **ModelCall ledger** so the main model request has the same Intent → Effect → Settlement | Unknown
window as tools. `RunStore.commit` / `commitFenced` can write `model_call_executions` in the same transaction as
state and events (Flyway `V004`). Production capture default is `MetadataOnly` (fingerprint and counts, no prompt).
`CapturePolicy.Replayable` stores a reconstructable `CanonicalModelRequest` for tests and authorized evals.
Crash after the intent commit marks the call `Unknown` and does **not** automatically re-invoke the provider.
Recovery also closes the window after a successful settlement if `Complete` never committed, and will not
reset already consumed budget after a crash that follows a persisted context checkpoint.

`CapturePolicy.Disabled` skips the model ledger (0.6.2 immediate invoke). Settlement persist failure or lost
lease at the Succeeded transition leaves the call `Unknown`.

Splits tool **online retry** from **crash replay** without changing `tool_executions`. Deployment `ToolRetryPolicy`
governs same-invocation 429/timeout; `ToolMetadata.recoveryPolicy` (`ReplaySafe` / `Idempotent` / `NeverReplay` /
`RequiresApproval`) governs whether a `Running`/`Unknown` ledger may be re-executed after process death. `Never`
online retry does not disable ReplaySafe crash replay. Destructive tools stay `RequiresApproval` even if they were
approved before the crash.

Scala SPI: `RunStore` gained `commit(..., modelCall)` / `commitFenced(..., modelCall)`, `getModelCall`, and
`getModelCalls`. Custom adapters must implement them. HTTP/Inspector expose request id, capture policy, counts and
fingerprint prefix only. `ToolMetadata.automaticallyRetryable` remains as an alias of `onlineRetryable`.

Freezes a **runtime composition fingerprint** on Run create (`submitStart` and sync `run`). Recovery compares the
frozen profile, instruction fingerprint, allowed tools, effective model overlay, and capture policy against the live
process. Missing tools still referenced by a pending plan, instruction changes, or model overlay changes are
`Incompatible`; profile/capture-only changes are `RequiresRevalidation`. Both fail closed (`CompositionIncompatible`)
instead of silent capability drift. Runs
created before this field remain recoverable. `AgentApplicationConfig.profile` productizes `CapturePolicy`
(production default `MetadataOnly`). Context contributors are composed through `ContextSourceResolver` without
Kernel changes; their `id@version` is frozen in the composition fingerprint. Eval `TrajectoryReplay` grades Replayable
ledger reconstruction against Fake Model recordings and rejects Inspector JSON that contains secret substrings.
`AgentEvalGrader` can attach that dimension. `TestAgentRuntime.inMemory` is the shared in-memory Runtime fixture.

Durable tool plans now freeze a canonical SHA-256 contract fingerprint for every called tool. The digest covers the
model-visible name/description/input/output Schema/strict flag and the Runtime-enforced risk, side-effect, scope,
redaction and conflict-parallelism metadata, while persisting none of that material a second time. Recovery resolves
the live registry before any approval or side effect and fails closed if a same-named tool changed or if a previously
missing tool appeared. Planning also freezes the call IDs that required approval: a later stricter deployment policy
may add approval, but a relaxed policy cannot remove the frozen requirement. New `AgentState` snapshots use schema v5
and reject incomplete plan fingerprints/approval snapshots; v4 and earlier plans retain the legacy recovery gates.
The new fields have safe JSON defaults for old readers and require a minor release rather than a `0.6.x` patch.

Human approval is now bound to an `ApprovalSubject` — the specific side effect — instead of a tool name or a
provider-supplied call ID. A subject binds the capability, call position, tool contract fingerprint, canonical input
digest, `ExecutionEnvironmentId`, the caller's tenant/principal/scope digest, the effective approval-policy digest, and
the declared risk and side-effect levels. Planning freezes a subject for every call that requires approval; the gate
recomputes it before any side effect and only reuses a recorded approval when every bound property still matches.
If a property drifted between suspension and approval, the runtime re-requests authorization with the refreshed subject
rather than honoring a decision the approver never saw, and the refreshed request carries a different approval ID so a
stale console submission is rejected. Monotonic tightening is preserved: a relaxed policy still cannot remove a frozen
requirement. `ApprovalRequest.subject` and `DurableToolPlan.approvalSubjects` move `AgentState` to schema v6; v6
snapshots missing a complete subject snapshot are treated as corrupt, while v5 and earlier keep their existing gates.
Subjects store digests only, so tool arguments are not persisted a second time. `TestAgentRuntime` gained
`inMemoryWithToolPolicySource` for tests that need to replace the effective policy mid-run.

Adds Wave 2 first cuts. World-state **ContextSection** snapshots compare fingerprints across turns: unchanged
payloads are omitted from the model-visible request, Secret sections are never rendered, and the decision is recorded
in `ModelCallContextLineage.sectionDecisions` without bodies. `AgentState` stores only `ContextSectionCursor`
fingerprints. `CanonicalModelRequest` remains the reconstructable authority for what the model actually saw.
`SkillCatalogSection` projects catalog identity as a Metadata section and never includes skill bodies.
HTTP declares `AgentProtocolStability` and `/api/v1/experimental`; experimental paths are excluded from the stable
OpenAPI promise until an explicit graduation.

Adds the Wave 1-C **constrained execution** surface (`com.zyblw.agent.execution`). `PermissionProfile` may only
narrow. `LocalExecutionEnvironment` makes host-JVM execution explicit; MCP workspace and OCI sandbox map through
`McpSandboxEnvironment` to `mcp-sandbox` (workspace root, no host network/process/secret access). Environment id and
permission digest bind into `ApprovalSubject` and `RuntimeCompositionFingerprint` without changing the composition
`value` hash, so old JSON missing the new fields still compares Compatible with Local/host. Switching to
`mcp-sandbox` or widening permissions is `Incompatible`. Default Local is not frozen into `extensionIds`.

Adds the Wave 1-B **typed extension** surface (`com.zyblw.agent.extension`). Extensions are narrow Scala traits
composed with `ZLayer`, never a plugin tree: `ToolProvider`, `SkillProvider`, `ApprovalReviewer` and
`ToolLifecycleObserver`. They receive only `ExtensionInput` (run/agent ids, authorization digest, composition
snapshot) and cannot see `AgentRuntimeLive` or `RunStore`. `ApprovalReview.Deny` fails closed before the side effect;
`RecommendAllow` cannot skip human approval or mutate `ApprovalSubject`. Tool lifecycle observers are `UIO` and their
defects are swallowed so observation cannot own invocation. Extension identities (`id@version`) freeze on Run create;
adding, removing or replacing an extension is `Incompatible`. Old composition JSON without `extensionIds` still
decodes and compares as empty. `AgentApplication` and `TestAgentRuntime` default to `RuntimeExtensions.empty`.

Adds the Wave 0 operations runbook and PostgreSQL v6 approval-subject gates. `PostgresApprovalSubjectIntegrationSpec`
round-trips a frozen subject through PostgreSQL 16 JSONB (V001–V010), checks `schema_version` envelope fail-closed,
and proves subject JSON does not contain tool-argument secrets. Host-side Pod loss, primary/standby failover and
hour-scale soak remain environment evidence; the runbook tells on-call how to read `queueSnapshot` /
`wakeQueueSnapshot` without inventing unmeasured SLOs.

Adds a **Harness** task-state SPI (`Goal` / `Plan` / `Todo` / `Skill`) with revision CAS. Goal `Active` does not start a Run. Plans cannot grant tools. Skills inject through `HarnessContextContributor` as untrusted retrieval data and cannot become System instructions. PostgreSQL Adapter is Flyway `V005` (`harness_goals` / `harness_plans` / `harness_skills`); in-memory remains for tests. Steer / FollowUp / UserMessage are append-only `InteractionInput` facts on a Goal (Flyway `V006`); they are not `RunCommandPayload` control commands and cannot cancel, recover, approve, or retry a Run.

Goal, Plan and Todo can now retain bounded typed `ArtifactReference` values. A reference fixes scope/name/version/media type/size/SHA-256 but contains neither bytes nor private metadata and grants no read authority; `ArtifactStore.read(reference)` revalidates the descriptor and fails closed on drift. `HarnessContextContributor` projects only reference metadata (`harness@2`) and cannot load artifact bodies. Flyway `V008` adds JSONB reference columns with empty-array defaults for rolling compatibility; existing JSON missing the new fields still decodes as empty references.

Adds a paired Harness evaluation gate. `HarnessEvalRunner` compares baseline and Harness observations for the same reviewed dataset, case and attempt under one bounded ZIO concurrency limit. It reports outcome/trajectory deltas, Harness safety failures, Wilson reliability, human-intervention delta, and latency/token/cost ratios; safety failures are never offset by resource gains. `EvalSuiteKind.HarnessComparison` stores only ten low-sensitive gate dimensions under an identity separate from Agent and AgentReliability. Flyway `V009` admits the new kind without changing existing snapshots.

Adds a durable Harness **Goal budget ledger**. `GoalBudgetPolicy` caps runs, model/tool calls, input/output/total tokens and optional estimated cost across concurrent Runs. `HarnessStore` now supports immutable configuration plus idempotent reserve/settle/release and bounded Reserved scans; in-memory uses `Ref.Synchronized`, while PostgreSQL `V010` uses one locked Goal counter row and stable per-Run reservations. Enabling a cost cap requires every Run to reserve an explicit cost limit. Usage above its reservation is still recorded as `Exceeded`, never rolled back or hidden; corrupt persisted `RunLimits`/`UsageSummary` JSON fails closed even though those domain types have construction defaults.

`HarnessCommandService` binds GoalId into the Start request fingerprint. PostgreSQL atomically commits the budget reservation with `AgentState(Created)`, `RunCreated`, the Start command and dispatcher, so exhausted admission leaves no orphan Run and HTTP replay consumes budget once. `HarnessBudgetReconciler` scans Reserved rows with a bounded keyset cursor and settles only durable terminal Run states; active or missing Runs remain reserved for recovery or explicit operator action. These capabilities still use the sole `AgentRuntime` and WorkerHost loop.

A shared Harness budget conformance suite now runs the same policy, reservation, transition, conflict, and cursor contracts against the in-memory and PostgreSQL adapters, preventing their state machines from drifting independently.

Adds a real PostgreSQL command-queue failure test that combines a vanished Worker with database pause/recovery. After the old lease expires, a new Worker must claim the same command at the next generation, stale completion is fenced, and the queue converges cleanly. A separate executable probe now starts the production PostgreSQL migration/store/`WorkerHost` path in forked JVMs, sends `SIGKILL` to the old JVM while it owns generation 1, and verifies that a different JVM completes the same command at generation 2 / attempt 2. Deployment-node loss and database failover remain environment gates.

Strengthens the PostgreSQL Workflow wakeup contract with the same combined outage boundary: a vanished wake Worker and database pause span the lease deadline, recovery is accepted only at the next generation, and stale heartbeat/abandon operations fail with lease loss.

Adds the corresponding executable Workflow process-death proof. The old forked JVM is killed only after it owns both the wake lease and the node-execution lease at generation 1. A different JVM must reclaim both at generation 2, consume the durable wait, commit the node ledger and terminal checkpoint, and leave no current wait. The probe reuses the production `WorkflowWakeWorker`, `WorkflowEngine`, migrations and PostgreSQL store; it is not a second workflow runtime.

CI now runs both process-death probes in a dedicated 20-minute job, with a 10-minute bound per exercise. Both paths restart the same PostgreSQL container after killing the old Worker, rediscover Docker's possibly changed random host port, and prove that durable state and lease fencing survive the database process restart. The release workflow runs the same gates before loading Maven Central signing credentials. Each invocation explicitly sets the forked JVM database environment, so a reused sbt thin server cannot retain the previous disposable container endpoint. This is restart evidence, not primary/standby failover evidence.

Adds a bounded durable-worker soak probe that repeatedly submits real PostgreSQL Start transactions and drains them through three production `WorkerHost` instances, six claim lanes and the sole `AgentRuntime`. Its versioned JSON report contains only aggregate worker/concurrency counts, queue high-water marks, generation/attempt counts and conservative 10ms latency histograms. The default local baseline completed 120/120 Runs with no retry, reclaim, expired lease or dead letter; claim and terminal P95 were 760ms and 980ms on that machine. These are repository regression thresholds, not production capacity or SLO claims. The executable script creates a disposable database and the probe refuses to run without an explicit disposable-database confirmation. CI and release run the bounded smoke before release secrets are loaded.

Adds the matching bounded Workflow wake-worker soak without introducing another workflow runtime. Each of three
production `WorkflowWakeWorker` instances owns an independent PostgreSQL Store adapter and engine, while all consume
the same durable signal backlog. The default local baseline completed 126/126 Runs across seven rounds, with exactly
126 wake claims, node-execution claims and completed cycles; all three wake/node owners participated, maximum node
concurrency was three, both generation-reclaim counts and every abandon/lease-lost/failure count were zero, and final
outstanding work was zero. Claim and terminal P95 were 270ms and 700ms on that machine. The JSON report is aggregate
only, the script requires its own disposable database, and CI/release treat the wide thresholds as regression gates,
not production SLOs.

Adds `WorkflowExecutionStore.wakeQueueSnapshot(workflowId, definitionVersion)`, a read-only low-sensitivity view of
pending/due waits, dispatchable/leased wakeups, expired wake leases and oldest dispatchable age. The in-memory and
PostgreSQL adapters share the same semantics; the PostgreSQL query uses one database-clock snapshot and never resolves,
claims or mutates a wait. Third-party Stores retain source compatibility through a concrete typed-failure default.
The Workflow soak now samples this production API and gates due waits, expired wake leases and final queue depth.

Fixes the in-memory `RunStore` transaction boundary: state CAS, events, tool executions, cancellation, and ModelCall ledger now share one `Ref.Synchronized` state. A failed ModelCall insert/transition no longer returns an error after already advancing the Run state or appending events. The focused store test asserts rollback of all three facts. The next-generation handbook and maturity roadmap now record the evidence-gated ideas worth adapting from LangChain/LangGraph, LLM4S, LlamaIndex, PydanticAI, OpenAI Agents SDK, Microsoft Agent Framework/Semantic Kernel, MetaGPT, Letta, and Pig without adding a second Runtime or authority path.

Adds one `RunStore` conformance suite shared by the in-memory Adapter and a real PostgreSQL 16 Testcontainer. Event IDs are now idempotent only when the complete persisted event is identical; reuse across a different Run, sequence, or payload fails instead of being silently dropped. Tool ledger preparation requires an existing Run in memory, matching the production foreign key, and event collection queries consistently return an empty page after deletion.

Hardens the state/event cursor invariant across transaction boundaries. `RunStore.save` can no longer change
`lastEventSequence` without events; `commit` / `commitFenced` require the first incoming sequence to immediately follow
the persisted cursor in the same CAS as the version update. `appendEvents` remains available for exact replay or repair
at or behind the state cursor, but cannot advance authoritative state. PostgreSQL performs the cursor check in the
transactional `UPDATE` predicate and locks replay boundaries; the shared conformance proves gap failures leave state,
events, version, and ledgers unchanged. Event sequences must be non-negative, event cursors start at `-1`, and Store
pages are capped at 4096 so direct Adapter callers cannot bypass the durable-stream boundary. This tightened public
Store semantic also proves that two commits racing on the same version and previous sequence have exactly one winner.
It ships only in the next minor, not a 0.6.x patch.

Treats PostgreSQL state, event, Tool ledger, and ModelCall ledger rows as typed persistence envelopes.
`PostgresRunStore` now cross-checks stable identity/status/version/attempt/index columns against decoded JSON on every
read, failing closed on drift instead of silently choosing one representation. A real PostgreSQL 16 tamper test covers
all four record families; diagnostics expose only the record ID and mismatched field, never the stored payload.

Bounds Harness interaction reads. `HarnessStore.listInteractions` now accepts an exclusive `beforeSequence` cursor and a hard 1–512 page limit, fetches the newest page, and returns it in sequence order. `HarnessContextContributor` asks the Store for only the latest 16 interactions instead of loading the complete Goal history. PostgreSQL reuses the existing `(goal_id, sequence)` unique index; no migration is added. Custom `HarnessStore` adapters must implement the new method signature.

Closes the Wave 2 safety gap around dynamic model settings and Skills. Runtime compositions now fingerprint complete effective
`ModelSettings`; every model call captures one working point, rejects mid-run overlay drift before dispatch, and records a
separate low-sensitive settings fingerprint in ModelCall lineage. `SkillContextContributor` wires host-selected
`SkillProvider.load` results into Retrieval only, while `HarnessSkillProvider` verifies loaded bodies against a body-free
catalog. World-state delivery defaults to `FullSnapshot` for stateless model APIs; omitting unchanged sections requires an
explicit `TrustedStatefulDelta` contract instead of assuming Providers retain hidden prior context.
`MemoryRagContextSourceResolver` v2 can additionally turn an explicit low-evidence Retriever result into a fixed trusted
refusal constraint without copying the query or document body into that instruction.

Removes unused experimental shells instead of promoting them by module count: `ActionFingerprint` had no consumer beyond its
declaration; the `rag/knowledge` graph SPI had no tests, durable adapter, depth-correct implementation, or retrieval integration;
and the standalone multimodal SPI had neither a Provider nor a consumer. The immutable V001 `model_calls` table remains only
because published Flyway migrations cannot be rewritten; it continues to have no writer and is superseded by
`model_call_executions`.

Adds a bounded, hash-pinned public evaluation import path for PubMedQA and InjecAgent. Public upstream provenance records immutable
revisions, licenses, source SHA-256 values, and deterministic selection protocols; generated datasets remain Draft until dual
review. Eval observations can now carry deterministic outcome labels, allowing yes/no/maybe correctness to remain separate from
tool, citation, safety, recovery, and resource grades.

Adds a local Docker evidence profile with PostgreSQL 16, transaction-mode PgBouncer, constrained backend pooling, command/workflow
soak, and backup/restore verification. The new pressure gate exposed and fixed global no-op dispatcher normalization acquiring
locks during every concurrent command claim; normalization now updates only mismatched rows through an ordered,
`SKIP LOCKED`-bounded candidate set.

MCP clients may now pin the expected initial `serverInfo.name/version`; a mismatch fails before `initialized` or any capability
call. The local `McpServerId` remains the authorization identity, while TLS/mTLS/OAuth and deployment provenance remain responsible
for cryptographic server authentication. `McpRootsProvider` adds an explicit, server-scoped `roots/list` handler for absolute local
file URIs; it never scans the working directory or enables sampling/elicitation as a side effect.

0.7.0 was superseded by the 0.8.0 fresh-install baseline, now a frozen published artifact;
current empty install is [the 0.9.0 upgrade guide](docs/fresh-install-0.9.0.md).

## 0.6.2 - 2026-08-16

Makes PDF ingestion operator-usable: quality-gated cascade with `extractionMode=auto` by default, optional operator override (`text|ocr|vision`), replay-safe Tika → Docling → page-bounded vision transcription, and fail-closed indexing when the extract is empty or CID garbage. Successful ingestions return compact extraction reports plus extracted Markdown/outline for the host to persist; the knowledge manifest still stores only short metadata. OpenAI-compatible Chat Completions now sends `image_url` content parts instead of stringifying images. Structure chunking may optionally use an approximate CJK token budget; the default `strategyId` is unchanged. Completes the PostgreSQL catalog dictionary for the 26 core control-plane tables.

Admin HTTP (explicitly evolutionary) accepts `extractionMode` on ingestion and surfaces requested/actual extraction on document views. No knowledge or core Flyway history changes.

That historical release is not an installation path for the current source line.

## 0.6.1 - 2026-08-13

Adds the production host-integration seam required by the full administration console without changing persisted schemas or
stable HTTP contracts. `AgentApplication.durableGoverned` consumes host-provided tool and model policy sources so saved runtime
overrides reach real executions. The dashboard can now run below a base path in `host-session` mode, where a same-origin BFF owns
the HttpOnly session and CSRF boundary; standalone Bearer-token deployments remain compatible. Production dashboard builds no
longer download Google Fonts.

## 0.6.0 - 2026-08-09

This release candidate establishes the fresh-install 1024-dimensional knowledge-index baseline used by the rebuilt platform RAG
integration. It is intentionally a minor release rather than a 0.5.x patch: it adds public migration entry points and a new
pgvector physical contract. It must be released to Maven Central before a server using `migrateCoreAndKnowledge1024` is built for
CI or production.

## 0.5.0 - 2026-08-07

Adds an optional administration sub-surface and the runtime resolver paths that make its overrides observable without a restart.
The Agent runtime, durable commands, business HTTP v1, workflow outcome v2 and the 0.4 knowledge schema are unchanged. Upgrading
without wiring any admin capability mounts no new routes, but the `V002` migration and two layer signature changes still apply —
the historical 0.5.0 release notes.

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

- Applications using custom Workflow nodes/checkpoint stores or custom
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
