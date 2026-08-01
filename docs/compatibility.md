# 兼容性契约与版本边界

> 状态：0.3.0 开发线当前契约
> 最后核验：2026-08-01
> 事实来源：`build.sbt`、公共源码、HTTP Schema、数据库 baseline、测试与发布工作流

## 当前结论

`main` 正在形成尚未发布的 `0.3.0`，允许破坏 `0.2.x` 的 Scala API、二进制、持久化 outcome 和数据库历史。原因不是
“版本早期就可以随意变化”，而是仓库尚无生产消费者，用户明确选择用一次清晰重建换取正确的 durable wait 与 RAG 数据
模型。已经发布的 `0.1.0`、`0.2.0`、`0.2.1` Maven 制品、Git 标签和 migration 保持客观不可变。

本次窗口结束于 `0.3.0` 正式发布：发布后，`0.3.x` patch 必须重新遵守 minor 内兼容承诺；下一次有意破坏必须提升 minor。

## 0.3 开发线的当前边界

| 表面 | 当前 main | 0.3.0 发布后的 patch 承诺 |
|---|---|---|
| 公共 Scala API | 允许删除、重命名和重构；必须同步所有仓库内消费者 | 不删除或改变公开签名语义 |
| Maven 坐标/POM | 继续保持 11 个有真实依赖边界的 artifact | 坐标保持，依赖只做兼容升级 |
| HTTP | `/api/v1` 暂不因内部重构而变化；若需要破坏则在 0.3 发布前完成或新增 `/api/v2` | 已发布路径与 wire 字段兼容 |
| State/outcome JSON | Workflow outcome schema 已切到 v2；不读取 0.2 Prepared outcome | 新字段必须有安全默认读取语义 |
| Workflow | 允许新的 wait/wakeup/commit 契约；旧活跃 Run 不恢复 | identity、fencing、唯一唤醒和原子提交不得弱化 |
| PostgreSQL | 只有一个 0.3 fresh-install V001；不接管旧 Flyway history | baseline 冻结，后续只追加 migration |
| RAG 派生数据 | 向量、chunk、FTS 与 lineage 可全部重建 | source identity、ACL、撤回与 lineage 语义稳定 |

## 数据库重建契约

默认 location 现在只有 `V001__zyblw_agent_0_3_baseline.sql`。它完整创建 Runtime、命令、Memory、Embedding 治理、Eval、
Workflow execution 与 durable wait/signal 表。采用当前源码必须：

- 使用空 schema 或新数据库；
- 不对旧 history 执行 `repair`、改 checksum 或 `baselineOnMigrate` 来假装兼容；
- 不恢复 0.2 Workflow checkpoint/ledger；
- 从业务源重新构建 RAG 派生索引；
- 在清理旧数据库前独立完成备份和保留审批。

详细步骤见[进入 0.3.0 开发线](upgrading-to-0.3.0.md)。

## Durable Workflow 不变量

`WorkflowExecutionStore.commit` 是一个事务语义：Prepared execution、checkpoint、旧 wait 消费和新 wait 注册必须全部成功
或全部回滚。外部 signal 使用 `(waitKey, signalId)` 去重；相同 ID 不同 payload 冲突。PostgreSQL 使用数据库时钟判断
deadline，达到 deadline 后 timeout 胜出。恢复只消费由 owner/token/generation/expiry 完整 fence 领取的 Signaled/TimedOut；
Pending 不允许执行节点，已决议 wait 也不能通过普通 `resume` 绕过 claim。wait 行本身是 durable wake command，消费与下一
checkpoint 原子提交。

这些保证不自动让节点内部第三方副作用 exactly-once；支付、发信等写操作仍需业务幂等键或 outbox/inbox。

## 发布门禁

`0.3.0` 发布前必须同时通过：完整 `testFull`、真实 PostgreSQL 16、fresh baseline、`publishM2`、独立 Maven consumer、
HTTP/OpenAPI 契约、Workflow wait 故障/竞态测试和 RAG 数据重建测试。发布标签必须来自远端 `main`，且 CHANGELOG 与
`docs/upgrading-to-0.3.0.md` 一致。
