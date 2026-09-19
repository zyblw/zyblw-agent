#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose_file="$repo_root/integration-tests/local-staging/compose.yml"
mode=${1:---quick}
report_dir="$repo_root/target/local-staging"
command_log="$report_dir/command-soak.log"
workflow_log="$report_dir/workflow-soak.log"
backup_file="$report_dir/zyblw.dump"
report_file="$report_dir/evidence.json"

case "$mode" in
  --quick)
    min_rounds=4
    runs_per_round=24
    min_duration_seconds=5
    ;;
  --full)
    min_rounds=${ZYBLW_AGENT_LOCAL_STAGING_MIN_ROUNDS:-20}
    runs_per_round=${ZYBLW_AGENT_LOCAL_STAGING_RUNS_PER_ROUND:-100}
    min_duration_seconds=${ZYBLW_AGENT_LOCAL_STAGING_MIN_DURATION_SECONDS:-3600}
    ;;
  *)
    echo "usage: $0 [--quick|--full]" >&2
    exit 64
    ;;
esac

for command in docker sbt python3; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command not found: $command" >&2
    exit 1
  fi
done
docker compose version >/dev/null
mkdir -p "$report_dir"

compose() {
  docker compose --file "$compose_file" "$@"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

cleanup
compose up --detach --wait

jdbc_url="jdbc:postgresql://127.0.0.1:${ZYBLW_LOCAL_PGBOUNCER_PORT:-56432}/zyblw"
common_env="\"ZYBLW_AGENT_JDBC_URL\" -> \"$jdbc_url\", \"ZYBLW_AGENT_DB_USER\" -> \"zyblw\", \"ZYBLW_AGENT_DB_PASSWORD\" -> \"local-staging-only\""

run_soak() {
  local probe_class=$1
  local output=$2
  (
    cd "$repo_root"
    sbt -batch \
      ";set examples / Compile / run / fork := true; set examples / Compile / run / envVars := Map($common_env, \"ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE\" -> \"true\", \"ZYBLW_AGENT_SOAK_MIN_ROUNDS\" -> \"$min_rounds\", \"ZYBLW_AGENT_SOAK_RUNS_PER_ROUND\" -> \"$runs_per_round\", \"ZYBLW_AGENT_SOAK_WORKERS\" -> \"8\", \"ZYBLW_AGENT_SOAK_PARALLELISM_PER_WORKER\" -> \"4\", \"ZYBLW_AGENT_SOAK_MIN_DURATION_SECONDS\" -> \"$min_duration_seconds\", \"ZYBLW_AGENT_SOAK_MAX_CLAIM_P95_MILLIS\" -> \"10000\", \"ZYBLW_AGENT_SOAK_MAX_TERMINAL_P95_MILLIS\" -> \"30000\", \"ZYBLW_AGENT_SOAK_TIMEOUT_SECONDS\" -> \"$((min_duration_seconds + 300))\", \"ZYBLW_AGENT_WORKFLOW_SOAK_CONFIRM_DISPOSABLE\" -> \"true\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MIN_ROUNDS\" -> \"$min_rounds\", \"ZYBLW_AGENT_WORKFLOW_SOAK_RUNS_PER_ROUND\" -> \"$runs_per_round\", \"ZYBLW_AGENT_WORKFLOW_SOAK_WORKERS\" -> \"8\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MIN_DURATION_SECONDS\" -> \"$min_duration_seconds\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MAX_CLAIM_P95_MILLIS\" -> \"10000\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MAX_TERMINAL_P95_MILLIS\" -> \"30000\", \"ZYBLW_AGENT_WORKFLOW_SOAK_TIMEOUT_SECONDS\" -> \"$((min_duration_seconds + 300))\"); examples/runMain $probe_class"
  ) | tee "$output"
}

run_soak com.zyblw.agent.examples.DurableWorkerSoakProbe "$command_log"
run_soak com.zyblw.agent.examples.WorkflowWakeWorkerSoakProbe "$workflow_log"

compose exec --no-TTY postgres pg_dump --username zyblw --dbname zyblw --format custom >"$backup_file"
compose exec --no-TTY postgres createdb --username zyblw zyblw_restore
compose exec --no-TTY postgres pg_restore \
  --username zyblw \
  --dbname zyblw_restore \
  --no-owner <"$backup_file"
required_tables=$(
  compose exec --no-TTY postgres psql \
    --username zyblw \
    --dbname zyblw_restore \
    --tuples-only \
    --no-align \
    --command "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('agent_runs','agent_run_commands','model_call_executions','agent_workflow_checkpoints');"
)
if [[ "$required_tables" != "4" ]]; then
  echo "restored database is missing required durable tables: found $required_tables/4" >&2
  exit 1
fi

python3 - "$mode" "$command_log" "$workflow_log" "$report_file" "$required_tables" <<'PY'
import datetime
import json
import pathlib
import sys

mode, command_path, workflow_path, report_path, table_count = sys.argv[1:]

def soak_report(path):
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        marker = "SOAK_REPORT "
        if marker in line:
            return json.loads(line.split(marker, 1)[1])
    raise SystemExit(f"missing SOAK_REPORT in {path}")

report = {
    "schemaVersion": 1,
    "classification": "local_docker_regression_only",
    "mode": mode.removeprefix("--"),
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "topology": {
        "postgres": "postgres:18-alpine",
        "pgbouncer": "edoburu/pgbouncer:v1.25.1-p0",
        "poolMode": "transaction",
        "defaultPoolSize": 4,
        "maxClientConnections": 256,
    },
    "command": soak_report(command_path),
    "workflow": soak_report(workflow_path),
    "backupRestore": {"requiredTablesFound": int(table_count), "passed": True},
    "limitations": [
        "not a PostgreSQL primary/standby failover",
        "not a Kubernetes node or availability-zone loss",
        "not a customer-facing SLO",
    ],
}
pathlib.Path(report_path).write_text(
    json.dumps(report, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(json.dumps({"localStagingEvidence": "passed", "report": report_path}, separators=(",", ":")))
PY

if [[ "$mode" == "--full" ]]; then
  "$repo_root/integration-tests/command-worker-kill-recovery.sh" --restart-postgres
  "$repo_root/integration-tests/workflow-wake-worker-kill-recovery.sh" --restart-postgres
fi
