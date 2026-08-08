# AGENTS.md

This repository is the public Scala 3 / ZIO 2 `zyblw-agent` framework. It must remain independently
cloneable, testable, publishable, and usable without access to any private zyblw product repository.

## Source of truth

Read `README.md`, `docs/README.md`, `docs/architecture.md`, `docs/maturity-and-roadmap.md`,
`docs/compatibility.md`, and `docs/releasing.md` before changing public APIs or release
configuration. Build definitions, sources, tests, migrations, and generated POMs take precedence
over roadmap prose. When code and a document disagree, fix the document.

Use the repository-local `zyblw-agent-development` skill for framework implementation, review,
documentation, source-learning, and releases. It routes version-sensitive ZIO and ZIO HTTP work to
their current official documentation without importing private product rules.

## Where things live

| Path | Contents | Published |
|---|---|---|
| `modules/agent-core` | Domain ADTs, runtime, tools, permissions, context, memory, scheduler, admin SPIs, application builder, observability SPI | yes |
| `modules/agent-rag` | Knowledge index, embedding governance, hybrid retrieval | yes |
| `modules/agent-document-loaders` | Tika / Docling loading and structure chunking | yes |
| `modules/agent-rerank` | External rerank HTTP protocol | yes |
| `modules/agent-evals` | Fixed-dataset evaluation, trends, release gate | yes |
| `modules/agent-providers` | OpenAI-compatible / Responses / Anthropic / Gemini adapters, model catalog and probe | yes |
| `modules/agent-postgres` | Flyway migrations, durable control plane, pgvector index, admin stores | yes |
| `modules/agent-zio-http` | HTTP v1 contract, routes, OpenAPI, host, admin API | yes |
| `modules/agent-mcp` | MCP client and controlled workspace | yes |
| `modules/agent-opentelemetry` | OTLP / Langfuse SDK and exporter | yes |
| `modules/agent-testkit` | Deterministic fakes and stubs | yes |
| `modules/agent-dashboard` | Next.js operations console | no (browser app) |
| `modules/agent-eval-cli`, `modules/agent-benchmarks`, `modules/agent-examples` | In-repo tooling and runnable examples | no |
| `integration-tests/maven-consumer` | Independent consumer that resolves only published artifacts | no |

Eleven Maven artifacts, one shared version line. Adding a twelfth requires a real dependency,
lifecycle, protocol, security, or license boundary plus an ADR.

## Current state

The version line is `0.5.0`. `0.3.0` froze the core control plane, `0.4.0` established structured
RAG on a dedicated knowledge schema, and `0.5.0` adds the optional administration sub-surface,
runtime configuration overrides and model governance without changing the business mainline.

Maturity is tracked per capability in `docs/maturity-and-roadmap.md`, not per module. As of `0.5.0`:

- **Foundation**: runtime loop, typed tools and policy, durable command worker, layered
  instructions, business HTTP v1, run inspection.
- **Beta**: context and memory, RAG and document loading, providers, admin surface and console,
  model governance, PostgreSQL, OTLP/Langfuse, cost estimation.
- **Experimental**: workflow graph, artifacts, side-effect tooling, MCP, workspace/sandbox,
  multimodal, evaluation trend gating.

Never promote a capability in a document without new test, failure-injection, or production
evidence. "Implemented" is not "production proven".

## Direction

Deepen the verified mainline instead of widening the module surface. In priority order:

1. Long-running soak, node kill, database failover and capacity curves for the durable worker and
   the workflow wake worker; publish SLOs from `queueSnapshot`.
2. Outcome / trajectory / safety / resource scoring separated in evals, with confidence intervals
   and human calibration, before any release claims quality improvements.
3. RAG hardening: real OCR and malicious-PDF corpora, tokenizer-aligned chunking, low-evidence
   refusal gating, retention workers.
4. Agent harness (goal / plan / todo / skill) as durable auditable state, not prompt text.
5. Interoperability and multi-agent only after fixed evals show a single agent is insufficient.

Do not add multi-agent orchestration, a graph studio, GraphRAG, or a general transaction platform
speculatively. Do not save full chain-of-thought as an audit record.

## Architecture rules

- Keep provider-neutral ADTs, runtime, permissions, tools, context, and memory in `agent-core`.
- Keep database, HTTP, document loading, MCP, providers, reranking, and telemetry optional.
- The model proposes actions; the runtime validates, authorizes, executes, traces, and stops them.
- Compose resources with scoped `ZLayer`; preserve interruption and cancellation semantics. Do not
  hide global singletons inside builders, and never fall back to in-memory stores in production
  entry points when the database fails.
- Snapshots exist for recovery, events and ledgers for audit; inspector, trace, and eval views are
  read-only low-sensitivity projections, never a second source of truth.
- Administration capabilities are host-supplied `Option`s. An unwired capability mounts no route,
  and `GET /api/v1/admin/capabilities` must report it as unavailable.
- Do not depend on `zyblw-server`, `zyblw-web`, private schemas, private fixtures, or private CI.

## Compatibility

The project uses early SemVer during `0.x`. Patch releases preserve public Scala APIs within the
minor line. Treat Scala APIs, HTTP/schema contracts, state JSON, Maven coordinates, and Flyway
migrations as separate compatibility surfaces. Never edit a migration that has been released.

`/api/v1/admin/**` is a deliberately Beta surface outside the stable `AgentHttpContract` OpenAPI
promise; anything that needs long-term integration stability belongs on the business `/api/v1`.

## Implementation order

1. Define or amend the domain contract and its typed error or stop reason.
2. Add deterministic tests for success, denial, malformed input, timeout, cancellation, budget
   exhaustion, and redaction as relevant.
3. Implement provider and infrastructure adapters behind narrow traits.
4. Add an executable example or consumer test when a public API changes.
5. Update the existing canonical document and `CHANGELOG.md`; add an ADR only for a durable
   cross-cutting decision.

## Verification

Run the smallest relevant checks during development and the full gates before a release:

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.5.0-local"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.5.0-local sbt -batch compile
```

Console changes additionally require, in `modules/agent-dashboard`:

```bash
npx tsc --noEmit && npm run lint && npm run build && npm run test:e2e
```

`test` is incremental in sbt 2; CI, releases, and PostgreSQL contracts must use `testFull`. Live
provider tests are opt-in and must never run on an ordinary pull request.

A test that fails intermittently is a defect, not noise. Fix the coupling that makes it racy;
do not raise the timeout and move on. Running many `testFull` rounds against one long-lived sbt
server can exhaust class space — restart the server rather than shrinking the test suite.

## Security and documentation

Never commit credentials, private product data, raw provider responses, sensitive prompts, or
production traces. External content and connector output are untrusted data. The framework accepts,
returns, and stores no provider API key: credentials are referenced as `env:VARIABLE` plus a
presence flag.

Update an existing canonical document instead of adding dated status notes, and distinguish
implemented, tested, experimental, and planned behavior. Documents carry a `最后核验` date; refresh
it when you verify the content, not when you touch the file.
