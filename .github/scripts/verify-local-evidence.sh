#!/usr/bin/env bash

set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

mode="${1:---manifest}"
manifest="docs/evidence/wave-0-manifest.json"
eval_manifest="docs/evidence/eval-manifest.json"

python3 - "$manifest" "$eval_manifest" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
allowed = {"verified_local", "verified_ci", "verified_host", "pending_host", "blocked_external", "deferred"}
items = data.get("items")
if data.get("schemaVersion") != 1 or not isinstance(items, list) or not items:
    raise SystemExit("invalid Wave 0 evidence manifest envelope")

seen = set()
for item in items:
    item_id = item.get("id")
    status = item.get("status")
    if not isinstance(item_id, str) or not item_id or item_id in seen:
        raise SystemExit(f"invalid or duplicate evidence id: {item_id!r}")
    seen.add(item_id)
    if status not in allowed:
        raise SystemExit(f"{item_id}: unsupported status {status!r}")
    evidence = item.get("evidence")
    if not isinstance(evidence, list) or not evidence:
        raise SystemExit(f"{item_id}: evidence paths are required")
    for raw in evidence:
        candidate = pathlib.Path(raw)
        if candidate.is_absolute() or ".." in candidate.parts or not candidate.exists():
            raise SystemExit(f"{item_id}: missing or unsafe evidence path {raw!r}")
    if status == "verified_host" and (not item.get("owner") or not item.get("measurements")):
        raise SystemExit(f"{item_id}: verified_host requires owner and measurements")

pending = sum(item["status"] in {"pending_host", "blocked_external", "deferred"} for item in items)

eval_data = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
if eval_data.get("schemaVersion") != 1:
    raise SystemExit("invalid eval evidence manifest schema")
for mechanism in eval_data.get("mechanisms", []):
    if mechanism.get("status") not in {"verified_local", "verified_ci"}:
        raise SystemExit(f"{mechanism.get('id')}: eval mechanism is not verified")
    for raw in mechanism.get("evidence", []):
        candidate = pathlib.Path(raw)
        if candidate.is_absolute() or ".." in candidate.parts or not candidate.exists():
            raise SystemExit(f"{mechanism.get('id')}: missing or unsafe evidence path {raw!r}")
eval_pending = sum(item.get("status") == "pending_host" for item in eval_data.get("hostEvidence", []))

print(json.dumps({
    "manifest": "valid",
    "items": len(items),
    "pendingExternal": pending,
    "pendingEvalHost": eval_pending
}, separators=(",", ":")))
PY

case "$mode" in
  --manifest)
    ;;
  --full)
    sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
    RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
    ./integration-tests/command-worker-kill-recovery.sh --restart-postgres
    ./integration-tests/workflow-wake-worker-kill-recovery.sh --restart-postgres
    ./integration-tests/durable-worker-soak.sh
    ./integration-tests/workflow-wake-worker-soak.sh
    ./integration-tests/failover-drill.sh
    printf '%s\n' '{"localEvidence":"passed","externalEvidence":"pending_host"}'
    ;;
  *)
    echo "usage: $0 [--manifest|--full]" >&2
    exit 64
    ;;
esac
