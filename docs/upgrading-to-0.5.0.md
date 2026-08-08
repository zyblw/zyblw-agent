# 升级到 0.5.0

> 适用范围：从 0.4.0/0.4.x 采用 0.5 的管理面、运行时配置覆盖与模型治理
> 最后核验：2026-08-08
> 原则：管理面全部可选，装配什么才暴露什么；配置覆盖只在有界白名单内生效；不修改已发布 migration

## 变化摘要

0.5.0 保持 Agent Runtime、耐久命令、HTTP v1 业务契约、Workflow outcome v2 与 0.4 知识 schema 不变。变化集中在
一个新增的**可选管理子面**和让它真正生效所需的运行时读取路径：

- `/api/v1/admin/**` 成为独立的 **Beta** 子面，与业务路由用 `++` 合并；每项能力都是 `Option`，未装配即不挂载路由；
- 运行时配置覆盖持久化在数据库，支持 CAS 写入、append-only 审计与跨副本刷新；
- 工具治理、检索工作点与模型路由改为经由 `ToolPolicySource`、`RetrievalPolicySource`、`ModelPolicySource` 解析；
- 单 Run 低敏耐久事件 SSE 支持按 `Last-Event-ID` 续传；
- 新增 `modules/agent-dashboard`（Next.js 浏览器端控制台），它不随 Maven 制品发布。

**不升级管理面也可以升级到 0.5.0。** 不装配任何 `AdminCapabilities` 时没有新增 HTTP 路由，但 V002 migration 与
下面两处 Layer 签名变化仍然适用。

## Scala 代码升级

把所有 `zyblw-agent-*` 模块统一升级到 `0.5.0`，然后完整重编译。两处需要显式处理：

1. **`AgentRuntimeLive` 的环境新增 `ModelPolicySource`。** 使用 `AgentApplication` 装配层的部署无需改动。直接装配
   Runtime 的部署必须补 `ModelPolicySource.defaultLayer`——它精确保留当前行为：每个 Agent 继续使用自己的
   `modelSettings`，且不做费用估算。
2. **`RuntimeSettingsService.layer` 新增要求 `ModelCatalog`。** 提供 `ModelCatalog.emptyLayer` 可保持既有行为，
   代价是**模型覆盖一律被拒绝**——这是有意的 fail-closed：一条指向未注册 Provider 的覆盖会在每次重启后重新加载，
   把一次下拉框误操作变成对所有调用的永久 `ProviderNotFound`，而控制台却显示保存成功。

自定义 `ChatModel`、`Tool`、`VectorStore` 与 Store 实现不需要改动。

## PostgreSQL 升级

核心 history 新增 `V002__zyblw_agent_admin_surface.sql`。**它会重写 `agent_runs` 表。**

V002 把 `tenant_id`、`user_id`、`awaiting_approval` 提升为 `agent_runs` 上的 STORED 生成列，并建立四个支持
keyset 翻页与审批过滤的索引。选择生成列而不是应用层双写，是为了让读模型不可能与权威 `state_json` 漂移：
生成列由 PostgreSQL 在写入时维护，所有写路径保持不变。代价是一次表重写。

1. 备份数据库。
2. **按表规模安排窗口。** `ADD COLUMN ... GENERATED ALWAYS AS ... STORED` 需要 `ACCESS EXCLUSIVE` 锁并重写整表；
   `agent_runs` 很大的部署不应在业务高峰执行。先在与生产同规模的副本上测量耗时，不要用空库的秒级结果做计划。
3. 执行核心 migration（`AgentPostgresMigrations.migrate`）。0.4 知识 schema 与它的独立 history 不受影响，
   不需要重新摄取，也不需要重建向量索引。
4. 重复启动应确认执行 0 个新 migration。

只装配了自定义 `AgentPersistence` 的部署不受 V002 影响，但也因此无法使用 `PostgresRunDirectory`——管理台的
Run 目录需要这些生成列。

## 管理面装配（可选）

管理路由是普通 `Routes`，与业务路由合并即可。完整装配说明见
[`docs/admin-console.md`](admin-console.md)，此处只强调升级时最容易出错的三点：

1. **授权是显式 scope，不是"归属即可读"。** 管理台看到的是跨租户聚合，因此 `agent:admin:read` /
   `agent:admin:write` / `agent:admin:debug` 必须由宿主的 `AgentRequestContextResolver` 解析出来。框架不自带认证
   中间件。`debug` **不被 `write` 蕴含**：检索沙盒、文档摄入与模型探活会产生真实 Provider 费用。
2. **能力探测是唯一事实来源。** 控制台按 `GET /api/v1/admin/capabilities` 隐藏页签，而不是对每个端点试探 404。
3. **控制台与后端不同源时必须允许 CORS 预检**，否则浏览器在 `capabilities` 阶段就失败。

管理 token 能改工具白名单与审批策略。授予建议：值班只给 `read`，变更审批人给 `write`，`debug` 临时授予。

## 运行时配置覆盖的边界

覆盖是**有界白名单**上的**稀疏补丁**：缺失字段表示沿用部署基线，因此删除一项覆盖与从未设置过它完全等价。
每一项都标注生效边界：

- `Immediate`——下次工具执行或检索即生效；
- `NextRun`——既有 Run 已把该值冻结进状态，只影响新 Run；
- `Restart`——装配期固化为不可变资源（如 `maxParallelism`），**直接拒绝覆盖**，而不是提供一个保存成功却无效的开关。

写入使用 compare-and-set：必须带上从 `GET` 读到的 `expectedVersion`，并发编辑的后提交方得到 409 而不是静默覆盖。

## 禁止操作

- 不修改已发布的 0.3 V001、0.4 知识 V001；
- 不把管理 scope 授予业务调用方的普通 token；
- 不用 `write` 代替 `debug` 去开放检索沙盒与摄入；
- 不把管理台部署在可公开访问且无认证的入口上——它是跨租户视图；
- 不依赖管理事件流获取业务正文：`AdminRunEventView` 是 allow-list，永远不含 `output` 与 `message`；
- 不在未测量表规模的情况下在高峰期执行 V002。

## 验收清单

- `sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'` 通过；
- `RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull` 通过；
- 真实 PostgreSQL 执行 V002 成功，重复启动执行 0 个新 migration，`agent_runs` 生成列与 `state_json` 一致；
- 未装配管理能力时 `/api/v1/admin/**` 全部 404，`capabilities` 如实报告；
- 缺少管理 scope 的请求在到达任何 Adapter 之前被拒绝；
- 配置覆盖的 CAS 冲突返回 409，且审计历史可追溯到操作者与原因；
- Maven-local 独立 consumer 编译通过；
- 备份、恢复、锁等待与回滚决策已由具体业务环境验证。
