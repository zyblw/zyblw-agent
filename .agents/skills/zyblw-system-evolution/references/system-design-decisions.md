# System-design decisions for zyblw

Use this reference as a decision checklist, not as a catalog of technologies to install.

## Begin with demand and quality attributes

Describe one concrete user or operator path and the load or failure it must survive. Rank the
attributes that matter for this decision:

- correctness and domain safety;
- availability and degraded behavior;
- latency and throughput;
- durability, consistency, and recoverability;
- security, privacy, and abuse resistance;
- operability and observability;
- compatibility and evolvability;
- delivery time and total cost.

A design that says “high availability” or “high scale” without a target, workload, or failure
domain is not yet a design.

## Ten decision lenses

### 1. Product flow and workload

Identify actors, request mix, read/write ratio, payload sizes, geographic distribution, traffic
shape, growth, retention, and the expensive path. Separate current measured demand from plausible
future ranges.

### 2. Boundaries and contracts

Locate the capability owner, API/module boundary, deployable unit, and release cadence. Prefer a
modular monolith or existing module until independent ownership, scaling, failure isolation, or
release needs justify a process boundary.

Treat Scala APIs, HTTP schemas, database schemas and migrations, serialized state, events, and
Maven artifacts as distinct compatibility surfaces.

### 3. Data and source of truth

For each piece of state, name:

- authoritative store and owner;
- identifier and partition/tenant scope;
- invariants and transaction boundary;
- read model or derived index;
- retention, deletion, lineage, and audit requirements;
- tolerated staleness and conflict behavior.

A cache, search index, event stream, projection, or prompt is not a second source of truth.

### 4. Flow, concurrency, and consistency

Choose synchronous or asynchronous flow from the user contract. Specify timeouts, cancellation,
backpressure, concurrency limit, ordering scope, idempotency key, retry class, and compensation.
State where strong consistency is required and where bounded staleness is acceptable.

“Exactly once” is not a free queue setting. Define the observable business invariant and make
processing replay-safe.

### 5. Capacity and performance

Estimate only quantities that can change the option:

- peak arrival rate and p50/p95/p99 service time;
- concurrency, fan-out, connection and worker limits;
- data size × growth × retention × replication;
- cache working set and expected locality;
- token input/output, retrieval fan-out, model latency, and cost;
- recovery backlog and time to drain.

Use measurements when available and ranges otherwise. Add instrumentation before architecture when
the answer depends on an unknown bottleneck.

### 6. Caching, indexing, and delivery

Use database indexes for access patterns, a cache for repeated expensive reads with acceptable
staleness, and a CDN for cacheable edge-deliverable content. Each needs key design, invalidation or
expiry, ownership, metrics, and a bypass or rebuild path.

Avoid caching correctness-critical authorization or mutable business state without explicit
freshness and revocation semantics.

### 7. Distribution and scaling

Vertical scaling is often the smallest first step. Horizontal replicas require stateless request
handling or explicit session/state placement, load-balancer health semantics, shared limits, and
failover testing.

Partitioning requires a stable key, even distribution, hot-key treatment, resharding, query
routing, cross-partition operation rules, and backup/restore proof. Consistent hashing is useful
only when membership churn and remapping cost are actual problems.

### 8. Failure and recovery

Trace failures at each boundary: timeout, cancellation, duplicate, partial success, dependency
outage, database failover, node kill, corrupt input, exhausted budget, and operator error. Define
retryable vs terminal errors, degraded behavior, recovery source, RPO/RTO, and failure-injection
tests.

Circuit breakers and retries can amplify outages. Bound attempts, add jitter where appropriate,
respect deadlines, and expose saturation.

### 9. Security and privacy

Model trust boundaries, authentication, authorization, tenant ownership, sensitive fields,
untrusted external content, prompt/tool injection, audit, retention, and deletion. Minimize data
movement and privilege. A new component creates another credential, dependency, patch, and incident
surface.

For zyblw medical or health-related behavior, use the repository safety skill and enforce policy in
typed runtime/code paths and tests, not only in prompts.

### 10. Operations and evolution

Define metrics, logs/traces, SLO indicators, dashboards/alerts, deployment order, backward
compatibility window, data backfill, dual-read/write hazards, rollback or forward-fix, and owner.
Prefer expand/migrate/contract changes over flag-day migrations.

## Mechanisms are conditional tools

| Mechanism | Evidence that can justify it | Obligations that come with it |
|---|---|---|
| Load balancer + replicas | saturation or availability target | health semantics, draining, shared state/limits, failover test |
| Cache/CDN | repeated expensive reads and acceptable staleness | keys, TTL/invalidation, stampede control, bypass, hit/miss metrics |
| Queue/event stream | buffering, asynchronous UX, or independent consumers | delivery contract, idempotency, ordering, retries, DLQ, lag and replay |
| Read replica | read pressure and tolerated lag | routing, consistency UX, failover and lag handling |
| Sharding | one-node capacity/availability limit | partition key, hot keys, rebalance, cross-shard rules, restore |
| Microservice | real ownership/release/scale/failure boundary | network contract, discovery, auth, consistency, deployment and tracing |
| CQRS/event sourcing | distinct read model or audit/rebuild requirement | event evolution, replay, projections, idempotency, privacy/deletion |
| Batch/MapReduce style work | large bounded offline computation | partitioning, retries, checkpointing, skew, result validation |

Do not combine mechanisms simply because they appear together in a case study.

## A compact decision record

Use this form in an issue, plan, or ADR:

```text
Outcome and scope:
Facts / inferences / unknowns:
Quality attributes and targets:
Current source of truth and request/data flow:
Observed or predicted failure:
Options (including current baseline):
Decision and non-goals:
Contracts and compatibility:
Failure / consistency / security / privacy:
Smallest rollout and migration:
Rollback or forward-fix:
Tests, metrics, and promotion threshold:
```

## Assessment of the supplied system-design repository

Reviewed source: [Complete-System-Design](https://github.com/Coder-World04/Complete-System-Design)
at commit `b92daa9595f23974e64e55d8ad0a942d75a64d56`.

It is useful as a broad interview-study index: scaling, load balancing, queues, caching, indexing,
networking, CAP, sharding, API design, concurrency, estimation, and case-study prompts. It is not a
production architecture method. The reviewed repository contains a long README and links, but no
executable models, implementation, tests, failure injection, capacity evidence, migrations,
security analysis, operational ownership, or rollback gates.

Therefore:

- use its topic list to discover questions that may have been missed;
- verify linked claims against primary documentation, code, measurements, or research;
- never copy a case-study topology into zyblw;
- never treat stars, breadth, or interview familiarity as production evidence.
