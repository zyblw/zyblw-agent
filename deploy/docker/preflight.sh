#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "preflight 失败: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    fail "缺少环境变量 $name"
  fi
}

require_env ZYBLW_AGENT_JDBC_URL
require_env ZYBLW_AGENT_DB_USER
require_env ZYBLW_AGENT_DB_PASSWORD
require_env OPENAI_BASE_URL
require_env OPENAI_API_KEY

if [[ "${ZYBLW_AGENT_RUNTIME_MODE:-live}" != "live" ]]; then
  fail "preflight 只接受 live 模式，当前为 ${ZYBLW_AGENT_RUNTIME_MODE}"
fi

if [[ "${ZYBLW_AGENT_AUTH_MODE:-trusted-headers}" != "trusted-headers" ]]; then
  fail "live 部署必须使用 trusted-headers"
fi

case "${ZYBLW_AGENT_JDBC_URL}" in
  jdbc:postgresql://*) ;;
  *) fail "ZYBLW_AGENT_JDBC_URL 必须是 jdbc:postgresql://..." ;;
esac

if [[ -n "${ZYBLW_AGENT_PSQL_URL:-}" ]]; then
  command -v psql >/dev/null || fail "提供了 ZYBLW_AGENT_PSQL_URL 时需要 psql"
  PGPASSWORD="${ZYBLW_AGENT_DB_PASSWORD}" psql "${ZYBLW_AGENT_PSQL_URL}" \
    --username "$ZYBLW_AGENT_DB_USER" \
    --command "SELECT current_setting('server_version_num');" >/dev/null \
    || fail "无法连接 PostgreSQL"
  echo "数据库连接探针已通过。"
else
  echo "未设置 ZYBLW_AGENT_PSQL_URL，跳过 psql 连接探针；请在 migrate 前由 DBA 确认 PostgreSQL 16+ 与角色权限。"
fi

echo "preflight 通过：live 配置完整，未回退内存。下一步是 migrate，然后滚动启动 serve。"
