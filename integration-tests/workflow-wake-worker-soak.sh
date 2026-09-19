#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
probe_class=com.zyblw.agent.examples.WorkflowWakeWorkerSoakProbe
container_name="zyblw-agent-workflow-soak-$$"

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

for command in docker sbt awk; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command not found: $command" >&2
    exit 1
  fi
done

docker run --detach --name "$container_name" \
  --publish 127.0.0.1::5432 \
  --env POSTGRES_USER=zyblw \
  --env POSTGRES_PASSWORD=zyblw-workflow-soak-test \
  --env POSTGRES_DB=zyblw \
  postgres:18-alpine >/dev/null

ready=false
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

host_port=$(docker port "$container_name" 5432/tcp | awk -F: 'NR == 1 { print $NF }')
if [[ ! "$host_port" =~ ^[0-9]+$ ]]; then
  echo "could not resolve PostgreSQL host port: $host_port" >&2
  exit 1
fi

jdbc_url="jdbc:postgresql://127.0.0.1:$host_port/zyblw"

(
  cd "$repo_root"
  sbt -batch \
    ";set examples / Compile / run / fork := true; set examples / Compile / run / envVars := Map(\"ZYBLW_AGENT_JDBC_URL\" -> \"$jdbc_url\", \"ZYBLW_AGENT_DB_USER\" -> \"zyblw\", \"ZYBLW_AGENT_DB_PASSWORD\" -> \"zyblw-workflow-soak-test\", \"ZYBLW_AGENT_WORKFLOW_SOAK_CONFIRM_DISPOSABLE\" -> \"true\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MIN_ROUNDS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_MIN_ROUNDS:-4}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_RUNS_PER_ROUND\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_RUNS_PER_ROUND:-18}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_WORKERS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_WORKERS:-3}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MIN_DURATION_SECONDS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_MIN_DURATION_SECONDS:-5}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_ROUND_PAUSE_MILLIS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_ROUND_PAUSE_MILLIS:-100}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_NODE_LATENCY_MILLIS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_NODE_LATENCY_MILLIS:-25}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_SAMPLE_MILLIS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_SAMPLE_MILLIS:-20}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MAX_CLAIM_P95_MILLIS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_MAX_CLAIM_P95_MILLIS:-5000}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_MAX_TERMINAL_P95_MILLIS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_MAX_TERMINAL_P95_MILLIS:-10000}\", \"ZYBLW_AGENT_WORKFLOW_SOAK_TIMEOUT_SECONDS\" -> \"${ZYBLW_AGENT_WORKFLOW_SOAK_TIMEOUT_SECONDS:-120}\"); examples/runMain $probe_class"
)
