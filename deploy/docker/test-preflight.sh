#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
preflight="$root/deploy/docker/preflight.sh"

run_preflight() {
  env -i \
    PATH="$PATH" \
    ZYBLW_AGENT_RUNTIME_MODE=live \
    ZYBLW_AGENT_AUTH_MODE=trusted-headers \
    ZYBLW_AGENT_BIND_ADDRESS=127.0.0.1 \
    ZYBLW_AGENT_JDBC_URL=jdbc:postgresql://postgres.internal:5432/zyblw_agent \
    ZYBLW_AGENT_DB_USER=zyblw_agent \
    ZYBLW_AGENT_DB_PASSWORD=test-only-password \
    "$@" \
    "$preflight"
}

expect_failure() {
  local expected="$1"
  shift
  local output
  if output="$(run_preflight "$@" 2>&1)"; then
    echo "预期 preflight 失败，但实际成功：$expected" >&2
    exit 1
  fi
  [[ "$output" == *"$expected"* ]] || {
    echo "preflight 失败原因不匹配，预期包含：$expected" >&2
    printf '%s\n' "$output" >&2
    exit 1
  }
}

run_preflight \
  OPENAI_BASE_URL=https://gateway.example/v1 \
  OPENAI_API_KEY=test-openai-key \
  OPENAI_MODEL=test-model >/dev/null

run_preflight \
  'ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON={"defaultProvider":"deepseek","endpoints":[{"providerId":"deepseek","baseUrl":"https://api.deepseek.com/v1","apiKeyEnv":"DEEPSEEK_API_KEY","defaultModel":"deepseek-chat","models":[{"name":"deepseek-chat","priceInputPerMillion":"1","priceOutputPerMillion":"2"}]}]}' \
  DEEPSEEK_API_KEY=test-deepseek-key >/dev/null

expect_failure \
  "缺少环境变量 DEEPSEEK_API_KEY" \
  'ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON={"defaultProvider":"deepseek","endpoints":[{"providerId":"deepseek","baseUrl":"https://api.deepseek.com/v1","apiKeyEnv":"DEEPSEEK_API_KEY","defaultModel":"deepseek-chat","models":[{"name":"deepseek-chat","priceInputPerMillion":"1","priceOutputPerMillion":"2"}]}]}'

expect_failure \
  "未由参考 Compose 注入" \
  'ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON={"defaultProvider":"custom","endpoints":[{"providerId":"custom","baseUrl":"https://gateway.example/v1","apiKeyEnv":"CUSTOM_API_KEY","defaultModel":"custom-model","models":[{"name":"custom-model","priceInputPerMillion":"1","priceOutputPerMillion":"2"}]}]}' \
  CUSTOM_API_KEY=test-custom-key

expect_failure \
  "禁止绑定所有网卡" \
  ZYBLW_AGENT_BIND_ADDRESS=0.0.0.0 \
  OPENAI_BASE_URL=https://gateway.example/v1 \
  OPENAI_API_KEY=test-openai-key \
  OPENAI_MODEL=test-model

echo "Docker preflight 契约测试通过。"
