#!/usr/bin/env bash
set -euo pipefail

# 只生成宿主证据草稿。没有测量值或负责人时，不得把 pending_host 改成 verified_host。

item="${1:-}"
out="${2:-host-evidence.json}"

case "$item" in
  soak|node-loss|failover|pgbouncer|rolling|backup|slo) ;;
  *)
    echo "用法: $0 <soak|node-loss|failover|pgbouncer|rolling|backup|slo> [output.json]" >&2
    exit 1
    ;;
esac

cat > "$out" <<EOF
{
  "item": "$item",
  "status": "pending_host",
  "commit": "$(git rev-parse HEAD 2>/dev/null || echo unknown)",
  "imageDigest": "${ZYBLW_AGENT_IMAGE_DIGEST:-}",
  "startedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "topology": "",
  "loadModel": "",
  "p50Millis": null,
  "p95Millis": null,
  "p99Millis": null,
  "backlog": null,
  "leaseLoss": null,
  "recoveryMillis": null,
  "rpo": "",
  "rto": "",
  "owner": "",
  "evidenceUri": "",
  "notes": "填写实测后再人工把 docs/evidence/wave-0-manifest.json 对应项改为 verified_host。"
}
EOF

echo "已写入 $out。脚本不会自动升级证据状态。"
