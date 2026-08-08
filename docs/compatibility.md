# 兼容性契约与版本边界

> 状态：0.5.0 发布候选契约
> 最后核验：2026-08-08
> 事实来源：`build.sbt`、公共源码、HTTP Schema、数据库 baseline、测试与发布工作流

## 当前结论

`0.5.0` 是一次**加法型** minor：它在既有主线之外增加可选的管理面（`/api/v1/admin/**`）、运行时配置覆盖与模型治理，
不改变 Agent Runtime、耐久命令、业务侧 HTTP v1、Workflow outcome v2 和 0.4 知识 schema。宿主不装配任何管理能力时不会
挂载新路由，但核心 schema 的 `V002` migration 与两处 Layer 签名变化仍然适用。

`0.4.0` 建立的 RAG/document-loading 契约在 0.5 保持不变；0.4 使用者可以留在 0.4.x。已经发布的 Maven 制品、Git 标签和
migration 永远不可变。`0.5.0` 发布后，`0.5.x` patch 保护本页定义的公共表面；下一次需要删除公共 API、改变 wire/state
语义或重建数据库基线时，必须提升 minor，不能伪装成 patch。

## 0.5.0 发布边界

| 表面 | 0.5.0 基线 | 后续 0.5.x patch 承诺 |
|---|---|---|
| Agent/Core Scala API | 延续 0.3.0 Runtime、Tool、权限、命令和应用装配语义；新增 `ToolPolicySource`、`ModelPolicySource`、`RetrievalPolicySource` 三个有默认实现的 resolver | 不删除或改变公开签名语义 |
| Runtime 装配 | `AgentRuntimeLive` 需要 `ModelPolicySource`，`RuntimeSettingsService.layer` 需要 `ModelCatalog`；`AgentApplication` 组合层已内置 | 不再要求新的必填环境依赖 |
| RAG Scala API | 延续 0.4 的结构化 provenance/lineage | 不删除字段或改变身份、ACL、谱系语义 |
| Maven 坐标/POM | 保持 11 个有真实依赖边界的 artifact；`agent-dashboard` 是浏览器应用，不发布到 Maven | 坐标保持，依赖只做兼容升级 |
| 业务 HTTP | 继续使用 `/api/v1`，`AgentHttpContract` 与 OpenAPI 不变 | 已发布路径与 wire 字段兼容 |
| 管理 HTTP | `/api/v1/admin/**` 明确标记 **Beta**，不进入 `AgentHttpContract` 的稳定 OpenAPI 承诺 | 视图形状跟随控制台演进，可在 minor 内调整 |
| State/outcome JSON | 继续使用 0.3 Workflow outcome v2 | 新字段必须有安全默认读取语义 |
| Workflow | 延续 durable wait/signal/wakeup、fencing 与原子提交 | identity、唯一唤醒和原子提交不得弱化 |
| 核心 PostgreSQL | 冻结的 0.3 V001 之上追加 `V002__zyblw_agent_admin_surface.sql`（生成列 + 运行时覆盖表 + 摄入任务表） | 已发布 V001/V002 不修改；只追加 migration |
| 0.4 知识 PostgreSQL | 独立 `zyblw_agent_knowledge` schema、独立 history、单一 fresh V001、固定 1536 维 | V001 发布后冻结；patch 只追加 migration |
| RAG 派生数据 | 文档 manifest、staging、active read model、FTS/vector 与结构谱系可由原始文档重建 | source identity、ACL、撤回、复合 chunk 身份与 lineage 语义稳定 |

管理面为什么不进入稳定 OpenAPI 承诺：它服务的是运维界面要显示什么，而不是业务集成契约。把仍在按控制台需求演进的
聚合视图冻结成公共 wire 契约，只会让两边同时被锁死。需要长期稳定集成的能力应通过业务侧 `/api/v1` 暴露。

## 管理面授权边界

管理接口不复用业务侧"归属即可读"的规则，因为运维看到的是跨租户聚合而不是单个 Run 所有者的视图：

| scope | 覆盖范围 | 蕴含关系 |
|---|---|---|
| `agent:admin:read` | 只读聚合：Run 目录、队列、有效配置、模型目录、评测趋势 | — |
| `agent:admin:write` | 改变部署行为：工具白名单、审批策略、模型切换、死信重排 | 蕴含 read |
| `agent:admin:debug` | 产生真实 Provider 费用：检索沙盒、文档摄入、模型探活 | **不**被 write 蕴含 |

管理面不接受、不返回、也不存储任何 Provider API Key；凭据只以 `env:VARIABLE` 形式的引用与"是否存在"呈现。

## 数据库采用契约

核心控制面继续由 `V001__zyblw_agent_0_3_baseline.sql` 管理，0.5 在其之上**追加** `V002__zyblw_agent_admin_surface.sql`，
不重建基线。`V002` 把 tenant、user 和"待审批"提升为 `agent_runs` 上的生成列并建立配套索引，另外创建运行时覆盖表和摄入任务表。
生成列不触碰任何写路径，因此读模型不会与权威状态分叉；代价是一次表重写，大规模部署必须安排窗口执行。

0.4 知识索引仍使用独立 classpath location，Flyway 固定管理 `zyblw_agent_knowledge` schema，并把
`flyway_zyblw_agent_knowledge_1536_history` 放在该 schema。这样两套 history 不共用，也不让两套 Flyway 生命周期共同
管理非空 `public` schema。0.5 不改动知识 schema，从 0.4.x 升级不需要重建 RAG 派生索引。

禁止用 `repair`、删除 history、伪造 checksum 或 `baselineOnMigrate` 掩盖未知结构。

完整步骤见[升级到 0.5.0](upgrading-to-0.5.0.md)；从 0.3.x 直接升级还需先完成[升级到 0.4.0](upgrading-to-0.4.0.md)
描述的知识索引重建。

## Durable Workflow 不变量

`WorkflowExecutionStore.commit` 是一个事务语义：Prepared execution、checkpoint、旧 wait 消费和新 wait 注册必须全部成功或全部
回滚。外部 signal 使用 `(waitKey, signalId)` 去重；相同 ID 不同 payload 冲突。PostgreSQL 使用数据库时钟判断 deadline，达到
deadline 后 timeout 胜出。恢复只消费由 owner/token/generation/expiry 完整 fence 领取的 Signaled/TimedOut；Pending 不允许执行
节点，已决议 wait 也不能通过普通 `resume` 绕过 claim。wait 行本身是 durable wake command，消费与下一 checkpoint 原子提交。

这些保证不自动让节点内部第三方副作用 exactly-once；支付、发信等写操作仍需业务幂等键或 outbox/inbox。

## 发布门禁

`0.5.0` 发布前必须同时通过：格式检查、完整 `testFull`、真实 PostgreSQL 16/pgvector（含 `V002` 生成列与 keyset 分页）、
核心后知识的组合迁移、重复启动、`publishM2`、独立 Maven consumer、HTTP/OpenAPI 契约、管理面授权边界测试，以及控制台的
`typecheck`、lint、生产构建与 Playwright 浏览器契约。发布标签必须来自远端 `main`，且 CHANGELOG 与
`docs/upgrading-to-0.5.0.md` 一致——`.github/scripts/verify-release.sh` 会 fail-closed 校验这三者。
