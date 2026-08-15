---
name: zyblw-system-evolution
description: Evaluate and guide architecture, system-design, reliability, scaling, data-boundary, migration, and agent context/memory/RAG changes across zyblw-agent and zyblw-platform. Use for cross-cutting changes, new infrastructure or distributed-system mechanisms, compatibility changes, production bottlenecks or failures, ADRs and roadmaps, and decisions about whether an external system-design idea belongs in zyblw. Do not use for an isolated routine edit with no architectural or operational trade-off.
---

# zyblw System Evolution

Turn architecture ideas into repository-grounded, reversible decisions. Optimize for the smallest
change that improves a named user or operational outcome; do not optimize for the number of
technologies, modules, or patterns adopted.

## Start with local truth

1. Read the nearest `AGENTS.md`, the repository-local implementation skill, and the shortest
   canonical documentation path for the affected capability.
2. Inspect current contracts, implementation, tests, migrations, metrics, and maturity claims.
   Treat these as evidence in descending order; never infer production readiness from class names.
3. State the business or operator outcome, affected repository and modules, compatibility surfaces,
   quality attributes, and executable completion criteria.
4. Keep a tiny evidence ledger:
   - **Fact**: directly supported by code, tests, measurement, or a primary source.
   - **Inference**: plausible conclusion that names its supporting facts.
   - **Unknown**: information that could change the decision and how to measure it.
5. Read [references/system-design-decisions.md](references/system-design-decisions.md) for any
   architectural decision. Also read
   [references/context-memory-boundary.md](references/context-memory-boundary.md) when the change
   touches agent state, context, memory, RAG, artifacts, retrieval, prompting, or multi-agent work.

## Classify before choosing technology

Classify the pressure first:

- product flow and actual workload;
- API, module, ownership, and release boundary;
- source of truth, schema, consistency, and retention;
- synchronous flow, concurrency, ordering, and idempotency;
- latency, throughput, storage growth, token use, and cost;
- partial failure, retry, recovery, and degraded mode;
- authentication, authorization, privacy, and abuse;
- observability, deployment, migration, and rollback;
- agent run state, active context, long-term memory, knowledge retrieval, or artifacts.

A technology name is not a requirement. If the pressure cannot be located in this list and tied to
evidence, keep the current design and instrument the uncertainty.

## Make the decision

1. Draw the current request/data/state path and identify the present source of truth.
2. Describe the observed or predicted failure mode. For a prediction, state the workload range or
   assumption that activates it.
3. Estimate only decision-sensitive quantities: peak and percentile load, service time and
   concurrency, data growth and retention, fan-out, token budget, recovery time, or cost. Use ranges
   when measurements do not exist.
4. Compare the current baseline with at most three credible options. For each option record:
   outcome fit, operational burden, compatibility risk, failure modes, security/privacy impact,
   migration effort, reversibility, and evidence still needed.
5. Prefer the simplest option that meets the required quality attributes. Separate the decision
   from possible later optimizations.
6. Define contracts and ownership: input/output schema, source of truth, consistency expectation,
   timeout/retry/idempotency behavior, sensitive-data policy, and who can change or delete state.
7. Plan a smallest vertical slice with deterministic tests, observable acceptance metrics, staged
   rollout, and a concrete rollback or forward-fix path.
8. Update an existing canonical document. Add an ADR only when the decision is durable,
   cross-cutting, costly to reverse, or establishes a compatibility boundary.

## Respect zyblw boundaries

- `zyblw-agent` is an independently cloneable and publishable public framework. It must not depend
  on private product schemas, fixtures, policies, or release timing.
- `zyblw-platform` owns product journeys, private business data, medical-safety policy, and UI.
  It consumes an explicit released or Maven-local framework contract.
- Never hide a breaking public contract by changing both repositories at once. Version, test,
  document, migrate, and provide rollback for the consumer.
- Keep provider-neutral domain/runtime contracts separate from database, HTTP, provider, document,
  telemetry, and UI adapters.
- Extend the existing Scala/ZIO agent runtime and its Context, Memory, RAG, Artifact, Citation, and
  Eval contracts. Do not add a second Python or JavaScript production agent runtime.
- Route implementation details through the repository-local ZIO, ZIO HTTP, Next.js, frontend, and
  medical-safety skills as applicable.

## Mechanism admission gates

Do not introduce the following without the named evidence and obligations:

- **Cache/CDN**: measured expensive or remote reads, stable keys, bounded staleness, invalidation
  ownership, hit/miss metrics, and a safe bypass.
- **Queue/event bus**: a real asynchronous, buffering, or decoupling need plus delivery semantics,
  idempotency, ordering scope, retry/backoff, poison-message handling, and lag visibility.
- **Microservice**: an independently owned capability and release/scale/failure boundary whose value
  exceeds network, consistency, deployment, and observability costs.
- **Replication/sharding/consistent hashing**: measured capacity or availability pressure plus
  partition key, rebalancing, hot-key, failover, and recovery design.
- **New search/vector store**: retrieval corpus and quality target, lineage and authorization,
  freshness/deletion, fixed eval set, latency/cost budget, and proof the existing store is
  insufficient.
- **Multi-agent orchestration**: fixed evaluations showing a single agent is insufficient, a
  decomposable task, explicit context-isolation benefit, budget/stop rules, and failure containment.
- **Long-term memory write**: eligibility, evidence and authority, namespace/owner, sensitivity,
  conflict/update behavior, expiry, deletion, audit, and maintenance policy.

## Required handoff

For a non-trivial decision, return:

1. decision and non-goals;
2. facts, inferences, and decision-changing unknowns;
3. current path and proposed contract/data flow;
4. alternatives and why they were rejected;
5. failure, consistency, security, privacy, and compatibility analysis;
6. smallest rollout slice, migration and rollback;
7. tests, metrics, thresholds, and remaining risks.

If evidence is insufficient, recommend the measurement or experiment—not a speculative platform.
