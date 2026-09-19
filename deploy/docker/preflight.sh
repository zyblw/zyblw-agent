#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "preflight 失败: $*" >&2
  exit 1
}

if (( $# > 1 )); then
  fail "用法: $0 [.env]"
fi

if (( $# == 1 )); then
  env_file="$1"
  [[ -f "$env_file" ]] || fail "环境文件不存在: $env_file"
  # 只加载运维人员自己维护的受信文件；不要对下载或用户上传的内容执行 source。
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
fi

require_env() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" ]]; then
    fail "缺少环境变量 $name"
  fi
  case "$value" in
    *replace-me*|*replace-with*|*change-me*) fail "$name 仍是示例占位符" ;;
  esac
}

require_env ZYBLW_AGENT_JDBC_URL
require_env ZYBLW_AGENT_DB_USER
require_env ZYBLW_AGENT_DB_PASSWORD

if [[ -n "${ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON:-}" ]]; then
  referenced_keys="$(printf '%s' "$ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON" |
    grep -oE '"apiKeyEnv"[[:space:]]*:[[:space:]]*"[A-Z][A-Z0-9_]*"' |
    sed -E 's/.*"([A-Z][A-Z0-9_]*)"$/\1/' | sort -u || true)"
  [[ -n "$referenced_keys" ]] || fail "多端点 JSON 至少需要一个合法 apiKeyEnv"
  while IFS= read -r key; do
    case "$key" in
      RELAY_API_KEY|DEEPSEEK_API_KEY|GLM_API_KEY|QWEN_API_KEY|MOONSHOT_API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|GEMINI_API_KEY) ;;
      *) fail "多端点 apiKeyEnv=$key 未由参考 Compose 注入；请先显式加入 Compose environment" ;;
    esac
    require_env "$key"
  done <<< "$referenced_keys"
  echo "检测到 ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON；所有 apiKeyEnv 均已注入，完整结构与 URL 将由应用启动时严格校验。"
else
  require_env OPENAI_BASE_URL
  require_env OPENAI_API_KEY
  require_env OPENAI_MODEL
fi

bind_address="${ZYBLW_AGENT_BIND_ADDRESS:-127.0.0.1}"
[[ "$bind_address" != "0.0.0.0" && "$bind_address" != "::" ]] ||
  fail "trusted-headers 入口禁止绑定所有网卡；请使用 127.0.0.1 或受控私网 IP"

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
  echo "未设置 ZYBLW_AGENT_PSQL_URL，跳过 psql 连接探针；请在 migrate 前由 DBA 确认 PostgreSQL 18 与角色权限。"
fi

echo "preflight 通过：live 配置完整，未回退内存。下一步是 migrate，然后启动或滚动发布 serve。"
