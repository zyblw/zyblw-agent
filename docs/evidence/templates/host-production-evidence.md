# Host 生产证据模板

> 复制本文件到宿主的受控证据库。不要把密钥、Prompt、业务正文、用户标识或未脱敏日志提交到框架仓库。

## 变更身份

- zyblw-agent 精确版本/commit：
- 宿主版本/commit：
- 环境与区域：
- 执行日期：
- 执行人：
- 运维审批人：
- 业务审批人：

## 负载与容量

- command/workflow 流量模型：
- 并发与持续时间：
- 数据库/连接池规格：
- backlog P50/P95/P99：
- claim latency P50/P95/P99：
- completion latency P50/P95/P99：
- lease-loss 数量与比例：
- token/cost：
- 原始低敏报告链接：

## 故障演练

对每项填写开始时间、注入方式、检测时间、止损动作、恢复时间、数据一致性检查和低敏证据链接。

- Pod/VM 节点丢失：
- PostgreSQL 主备切换：
- PgBouncer/连接池饱和：
- 滚动发布：
- 备份恢复：

## SLO 与恢复目标

- 可用性 SLO 与测量窗口：
- backlog/claim/恢复 P95/P99：
- RPO：
- RTO：
- 告警负责人：
- 止损动作：
- 回滚条件：

## 结论

- [ ] 所有失败均有 typed 分类和可执行 runbook。
- [ ] 没有重复非幂等副作用或跨租户读取。
- [ ] 备份恢复数据与 ledger/state/event 不变量一致。
- [ ] 告警已实际触发并通知到负责人。
- [ ] 结论仅适用于上述宿主、版本、容量和环境。

最终判定：`pass` / `conditional` / `fail`

未关闭风险：
