# Wave 0 生产证据清单

机器可读状态以 [`wave-0-manifest.json`](wave-0-manifest.json) 为准。

当前业务可用结论：Docker + 自管 PostgreSQL 的单库路径已经按仓库门禁验收。下面 7 项没有真实上线环境，状态为
`deferred`，不阻止把框架引进业务应用。

## 仓库门禁（支撑业务接入）

- `scalafmtCheckAll; scalafmtSbtCheck; testFull`
- `scripts/verify-business-ready.sh`
- PostgreSQL 16 Store conformance（`RUN_POSTGRES_INTEGRATION=1` 时）
- command/workflow 有界 soak 与 kill-recovery（CI 机制证据，不是宿主实测）
- `publishM2` + 独立 Maven consumer
- `.github/scripts/verify-local-evidence.sh --manifest`

## 已延期，等独立上线环境

- 数小时真实 soak
- VM/进程节点丢失
- PostgreSQL 主备切换
- PgBouncer / 连接池饱和
- 滚动发布
- 备份恢复 RPO/RTO
- 生产 SLO / 告警 owner

在有测量值和负责人之前，这些条目不得改成 `verified_host`。
