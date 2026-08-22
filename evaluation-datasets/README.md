# Public evaluation dataset inputs

This directory stores acquisition code and immutable source metadata, not third-party dataset copies.

```bash
./evaluation-datasets/prepare-public-eval-dataset.py
```

The importer downloads exact revisions of:

- PubMedQA PQA-L (MIT), stratified to eight `yes`, eight `no`, and eight `maybe` cases;
- InjecAgent direct-harm base cases (MIT), stratified across attack types;
- InjecAgent data-stealing base cases (MIT), stratified across attack types.

Every source has a fixed commit and SHA-256 in `public-sources.lock.json`. Downloads are HTTPS-only in the lock, byte-bounded,
hash-checked, and transformed deterministically. Generated data belongs under `target/public-evals/` and is intentionally not
vendored or published as a Maven resource.

The generated `AgentEvalDataset` is always `Draft`. It provides real public examples for local development, structured
yes/no/maybe outcome labels, citation expectations, and forbidden attacker tools. It is not a private business dataset and cannot
pass the release gate until two independent maintainers review the selected cases, record their organizational identities, advance
the dataset version if they edit content, and regenerate the content digest. Public benchmark success also does not authorize any
production tool or replace tenant-specific evaluation.

A separate, maintainer-reviewed 72-case fixture lives in `PublicEvalClosedLoop` (36 PubMedQA labels + 36 InjecAgent
refusals). It is labeled **维护者审查，非领域专家校准**：two organizational reviewer IDs, no private patient text, and a
deterministic grader plus `TestAgentRuntime` closed loop. CI smoke is `integration-tests/eval-release-gate-smoke.sh`
(file-backend CLI). Host domain calibration and production trend baselines remain `pending_host`.
