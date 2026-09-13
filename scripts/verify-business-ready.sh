#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

echo "业务接入门禁：格式、确定性全量测试、问答契约与证据清单结构。"
echo "不含长时 soak、主备、PgBouncer、滚动发布或备份 RPO/RTO。"

.github/scripts/verify-local-evidence.sh --manifest
sbt -batch 'evals/testOnly com.zyblw.agent.evals.BookCorpusRagEvalSpec; examples/testOnly com.zyblw.agent.examples.knowledge.KnowledgeQaHostContractSpec; http/testOnly com.zyblw.agent.http.KnowledgeHttpApiSpec'
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'

if [[ "${RUN_POSTGRES_INTEGRATION:-}" == "1" ]]; then
  echo "检测到 RUN_POSTGRES_INTEGRATION=1，追加 PostgreSQL Adapter 全量契约。"
  sbt -batch postgres/testFull
else
  echo "跳过 postgres/testFull。需要时设置 RUN_POSTGRES_INTEGRATION=1。"
fi

echo "业务接入门禁通过。可将 0.9.0-local 发布到 Maven Local，或用 KnowledgeQaHost / deploy/docker/compose.business.yml 启动。"
