#!/usr/bin/env bash

# 文件后端评测闭环 smoke：公开 fixture 双审门禁 + CLI 文件趋势。
# 不调用真实模型，不写入宿主生产趋势库。

set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

sbt -batch \
  'evals/testOnly com.zyblw.agent.evals.PublicEvalClosedLoopSpec com.zyblw.agent.evals.RagEvaluationSpec com.zyblw.agent.evals.BookCorpusRagEvalSpec'
sbt -batch \
  'testkit/testOnly com.zyblw.agent.evals.PublicEvalClosedLoopRuntimeSpec'
sbt -batch \
  'evalCli/testOnly com.zyblw.agent.evals.cli.EvalReleaseGateCliSpec'

printf '%s\n' '{"evalClosedLoop":"passed","store":"file","hostCalibration":"pending_host"}'
