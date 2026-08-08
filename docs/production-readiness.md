# 生产接入基线与发布候选判定

> 状态：当前运行手册
>
> 最后核验：2026-08-08
>
> 事实来源：源码、测试、0.3 core baseline、0.5 admin V002、0.4 knowledge baseline、CI/发布工作流与本项目成熟度矩阵

本文面向准备基于 `zyblw-agent` 开发真实业务的团队。它把“框架可以被使用”“某个业务可以小流量上线”和“已经经过
通用大规模生产验证”分开，避免用单元测试数量或功能清单替代上线证据。

## 当前结论与版本建议

当前源码已经具备开发真实业务的主干：耐久提交、异步 Worker、有界多 Run 并发、lease/fencing、类型化工具与权限、
审批/取消/恢复、PostgreSQL、HTTP/SSE、低敏观测、RAG 和 Eval。新业务可以基于 0.5.0 构建垂直切片，
不需要等待 Harness、多 Agent、Graph Studio 或完整 GraphRAG。

**`0.5.0` 延续 0.3.0 的核心“业务生产基线”与 0.4.0 的结构化 RAG，并新增可选的管理面与模型治理**，适合从 staging
进入受限生产验收，而不是已经通过任意规模验证的通用 GA：

- 0.3.0 的 core V001、业务 HTTP v1、state/outcome 与核心控制面保持冻结；0.5.0 只追加 `V002`，不改写已发布 migration；
- 0.4.0 的结构化 RAG 契约与知识 schema 在 0.5 不变，从 0.4.x 升级**不需要**重建派生知识索引；
- 管理面（`/api/v1/admin/**`）与控制台是 **Beta 且完全可选**：不装配任何管理能力就不挂载任何管理路由，业务主线行为不变；
- 业务先在 staging 和受限流量使用 0.5.0，完成自己的数据、权限、Provider、容量和恢复验收后再扩大流量；
- Workflow、Artifact、MCP/Sandbox 等标记为 Experimental 的能力不自动继承核心主线的成熟度。

发布流水线完成前，业务仓库应使用唯一的内部 `0.5.0-local.*` 候选；Maven Central 显示 Published 后固定精确 `0.5.0`，不要使用
移动分支、版本范围或 `latest.release`。

启用管理面时，它本身也是一条需要单独验收的暴露面：管理路由必须只对运维身份开放，`agent:admin:debug` 会产生真实
Provider 费用，管理台的地址不应与业务 API 共用同一条公网入口和限流策略。

## 推荐生产拓扑

```text
可信身份/TLS/限流
        │
        ▼
业务 ZIO HTTP Routes ── AgentHttpApi ── PostgreSQL（唯一耐久事实源）
        │                                  │
        ├─ AgentHttpHost / WorkerHost ─────┘
        │        ├─ Provider（有超时、额度、降级与 smoke）
        │        ├─ Typed Tools（权限、幂等、审批、outbox/inbox）
        │        └─ RAG/Memory（ACL 前置、引用、撤回与保留）
        │
        └─ 低敏 OTel → Collector / Langfuse / Prometheus
```

框架不创建认证、DataSource、Secret Manager、业务限流或备份系统。生产应用必须显式提供这些边界；数据库故障时不能
静默回退到内存 Store。

## 最小依赖与装配

绝大多数业务从以下三个 artifact 开始，RAG、文档解析和 OTel 再按需加入：

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers" % agentVersion,
  "io.github.zyblw" %% "zyblw-agent-postgres"  % agentVersion
)
```

生产主线使用：

```scala
PostgresAgentPersistence.layer
AgentApplication.durable(workerId, applicationConfig)
```

对外提供异步 API 时再组合 `AgentHttpApi`；独立部署使用 `AgentHttpHost`，嵌入既有服务则只合并 routes。完整类型安全接线见
[AgentApplication 与 Builder](application-builder.md)和[ZIO HTTP 生产宿主](http-host.md)。发布流水线中的独立 Maven
consumer 会从制品重新编译这条生产装配，而不是引用仓库源码。

## 容量与过载边界

`WorkerHostConfig.parallelism` 是单实例同时推进的不同 Run 上限，默认 4、允许 1..256。同一 Run 的命令仍由 dispatcher
严格串行。集群理论 Run 并发上限约为：

```text
实例数 × worker.parallelism
```

这不是吞吐承诺。一个 Run 内还可能使用 `ToolPolicyConfig.maxParallelism` 并行执行互不冲突的工具，因此工具下游的最坏
并发压力需要同时考虑两个上限。上线前至少验证：

- P50/P95/P99 提交、排队、首 token、完成与恢复时延；
- Provider 429/5xx、连接中断和额度耗尽时的失败与降级；
- JDBC pool、PostgreSQL CPU/锁/IO、Pod 内存和 Fiber 数在峰值下没有饱和；
- 队列增长时拒绝、降级或扩容策略明确，不能依靠无界重试；
- 每个 Agent 的步骤、模型调用、工具调用、token、费用和 wall-clock 均有硬预算。

并发值应从 1 或 4 开始按测量调整。256 是防止错误配置的硬边界，不是推荐生产值。

`AgentApplication.queueSnapshot` 可直接采样 `dispatchableRuns`、`oldestDispatchableAgeMillis`、`leasedRuns`、
`expiredLeases` 和 `deadLetterCommands`。框架提供数据，不替业务选择阈值；至少把“最长等待持续增长”“过期 lease 非零”和
“DeadLetter 新增”配置成不同告警，因为三者的处置分别是扩容/下游诊断、Worker/数据库诊断和人工重试审查。

## 上线前六类强制证据

### 1. 身份、权限与不可信输入

- `RunContext` 只由已验签 JWT/session/mTLS 映射，正文、普通 header 和模型输出不能授予 scope；
- Agent 工具集合是全局 allowlist 的子集，写工具按风险进入审批；
- RAG 在候选排名前完成 tenant/user/scope ACL 过滤；
- Prompt、网页、PDF、MCP 描述和工具结果均按不可信数据处理；
- 反向代理和 Server 同时限制请求体、连接、读取、并发和租户速率。

### 2. 副作用与恢复

- 每个写操作有稳定业务幂等键或唯一约束；跨系统写使用 outbox/inbox 或等价事务边界；
- 演练 Worker `SIGKILL`、Provider 断流、lease 过期、重复命令和数据库短时不可用；
- 确认旧 generation 无法迟到写入，恢复不会重复已登记的成功工具结果；
- 备份、恢复、RPO/RTO、数据修复和删除路径由实际操作者执行过，而不是只存在文档。

### 3. 数据与隐私

- 定义 Prompt、工具结果、Memory、RAG、Artifact、Trace 和 Eval 的所有者、用途、保留期与删除方式；
- 使用假凭据和假敏感正文检查 Collector、Langfuse、日志、HTTP 错误和 Inspector 均不泄漏正文；
- Provider 与文档解析器的数据出境、许可证和供应商保留策略已经审查；
- 健康、金融等高风险业务设置拒答、人工复核和审计，不把模型概率当作授权。

### 4. 质量与成本

- 建立版本化业务数据集，分别评分 outcome、trajectory、safety、latency、token 和 cost；
- 正常、无证据、冲突证据、Prompt injection、权限隔离和 Provider 失败均有固定用例；
- 关键路径使用重复试验，不以一次通过决定发布；
- instruction fingerprint、模型版本、索引版本和 evaluator 版本进入评测身份。

### 5. 观测与值班

- liveness 反映关键 Worker 生命周期，readiness 在硬超时内验证耐久依赖；
- dashboard 能区分排队、模型、工具、审批、恢复和投影阶段；
- 告警具有负责人、阈值、runbook 和可执行止损动作；
- OTel/Langfuse 不可用时 Run 仍正确推进，关闭过程不超过既定超时。

### 6. 发布与升级

- fresh 核心 schema 执行冻结的 core V001 与追加的 `V002`（生成列会重写 `agent_runs`，大表需安排窗口）；需要 RAG 时在
  `zyblw_agent_knowledge` 专属 schema/history 执行唯一 0.4 knowledge V001；所有派生 RAG 索引可重建；
- 格式、`testFull`、PostgreSQL 16、`publishM2` 和独立 Maven consumer 全部通过；启用控制台时另加类型检查、lint、
  生产构建与 Playwright 浏览器契约；
- CHANGELOG、升级指南、tag、远端 main 和 Maven 制品来自同一提交；
- 先 canary，再受限租户/只读工具，最后开放写工具；每一步都有回滚或停止扩流条件。

## 框架发布候选门禁

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.5.0-local.1"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.5.0-local.1 sbt -batch 'clean; compile'
```

使用控制台的部署还需在 `modules/agent-dashboard` 执行：

```bash
npm run typecheck && npm run lint && npm run build
npm run test:e2e
```

这些命令证明可构建、可迁移、可发布和可被独立 Scala 项目消费；它们不能替代业务数据集、容量、攻击、备份恢复和
真实 Provider 验证。

## 分阶段投产建议

| 阶段 | 允许能力 | 退出条件 |
|---|---|---|
| 开发 | Fake Provider、内存 Store、只读工具 | 垂直切片和确定性业务测试通过 |
| Staging | PostgreSQL、真实 Provider、小额度、只读 RAG | 权限、质量、恢复、容量与低敏观测通过 |
| Limited Production | 小流量、单租户/白名单、受控审批写工具 | SLO 稳定、无高风险泄漏、值班与回滚演练通过 |
| 扩大流量 | 经验证的 Agent/Tool/RAG 组合 | 趋势 Eval、成本、故障率和人工反馈持续达标 |

Workflow、MCP/Sandbox、长期 Memory 与多 Agent 必须分别完成自己的验收，不能因为单 Agent 主线通过就自动开放。

## 当前仍阻止“通用生产 GA”宣称的证据缺口

- 已完成短时三 Worker/六 lane/48 Run 排他 drain、Worker Fiber 中断后过期重领、数据库 pause/unpause 与
  `pg_dump/pg_restore`；仍缺部署环境中的数小时/数天 soak、真实 `SIGKILL`/节点丢失、数据库主备切换和容量曲线；
- 真实 HikariCP/PgBouncer 饱和、滚动发布与备份恢复演练；
- RAG block/page/bbox lineage 已实现并通过 PostgreSQL round-trip；仍缺恶意 PDF/真实 OCR、大规模 corpus 容量和线上领域质量趋势；
- MCP OAuth/Roots/供应链、OCI 隔离攻击和真实第三方互操作；
- 独立外部业务用户的持续运行反馈。

因此，当前合理表述是“核心主线具备业务开发与受限生产采用条件”，不是“所有模块已经通用生产就绪”。最新证据与缺口
始终以[成熟度与路线](maturity-and-roadmap.md)为准。
