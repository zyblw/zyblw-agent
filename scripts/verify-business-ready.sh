#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

echo "业务接入门禁：格式、确定性全量测试、问答契约、证据清单结构与 PostgreSQL 18 全量契约。"
echo "不含长时 soak、主备、PgBouncer、滚动发布或备份 RPO/RTO。"

.github/scripts/verify-local-evidence.sh --manifest
sbt -batch 'evals/testOnly com.zyblw.agent.evals.BookCorpusRagEvalSpec; examples/testOnly com.zyblw.agent.examples.knowledge.KnowledgeQaHostContractSpec; http/testOnly com.zyblw.agent.http.KnowledgeHttpApiSpec'
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'

if [[ "${SKIP_POSTGRES_INTEGRATION:-}" == "1" ]]; then
  echo "SKIP_POSTGRES_INTEGRATION=1：仅完成开发预检，不构成业务接入门禁通过。" >&2
  echo "需要 Docker/PostgreSQL 18 后重新运行本脚本。" >&2
  exit 2
else
  echo "运行 PostgreSQL 18 Adapter 全量契约；耐久业务接入不得跳过真实数据库验证。"
  RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
fi

echo "业务接入门禁通过。可将 0.9.0-local 发布到 Maven Local，或用 KnowledgeQaHost / deploy/docker/compose.business.yml 启动。"
