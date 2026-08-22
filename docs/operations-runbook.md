# 运维 Runbook

> 状态：当前值班手册（Wave 0 证据合同）
> 最后核验：2026-08-22
> 事实来源：`queueSnapshot` / `wakeQueueSnapshot` 源码、管理控制台 `QueueOps`、`integration-tests/*soak*.sh`、`integration-tests/*kill-recovery*.sh`、[生产接入基线](production-readiness.md)

本文告诉值班人员**看到什么数字、先查什么、不要做什么**。它不发明尚未测得的生产 SLO。仓库回归里的 P95 只是本机/CI 基线，不能直接当成对客承诺。Docker/VM 安装、升级和恢复见 [Docker/VM 手册](operations-docker-vm.md)。

## 信号从哪来

| 信号 | 来源 | 含什么 | 不含什么 |
|---|---|---|---|
| 命令队列 | `AgentApplication.queueSnapshot`；控制台 [QueueOps](../modules/agent-dashboard/src/components/QueueOps.tsx) 每 5 秒刷新 | Queued、可领取 Run、在租约、过期租约、死信、最长等待 | 命令正文、tenant/user、lease token、approval 决定 |
| Workflow 唤醒 | `WorkflowExecutionStore.wakeQueueSnapshot(workflowId, definitionVersion)` | Pending/Due wait、可领取/在租约/过期 wakeup、最长等待 | Run/Session、signal payload、owner、fencing token |
| 进程死亡 | `integration-tests/command-worker-kill-recovery.sh`、`workflow-wake-worker-kill-recovery.sh` | generation 提升、同一 PostgreSQL restart 后接管 | 云厂商主备切换、AZ 故障 |
| 有界 soak | `durable-worker-soak.sh`、`workflow-wake-worker-soak.sh` | 多 Worker 归零、无异常重领 | 数小时业务 soak、容量曲线 |
| 本地类 staging | `integration-tests/local-staging-evidence.sh` | PostgreSQL 16 + transaction-mode PgBouncer 小池压力、两类 soak、备份恢复和机器可读报告 | 主备/AZ/Kubernetes 故障、业务 SLO |
| 本地主备提升 | `integration-tests/failover-drill.sh` | 流复制、`pg_promote`、提升后继续 soak、丢失已提交行数与提升等待毫秒 | 托管 HA、自动切换、对客 RPO/RTO |

快照是**采样**，不是审计。审计走 `RunStore.events` / 命令记录；指标走 OTLP。不要为了看 backlog 去查任意 SQL。

## 告警怎么拆

三者原因不同，禁止合成一条「队列不健康」：

1. **最长等待持续增长**（`oldestDispatchableAgeMillis`）→ 扩容、下游 Provider/工具变慢、或 claim 周期过长。先看 `dispatchableRuns` 是否堆积、Worker replica 是否少于预期。
2. **过期租约非零**（`expiredLeases` / `expiredWakeLeases`）→ Worker 崩溃、GC/IO stall、或数据库暂停超过 lease。先看 Pod 重启与 lease 配置，再看 PostgreSQL。
3. **死信新增**（`deadLetterCommands`）→ 人工审查。控制台死信清单不含命令正文；重排前确认失败分类不是权限/审批/组合漂移。

Workflow 另两条：

- `dueWaits` 持续非零：deadline 决议器滞后，先查 wake worker 是否在跑。
- `dispatchableWakeups` 增长而 `leasedWakeups` 为 0：有活可领但没人领，先查 wake worker 与 workflow/version 是否匹配冻结定义。

## 处置步骤

### Worker 消失 / Pod SIGKILL

1. 确认新副本的 `WorkerId` 每次启动唯一，没有多个 replica 共用一个固定 ID。
2. 等原 lease 过期（不要手工改 `agent_run_dispatch` 行）。
3. 新 Worker 必须以 **generation+1** 领取同一命令；旧 generation 的 complete/heartbeat 必须被 fencing 拒绝。
4. 队列快照应在一个 lease 周期内收敛：`expiredLeases → 0`，`dispatchableRuns` 下降。
5. 仓库可重复证据：`integration-tests/command-worker-kill-recovery.sh --restart-postgres`（命令）与 `workflow-wake-worker-kill-recovery.sh --restart-postgres`（唤醒）。

### 数据库短暂停顿（非主备切换）

已有契约：Worker 消失 **同时** PostgreSQL pause/unpause，过期后下一 generation 重领。

### 数据库主备切换：库与宿主的职责

`zyblw-agent` 是业务引入的库，不是数据库运营商。合理边界：

| 放在库里 | 放在业务宿主运维 |
|---|---|
| 连接中断映射为 typed `PersistenceFailure`，不静默回退内存 Store | 选择托管 HA / Patroni / 云厂商故障转移 |
| Worker fencing：旧 generation 的 complete/heartbeat 必须被拒绝 | 备份、PITR、跨 AZ 副本、值班与 paging |
| 可重复演练脚本与机器可读 RPO/RTO **回归**报告 | 用真实流量校准对客 SLO 并指定 on-call |
| 文档化“切换后必须换连接串或由连接池重新解析写节点” | 连接池/代理的写节点切换实现 |

本地可重复证据：

```bash
./integration-tests/failover-drill.sh
```

它启动 `compose-ha.yml` 的流复制对，在主库完成有界 soak 后停止主库、`pg_promote` 备库，再对提升后的写节点跑第二次 soak。报告写到 `target/local-ha/failover-evidence.json`，分类为 `local_docker_ha_regression_only`。丢失已提交行必须为 0，提升后必须能继续写入。这证明库在主库消失后仍遵守同一 JDBC 契约，**不能**改名为云厂商自动故障转移或生产 RPO/RTO。

### 审批卡住

1. 控制台按 `approvalId` 提交；主体漂移后 ID 会变，旧页面决定会被拒绝——这是预期，重新打开当前请求。
2. `WaitingForApproval` 不是死信。不要用 Retry 代替 Approve/Reject。
3. 组合指纹 `Incompatible` / `RequiresRevalidation` 必须重新部署对齐或显式放弃该 Run，禁止改 JSON 放行。

### 死信重排

控制台「死信命令」只展示类型、分类、尝试次数。重排会递增人工重试计数。权限拒绝、审批拒绝、组合不兼容不要重排；未知工具结果不确定（`Unknown`）走审批重放，不走死信重试。

## 仓库回归阈值（不是生产 SLO）

`durable-worker-soak.sh` / `workflow-wake-worker-soak.sh` 使用秒级有界负载，门禁 claim/terminal P95 远宽于本机实测。发布候选必须重新跑这些脚本；把某次笔记本上的 760ms 写成对客 SLO 是合同错误。

Wave 0 仍待宿主环境补齐：数小时业务 soak、节点丢失、数据库主备切换、按实测校准的 backlog/lease-lost/恢复时延 SLO、以及告警负责人（框架不能替业务指定 on-call）。

开发阶段可先运行：

```bash
./integration-tests/local-staging-evidence.sh --quick
```

它生成 `target/local-staging/evidence.json`，并明确标记 `local_docker_regression_only`。`--full` 默认要求至少一小时并追加
两条 SIGKILL + PostgreSQL restart 恢复脚本；可通过 `ZYBLW_AGENT_LOCAL_STAGING_*` 调整负载，但无论运行多久都不能把
单机 Docker 报告改名为主备切换、节点丢失或生产 SLO 证据。Compose 端口默认仅绑定 loopback，可通过
`ZYBLW_LOCAL_POSTGRES_PORT` / `ZYBLW_LOCAL_PGBOUNCER_PORT` 修改，测试密码不得复用到任何共享环境。

## 不要做的事

- 不要为了清空队列而调大 `worker.parallelism` 越过 JDBC 池和 Provider 配额。
- 不要在生产入口把 PostgreSQL 失败回退成内存 Store。
- 不要把 snapshot、SSE Hub 或 Langfuse 当成恢复真相。
- 不要把 `expiredLeases` 当吞吐指标；它只说明有人没续上租约。
