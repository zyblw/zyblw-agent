#!/usr/bin/env bash
# 本地 PostgreSQL 流复制主备切换演练。
#
# 职责分界：
#   本脚本证明框架在「主库进程消失、备库提升为可写」之后，仍能用同一 JDBC 契约完成
#   Flyway 已存在的 schema 上的 Worker soak，并把 RPO/RTO 写成机器可读报告。
#   它不替代云厂商托管 HA、不校准生产 SLO，也不把单机 Docker 结果升级为 verified_host。
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose_file="$repo_root/integration-tests/local-staging/compose-ha.yml"
report_dir="$repo_root/target/local-ha"
primary_log="$report_dir/primary-soak.log"
standby_log="$report_dir/promoted-soak.log"
report_file="$report_dir/failover-evidence.json"
primary_port="${ZYBLW_HA_PRIMARY_PORT:-55433}"
standby_port="${ZYBLW_HA_STANDBY_PORT:-55434}"
min_rounds=${ZYBLW_HA_FAILOVER_MIN_ROUNDS:-2}
runs_per_round=${ZYBLW_HA_FAILOVER_RUNS_PER_ROUND:-8}
min_duration_seconds=${ZYBLW_HA_FAILOVER_MIN_DURATION_SECONDS:-3}

for command in docker sbt python3; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command not found: $command" >&2
    exit 1
  fi
done
docker compose version >/dev/null
mkdir -p "$report_dir"
chmod +x "$repo_root/integration-tests/local-staging/ha/primary-init/00-replicator.sh"

compose() {
  docker compose --file "$compose_file" "$@"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

cleanup
if ! compose up --detach --wait; then
  echo "compose failed to become healthy; dumping container logs" >&2
  compose logs >&2 || true
  exit 1
fi

wait_for_streaming() {
  local ready=false
  for _ in $(seq 1 90); do
    local count
    count=$(compose exec --no-TTY postgres-primary psql \
      --username zyblw \
      --dbname zyblw \
      --tuples-only \
      --no-align \
      --command "SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming';" | tr -d '[:space:]')
    if [[ "$count" == "1" ]]; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then
    echo "standby never entered streaming replication" >&2
    compose logs >&2 || true
    exit 1
  fi
}

wait_for_streaming

sql_primary() {
  compose exec --no-TTY postgres-primary psql --username zyblw --dbname zyblw --tuples-only --no-align --command "$1"
}

sql_standby() {
  compose exec --no-TTY postgres-standby psql --username zyblw --dbname zyblw --tuples-only --no-align --command "$1"
}

primary_lsn=$(sql_primary "SELECT pg_current_wal_lsn();" | tr -d '[:space:]')
standby_replay_lsn=$(sql_standby "SELECT pg_last_wal_replay_lsn();" | tr -d '[:space:]')

run_soak() {
  local jdbc_url=$1
  local output=$2
  local common_env="\"ZYBLW_AGENT_JDBC_URL\" -> \"$jdbc_url\", \"ZYBLW_AGENT_DB_USER\" -> \"zyblw\", \"ZYBLW_AGENT_DB_PASSWORD\" -> \"local-ha-only\""
  (
    cd "$repo_root"
    sbt -batch \
      ";set examples / Compile / run / fork := true; set examples / Compile / run / envVars := Map($common_env, \"ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE\" -> \"true\", \"ZYBLW_AGENT_SOAK_MIN_ROUNDS\" -> \"$min_rounds\", \"ZYBLW_AGENT_SOAK_RUNS_PER_ROUND\" -> \"$runs_per_round\", \"ZYBLW_AGENT_SOAK_WORKERS\" -> \"2\", \"ZYBLW_AGENT_SOAK_PARALLELISM_PER_WORKER\" -> \"2\", \"ZYBLW_AGENT_SOAK_MIN_DURATION_SECONDS\" -> \"$min_duration_seconds\", \"ZYBLW_AGENT_SOAK_MAX_CLAIM_P95_MILLIS\" -> \"15000\", \"ZYBLW_AGENT_SOAK_MAX_TERMINAL_P95_MILLIS\" -> \"45000\", \"ZYBLW_AGENT_SOAK_TIMEOUT_SECONDS\" -> \"$((min_duration_seconds + 240))\"); examples/runMain com.zyblw.agent.examples.DurableWorkerSoakProbe"
  ) | tee "$output"
}

run_soak "jdbc:postgresql://127.0.0.1:${primary_port}/zyblw" "$primary_log"

count_table() {
  local role=$1
  local table=$2
  if [[ "$role" == "primary" ]]; then
    sql_primary "SELECT count(*) FROM ${table};" | tr -d '[:space:]'
  else
    sql_standby "SELECT count(*) FROM ${table};" | tr -d '[:space:]'
  fi
}

pre_runs=$(count_table primary agent_runs)
pre_commands=$(count_table primary agent_run_commands)
pre_model_calls=$(count_table primary model_call_executions)
replay_runs=$(count_table standby agent_runs)
replay_commands=$(count_table standby agent_run_commands)

failover_started_ns=$(python3 - <<'PY'
import time
print(time.time_ns())
PY
)

compose stop postgres-primary >/dev/null
promote_output=$(sql_standby "SELECT pg_promote(wait := true, wait_seconds := 60);" | tr -d '[:space:]')
if [[ "$promote_output" != "t" ]]; then
  echo "pg_promote did not report success: $promote_output" >&2
  exit 1
fi

writable=false
for _ in $(seq 1 60); do
  if sql_standby "SELECT pg_is_in_recovery();" | tr -d '[:space:]' | grep -qx f; then
    writable=true
    break
  fi
  sleep 0.5
done
if [[ "$writable" != true ]]; then
  echo "promoted standby never left recovery" >&2
  exit 1
fi

failover_ready_ns=$(python3 - <<'PY'
import time
print(time.time_ns())
PY
)

post_promote_runs=$(count_table standby agent_runs)
post_promote_commands=$(count_table standby agent_run_commands)

run_soak "jdbc:postgresql://127.0.0.1:${standby_port}/zyblw" "$standby_log"

final_runs=$(count_table standby agent_runs)
final_commands=$(count_table standby agent_run_commands)

path_log="$report_dir/durable-path.log"
run_path_probe() {
  local jdbc_url=$1
  local output=$2
  local common_env="\"ZYBLW_AGENT_JDBC_URL\" -> \"$jdbc_url\", \"ZYBLW_AGENT_DB_USER\" -> \"zyblw\", \"ZYBLW_AGENT_DB_PASSWORD\" -> \"local-ha-only\", \"ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE\" -> \"true\""
  (
    cd "$repo_root"
    sbt -batch \
      ";set examples / Compile / run / fork := true; set examples / Compile / run / envVars := Map($common_env); examples/runMain com.zyblw.agent.examples.FailoverDurablePathProbe"
  ) | tee "$output"
}
run_path_probe "jdbc:postgresql://127.0.0.1:${standby_port}/zyblw" "$path_log"
post_suspensions=$(count_table standby agent_suspensions)
post_artifacts=$(count_table standby agent_artifact_versions)

python3 - "$report_file" "$primary_log" "$standby_log" \
  "$primary_lsn" "$standby_replay_lsn" \
  "$pre_runs" "$pre_commands" "$pre_model_calls" \
  "$replay_runs" "$replay_commands" \
  "$post_promote_runs" "$post_promote_commands" \
  "$final_runs" "$final_commands" \
  "$failover_started_ns" "$failover_ready_ns" \
  "$primary_port" "$standby_port" <<'PY'
import datetime
import json
import pathlib
import sys

(
    report_path,
    primary_log,
    standby_log,
    primary_lsn,
    standby_replay_lsn,
    pre_runs,
    pre_commands,
    pre_model_calls,
    replay_runs,
    replay_commands,
    post_promote_runs,
    post_promote_commands,
    final_runs,
    final_commands,
    started_ns,
    ready_ns,
    primary_port,
    standby_port,
) = sys.argv[1:]

def soak_report(path):
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        marker = "SOAK_REPORT "
        if marker in line:
            return json.loads(line.split(marker, 1)[1])
    raise SystemExit(f"missing SOAK_REPORT in {path}")

def as_int(value):
    return int(value)

rto_millis = max(0, (int(ready_ns) - int(started_ns)) // 1_000_000)
lost_runs = max(0, as_int(pre_runs) - as_int(post_promote_runs))
lost_commands = max(0, as_int(pre_commands) - as_int(post_promote_commands))

report = {
    "schemaVersion": 1,
    "classification": "local_docker_ha_regression_only",
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "topology": {
        "primaryImage": "postgres:18-alpine",
        "standbyImage": "postgres:18-alpine",
        "replication": "physical_streaming",
        "primaryPort": int(primary_port),
        "standbyPort": int(standby_port),
    },
    "wal": {
        "primaryLsnBeforeFailover": primary_lsn,
        "standbyReplayLsnBeforeFailover": standby_replay_lsn,
    },
    "rowCounts": {
        "primaryBeforeStop": {
            "agent_runs": as_int(pre_runs),
            "agent_run_commands": as_int(pre_commands),
            "model_call_executions": as_int(pre_model_calls),
        },
        "standbyReplayBeforeStop": {
            "agent_runs": as_int(replay_runs),
            "agent_run_commands": as_int(replay_commands),
        },
        "standbyImmediatelyAfterPromote": {
            "agent_runs": as_int(post_promote_runs),
            "agent_run_commands": as_int(post_promote_commands),
        },
        "standbyAfterRecoverySoak": {
            "agent_runs": as_int(final_runs),
            "agent_run_commands": as_int(final_commands),
        },
    },
    "rpo": {
        "lostCommittedRuns": lost_runs,
        "lostCommittedCommands": lost_commands,
        "note": "Counts compare primary before stop with promoted standby before the recovery soak.",
    },
    "rto": {
        "promoteWaitMillis": rto_millis,
        "note": "Time from primary stop to pg_is_in_recovery() = false on the former standby.",
    },
    "primarySoak": soak_report(primary_log),
    "promotedSoak": soak_report(standby_log),
    "passed": as_int(final_runs) > as_int(post_promote_runs) and lost_runs == 0 and lost_commands == 0,
    "limitations": [
        "not a managed-cloud automatic failover",
        "not a Kubernetes node or availability-zone loss",
        "not a customer-facing RPO/RTO SLO",
        "application connection switching is performed by the drill, not by a patroni/operator",
    ],
}

if not report["passed"]:
    raise SystemExit("failover drill did not preserve committed rows or resume writes: " + json.dumps(report, ensure_ascii=False))

pathlib.Path(report_path).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"localFailoverDrill": "passed", "report": report_path}, separators=(",", ":")))
PY

python3 - "$report_file" "$path_log" "$post_suspensions" "$post_artifacts" <<'PY'
import json
import pathlib
import sys

report_path, path_log, suspensions, artifacts = sys.argv[1:]
report = json.loads(pathlib.Path(report_path).read_text(encoding="utf-8"))

def path_report(path):
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        marker = "FAILOVER_PATH_REPORT "
        if marker in line:
            return json.loads(line.split(marker, 1)[1])
    raise SystemExit(f"missing FAILOVER_PATH_REPORT in {path}")

probe = path_report(path_log)
probe["agent_suspensions"] = int(suspensions)
probe["agent_artifact_versions"] = int(artifacts)
report["durablePaths"] = probe
report["passed"] = bool(report.get("passed")) and all(
    probe.get(key) is True
    for key in ("artifactWritten", "artifactReadBack", "suspensionExpired", "runTimedOut")
) and int(artifacts) >= 1
if not report["passed"]:
    raise SystemExit("failover drill durable path probe failed: " + json.dumps(probe, ensure_ascii=False))
pathlib.Path(report_path).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"localFailoverPath": "passed"}, separators=(",", ":")))
PY
