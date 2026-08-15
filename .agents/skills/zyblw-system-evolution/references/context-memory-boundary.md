# Context and memory boundary for zyblw agents

Use this reference when a proposal touches agent state, conversation history, prompts, compression,
memory, retrieval, RAG, citations, artifacts, or multi-agent delegation.

## Keep five artifacts separate

| Artifact | Purpose | Lifetime and authority | Must not become |
|---|---|---|---|
| Durable run state | workflow truth needed to resume, stop, retry, or audit a run | persisted state machine; runtime-authoritative | a prompt-only plan |
| Active context | selected tokens shown to one model inference | rebuilt per inference; bounded and non-authoritative | an append-only transcript or database |
| Long-term memory | distilled cross-run user/task facts or preferences | governed, scoped, versioned, correctable, expirable | raw history or trusted instructions |
| Knowledge/RAG | external source material used to answer a question | indexed corpus with lineage, authorization, freshness, and citations | user memory or uncited model knowledge |
| Artifact | large/raw/versioned content such as documents or outputs | durable object plus metadata/hash/reference | permanently inlined context |

A provider may cache or persist inputs operationally, so “ephemeral context” is a conceptual
boundary, not a universal storage guarantee.

## The retrieval-to-context boundary

Memory/RAG retrieval produces **candidates**. Context assembly decides which candidates are admitted
to a particular inference.

1. Reserve token space for trusted instructions, safety policy, required output, recent user intent,
   and expected completion.
2. Pass the remaining per-source budget and authorization scope into retrieval before broad search
   or reranking.
3. Retrieve compact candidates with stable IDs, provenance, timestamps, sensitivity, authority,
   relevance signals, and estimated token cost.
4. Filter ownership, authorization, expiry, deletion/tombstones, minimum evidence, and incompatible
   task scope.
5. Deduplicate and surface contradictions. Do not silently merge conflicting facts.
6. Select for task utility, authority, recency where relevant, diversity, and token cost—not
   similarity alone.
7. Place admitted items structurally with source labels. Keep trusted instructions separate from
   untrusted memory, RAG, user, and tool content.
8. Compress only when necessary. Preserve evidence quotes or stable references and keep the raw
   artifact retrievable outside the prompt.
9. Record candidate/admission/exclusion metadata and budget use without logging sensitive content.

Retrieval quality and context quality are different metrics. More retrieved text can reduce answer
quality even when recall increases.

## Context assembly contract

Every source needs:

- purpose and trust class;
- admission rule and priority;
- hard/soft token budget;
- deduplication key;
- placement and delimiters;
- compression and validation behavior;
- provenance and citation behavior;
- fail-open or fail-closed rule;
- observability that does not leak secrets.

Test exact-fit and overflow budgets, malformed tool/RAG content, conflicting evidence, compression
failure and fallback, missing citations, cancellation, and long-session degradation. Evaluate
outcome, evidence fidelity, safety, latency, and token/cost separately.

Placement guidance is a hypothesis to test per model/provider/task. The “lost in the middle”
research demonstrated positional degradation in multi-document QA and key-value retrieval; it is
not a universal law that every model or prompt always prefers the same ordering.

## Long-term memory is a governed write lifecycle

A memory write is an application decision, not a model side effect. Define:

1. **Trigger**: explicit user request, verified task outcome, controlled import, or extractor
   proposal.
2. **Eligibility**: durable future utility; do not store ordinary turns by default.
3. **Representation**: typed field/fact with stable namespace and schema version, not free-form
   instructions when structure is possible.
4. **Evidence and authority**: source reference, validation state, and who asserted it.
5. **Owner and scope**: user/tenant/agent/task visibility and authorization.
6. **Sensitivity**: prohibited, encrypted, consented, or retention-limited categories.
7. **Conflict/update**: precedence, compare-and-set/version behavior, correction, and uncertainty.
8. **Expiry**: TTL or explicit durable basis; freshness should be task-specific.
9. **Deletion**: user-visible correction/deletion, tombstones, derived-index cleanup, and audit.
10. **Maintenance**: deduplication, consolidation, confidence decay where justified, stale review,
    retention enforcement, and quality evaluation.

Trust is multidimensional. A user statement may be authoritative for a preference while still being
untrusted as executable instructions. Do not collapse provenance, confidence, authorization,
validation, recency, and sensitivity into one universal scalar threshold.

Memory content is always data. It cannot override system/developer policy, authorize tools, or
bypass medical and privacy controls.

## Apply this to the existing framework

Before proposing new abstractions, inspect the current contracts and canonical documents:

- `docs/context-memory-rag.md`
- `docs/memory-governance.md`
- `docs/instruction-context-cost.md`
- `modules/agent-core/src/main/scala/com/zyblw/agent/context/ContextManager.scala`
- `modules/agent-core/src/main/scala/com/zyblw/agent/context/llm/LlmContextCompressor.scala`
- `modules/agent-core/src/main/scala/com/zyblw/agent/memory/LongTermMemory.scala`
- `modules/agent-core/src/main/scala/com/zyblw/agent/memory/MemoryLifecycle.scala`
- `modules/agent-core/src/main/scala/com/zyblw/agent/memory/MemoryGovernance.scala`
- `modules/agent-core/src/main/scala/com/zyblw/agent/artifacts/ArtifactStore.scala`
- `modules/agent-rag/src/main/scala/com/zyblw/agent/rag/MemoryRagContextSourceResolver.scala`
- relevant deterministic specs under `modules/**/src/test`

The current zyblw-agent design already separates durable state, governed long-term memory,
retrieval, active context, and artifacts; has partitioned context budgets and provenance; and
contains lifecycle/governance and evidence-aware compression contracts. Treat this article as a
review lens for those contracts, not as authorization to build a parallel memory subsystem.

Consult the current maturity document before claiming gaps. High-value evidence work is likely to
include long-session fixed datasets, real retrieval/OCR and malicious-document corpora, user-facing
memory correction/deletion behavior in the private product, provider-specific context evaluation,
and production capacity/failure data. Verify these against the current tree before planning work.

## Admission gates for changes

Reject or defer a proposal when it:

- stores raw transcripts or chain-of-thought as ordinary long-term memory;
- allows model text to write/delete memory or authorize actions without runtime policy;
- retrieves first and tries to fit an unbounded result into context later;
- treats procedural memory as privileged prompt instructions;
- loses raw evidence during summarization;
- selects only by vector similarity or a single relevance score;
- introduces Redis, a new vector database, or another memory service because a generic diagram maps
  a memory type to that backend;
- ports a Python memory class into the Scala/ZIO domain without a demonstrated contract gap;
- introduces multi-agent coordination before fixed evals prove decomposition/context-isolation
  benefit over the single-agent baseline.

## Evaluation matrix

Measure at least the affected rows:

| Layer | Quality measures | Failure probes |
|---|---|---|
| Retrieval candidates | recall/precision at k, authority, diversity, freshness, deletion correctness | unauthorized, stale, duplicate, contradictory, poisoned source |
| Context assembly | admitted evidence, budget utilization, truncation/compression fidelity | overflow, lost evidence, bad placement, compressor/provider failure |
| Memory writes | precision of durable writes, correction/deletion success, conflict handling | low-confidence, sensitive, stale, concurrent update, tombstone resurrection |
| Agent outcome | task success, citation support, safety, trajectory, latency, tokens/cost | long session, tool failure, missing source, model/provider change |

Use a fixed dataset and report dimensions separately. A single “memory accuracy” or “agent quality”
score hides the trade-off that needs an architectural decision.

## Assessment of the supplied article

Source: [Context vs. Memory Engineering in Agentic AI Systems](https://x.com/beamnxw/status/2084647643314102600).

The article’s durable contribution is the separation of per-inference context engineering from
cross-call memory engineering, and the explicit retrieval boundary between them. Its emphasis on
write policy, token budget before retrieval, structural placement, provenance, expiry,
deduplication, conflict handling, and maintenance is directly useful.

Treat its implementation examples as illustrative:

- memory-type-to-storage mappings are options, not mandates;
- a generic Python `MemoryEntry` is not zyblw's domain contract;
- relevance-only greedy selection ignores authority, diversity, contradiction, and task utility;
- an expiry filter must deliberately support non-expiring entries rather than accidentally dropping
  null expiry values;
- scalar trust/confidence thresholds cannot replace typed policy and provenance;
- compression must preserve raw evidence references;
- multi-agent claims require local fixed-eval proof.

Further primary reading:

- [Anthropic: Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [OpenAI Cookbook: Context personalization](https://developers.openai.com/cookbook/examples/agents_sdk/context_personalization)
- [Lost in the Middle research paper](https://arxiv.org/abs/2307.03172)

Use those sources as hypotheses and evaluation guidance. Local code, contracts, tests, measurements,
and product policy remain authoritative for zyblw.
