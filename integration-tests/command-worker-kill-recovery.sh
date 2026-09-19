#!/usr/bin/env bash
set -euo pipefail

restart_postgres=false
case ${1:-} in
  "") ;;
  --restart-postgres) restart_postgres=true ;;
  *)
    echo "usage: $0 [--restart-postgres]" >&2
    exit 2
    ;;
esac
if (( $# > 1 )); then
  echo "usage: $0 [--restart-postgres]" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
probe_class=com.zyblw.agent.examples.CommandWorkerProcessKillProbe
container_name="zyblw-agent-command-kill-$$"
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/zyblw-agent-command-kill.XXXXXX")
state_file="$work_dir/state"
old_report="$work_dir/old-report"
recovery_report="$work_dir/recovery-report"
old_log="$work_dir/old-worker.log"
old_sbt_pid=
old_worker_pid=

cleanup() {
  if [[ -n "$old_worker_pid" ]] && kill -0 "$old_worker_pid" 2>/dev/null; then
    kill -KILL "$old_worker_pid" 2>/dev/null || true
  fi
  if [[ -n "$old_sbt_pid" ]] && kill -0 "$old_sbt_pid" 2>/dev/null; then
    kill "$old_sbt_pid" 2>/dev/null || true
  fi
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT INT TERM

for command in docker sbt awk; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command not found: $command" >&2
    exit 1
  fi
done

wait_for_postgres() {
  local ready=false
  for _ in $(seq 1 120); do
    if docker exec "$container_name" pg_isready --username zyblw --dbname zyblw >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 0.25
  done
  if [[ "$ready" != true ]]; then
    echo "PostgreSQL did not become ready" >&2
    exit 1
  fi
}

docker run --detach --name "$container_name" \
  --publish 127.0.0.1::5432 \
  --env POSTGRES_USER=zyblw \
  --env POSTGRES_PASSWORD=zyblw-process-kill-test \
  --env POSTGRES_DB=zyblw \
  postgres:18-alpine >/dev/null

wait_for_postgres

host_port=$(docker port "$container_name" 5432/tcp | awk -F: 'NR == 1 { print $NF }')
if [[ ! "$host_port" =~ ^[0-9]+$ ]]; then
  echo "could not resolve PostgreSQL host port: $host_port" >&2
  exit 1
fi

jdbc_url="jdbc:postgresql://127.0.0.1:$host_port/zyblw"
db_user=zyblw
db_password=zyblw-process-kill-test

run_probe() {
  local mode=$1
  (
    cd "$repo_root"
    sbt -batch \
      ";set examples / Compile / run / fork := true; set examples / Compile / run / envVars := Map(\"ZYBLW_AGENT_JDBC_URL\" -> \"$jdbc_url\", \"ZYBLW_AGENT_DB_USER\" -> \"$db_user\", \"ZYBLW_AGENT_DB_PASSWORD\" -> \"$db_password\"); examples/runMain $probe_class $mode $state_file $old_report $recovery_report"
  )
}

echo "[1/4] seed PostgreSQL run and command"
run_probe seed

echo "[2/4] start old Worker JVM and wait until it owns generation 1"
run_probe old >"$old_log" 2>&1 &
old_sbt_pid=$!

reported=false
for _ in $(seq 1 240); do
  if [[ -s "$old_report" ]] && awk -F= '$1 == "generation" && $2 == "1" { found = 1 } END { exit !found }' "$old_report"; then
    reported=true
    break
  fi
  if ! kill -0 "$old_sbt_pid" 2>/dev/null; then
    echo "old Worker launcher exited before reporting its lease" >&2
    sed -n '1,240p' "$old_log" >&2
    exit 1
  fi
  sleep 0.25
done
if [[ "$reported" != true ]]; then
  echo "old Worker did not report its lease" >&2
  sed -n '1,240p' "$old_log" >&2
  exit 1
fi

old_worker_pid=$(awk -F= '$1 == "pid" { print $2 }' "$old_report")
if [[ ! "$old_worker_pid" =~ ^[1-9][0-9]*$ ]] || ! kill -0 "$old_worker_pid" 2>/dev/null; then
  echo "reported old Worker PID is not alive: $old_worker_pid" >&2
  exit 1
fi

echo "      SIGKILL old Worker JVM pid=$old_worker_pid"
kill -KILL "$old_worker_pid"
old_worker_pid=
if wait "$old_sbt_pid"; then
  echo "old Worker launcher unexpectedly succeeded after SIGKILL" >&2
  exit 1
fi
old_sbt_pid=

if [[ "$restart_postgres" == true ]]; then
  echo "      restart the PostgreSQL container and rediscover its host endpoint"
  docker restart "$container_name" >/dev/null
  wait_for_postgres
  restarted_host_port=$(docker port "$container_name" 5432/tcp | awk -F: 'NR == 1 { print $NF }')
  if [[ ! "$restarted_host_port" =~ ^[0-9]+$ ]]; then
    echo "could not resolve restarted PostgreSQL host port: $restarted_host_port" >&2
    exit 1
  fi
  host_port=$restarted_host_port
  jdbc_url="jdbc:postgresql://127.0.0.1:$host_port/zyblw"
fi

echo "[3/4] wait for database lease expiry and recover with a new Worker JVM"
run_probe recover

echo "[4/4] verify generation fencing, attempt history and queue convergence"
run_probe verify

echo "PASS: an OS-killed Worker was reclaimed by a different JVM through the production PostgreSQL lease protocol"
