#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
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

census_path = pathlib.Path("modules/agent-postgres/src/main/scala/com/zyblw/agent/persistence/postgres/AgentSchemaCensus.scala")
census = census_path.read_text(encoding="utf-8")
authoritative_block = census.split("val Authoritative: Chunk[String] = Chunk(", 1)
if len(authoritative_block) != 2:
    raise SystemExit("could not parse AgentSchemaInventory.Authoritative")
authoritative_src = authoritative_block[1].split(")", 1)[0]
tables = [line.strip().strip(",").strip('"') for line in authoritative_src.splitlines() if '"' in line]
if not tables:
    raise SystemExit("Authoritative table list is empty")

v001 = pathlib.Path(
    "modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/migration/V001__zyblw_agent_0_9_baseline.sql"
).read_text(encoding="utf-8")
missing_ddl = [table for table in tables if f"CREATE TABLE {table}" not in v001]
if missing_ddl:
    raise SystemExit("authoritative tables missing V001 DDL: " + ", ".join(missing_ddl))

# 0.9.0 尚未发布，当前仓库采用可破坏重建的 fresh-install 基线。这里检查目录全集，而不是只探测几个已知旧文件，
# 防止误加 V002 或遗留历史 migration 后让“空库唯一 V001”退化成只存在于文档里的约定。
core_migration_dir = pathlib.Path(
    "modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/migration"
)
knowledge_migration_dir = pathlib.Path(
    "modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/optional/pgvector_1024"
)
expected_core = {
    "V001__zyblw_agent_0_9_baseline.sql",
    "R__zyblw_agent_schema_comments.sql",
}
expected_knowledge = {
    "V001__agent_knowledge_0_9_baseline.sql",
    "R__agent_knowledge_1024_comments.sql",
}
actual_core = {path.name for path in core_migration_dir.glob("*.sql")}
actual_knowledge = {path.name for path in knowledge_migration_dir.glob("*.sql")}
if actual_core != expected_core:
    raise SystemExit(
        "core migration directory must contain exactly the 0.9 V001 and repeatable comments: "
        + ", ".join(sorted(actual_core))
    )
if actual_knowledge != expected_knowledge:
    raise SystemExit(
        "knowledge migration directory must contain exactly the 0.9 V001 and repeatable comments: "
        + ", ".join(sorted(actual_knowledge))
    )

test_root = pathlib.Path("modules/agent-postgres/src/test/scala")
spec_files = list(test_root.rglob("*ConformanceSpec.scala")) + list(
    test_root.rglob("*IntegrationSpec.scala")
)
if not spec_files:
    raise SystemExit("no postgres ConformanceSpec or IntegrationSpec files found")
spec_corpus = "\n".join(path.read_text(encoding="utf-8") for path in spec_files)
missing_specs = [table for table in tables if table not in spec_corpus]
if missing_specs:
    raise SystemExit(
        "authoritative tables missing ConformanceSpec/IntegrationSpec evidence: "
        + ", ".join(missing_specs)
    )

print(json.dumps({
    "manifest": "valid",
    "items": len(items),
    "pendingExternal": pending,
    "pendingEvalHost": eval_pending,
    "authoritativeTables": len(tables),
    "freshInstallBaselines": 2
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
