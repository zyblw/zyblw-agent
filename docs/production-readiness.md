# 生产接入基线与发布候选判定

> 状态：当前运行手册
>
> 最后核验：2026-09-26
>
> 2026-09-23 对照：现行安装是 0.9 空库（核心与 1024 知识各一份 V001）。执行内核是 `AgentKernel` + `AgentRuntimeDriver`。Memory、RAG 与摘要走 User envelope。等待使用 `Suspension`。稳定 HTTP 是 OpenAPI `1.2.0`。本页不提升 Experimental 能力的成熟度。
>
>
> 事实来源：源码、测试、0.9 空库 V001、CI/发布工作流与本项目成熟度矩阵

本文面向准备基于 `zyblw-agent` 开发真实业务的团队。它把“框架可以被使用”“某个业务可以小流量上线”和“已经经过
通用大规模生产验证”分开，避免用单元测试数量或功能清单替代上线证据。

## 当前结论与版本建议

当前源码已经具备开发真实业务的主干：耐久提交、异步 Worker、有界多 Run 并发、lease/fencing、类型化工具与权限、
审批/取消/恢复、PostgreSQL、HTTP/SSE、低敏观测、RAG 和 Eval。**当前支持的接入拓扑是 Docker 启动 + 自管
PostgreSQL 直连**；7 项宿主环境证据已延期。新业务统一基于当前 **0.9 空库基线**（源码或 `0.9.0-local`）构建垂直切片；平台以固定 commit 消费同一份 0.9 源码。
不需要等待 Harness、多 Agent、Graph Studio 或完整 GraphRAG。

**`0.9.0` 是当前全新安装基线，由 annotated tag `v0.9.0` 触发 Central 发布**：核心与 1024 知识各一份 V001；适合本机 Compose 演练后进入
受限生产验收，而不是已经通过任意规模验证的通用 GA。Portal 显示 Published 之前，可重复构建使用该 tag 的源码或本机 `0.9.0-local`。当前契约是：

- 核心与 1024 知识各一份 0.9 V001；业务 HTTP v1 / OpenAPI 1.2.0、fresh-install state schema v1 与知识检索 mode 是当前契约；
- RAG 固定使用独立 1024 knowledge schema/history；所有新索引都按同一模型身份、维度与 lexical strategy 建立；
- 稳定知识面是 `/api/v1/knowledge/**`；管理面（`/api/v1/admin/**`）与控制台是 **Beta 且完全可选**；
- 投产前用本机 bundled Postgres（`compose.staging.yml`）或生产小流量验收 sibling 源码 / 精确 `0.9.0-local`；不要求常开产品 Test 站；
- Workflow、Harness、MCP/Sandbox 等标记为 Experimental 的能力不自动继承核心主线的成熟度。Artifact 元数据
  存储已升为 Beta，仍需业务侧验收附件路径。全新空库接入见
  [0.9.0 全新安装](fresh-install-0.9.0.md)。

业务仓库不要使用移动分支、版本范围或 `latest.release`。验证未发布提交时才使用唯一的内部
`0.9.0-local` 候选，且不得上传 Central。

### 当前能力采用判定

| 能力面 | 当前判定 | 生产使用边界 |
|---|---|---|
| 单 Agent Runtime、预算、取消、审批、恢复 | 可进入受限生产验收 | 固定组合与预算，先只读/低风险工具；必须跑业务 Eval 与故障演练 |
| PostgreSQL 状态、事件、命令、lease/fencing | 可作为耐久主线 | 真实 PostgreSQL 18 门禁必跑；宿主负责连接池、备份、主备和 RPO/RTO |
| OpenAI/DeepSeek/Qwen/GLM/Kimi/Anthropic/Gemini/中转站 | 可接业务开发 | 只开放逐模型已声明能力；每个实际 endpoint/model/profile 必须跑真实 smoke |
| HTTP/SSE、管理只读投影、OTel | 可接入 | 身份、TLS、租户限流和 Collector 由宿主提供；管理面独立暴露和授权 |
| RAG、Memory、模型辅助压缩 | Beta，可小流量验收 | 固定索引/数据集；验证 ACL、注入、撤回、保留期、质量与成本趋势 |
| Workflow、Harness、MCP/Sandbox、多 Agent | Experimental | 不随核心主线默认开放；逐能力完成独立威胁模型、恢复与业务收益证据 |

这里的“可接业务开发”表示框架边界和本地契约足以构建垂直切片，不表示某个厂商账号、业务数据、部署拓扑或 SLO 已由
框架仓库替宿主验证。

### 最新表结构与重建语义

当前源码只有两份版本化 SQL：核心 `V001__zyblw_agent_0_9_baseline.sql` 与独立 1024 维知识
`V001__agent_knowledge_0_9_baseline.sql`；其余两份 `R__*comments.sql` 只维护数据字典。`verify-local-evidence.sh` 会扫描
migration 目录全集，出现 V002、旧 V001 或额外 SQL 即失败，防止新代码继续背负未发布历史。

“不需要旧版本迁移”不等于“不执行 migration”：新环境仍必须在空 schema/新数据库执行当前 V001 来创建表、约束、索引和
Flyway history。已有 0.8 或更早数据库不能原地升级到本工作树；应新建数据库，从业务事实源重新提交业务数据，并重建
Memory/RAG 等派生数据。`resetAll` 是破坏性测试/本地重建工具，不是生产升级命令。

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
        │        ├─ Provider/中转站（HTTPS、Secret 引用、逐模型能力、超时、额度与 smoke）
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

模型中转站不降低准入要求：同一 URL/Key 下不同 wire 方言必须拆成独立逻辑 Provider；完整响应、SSE、usage、工具调用、
推理回放和错误分类按实际模型分别验证。中转站的数据保留、地域、上游供应商、模型版本固定和 SLA 是宿主责任。

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

Workflow 宿主可按冻结的 workflow/version 采样 `WorkflowExecutionStore.wakeQueueSnapshot`。`dueWaits` 持续非零表示 deadline
决议器滞后，`dispatchableWakeups`/`oldestDispatchableAgeMillis` 增长表示恢复能力不足，`expiredWakeLeases` 非零则优先排查
Worker、节点逻辑或数据库；该快照按定义聚合且不含业务身份和 signal 正文。

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
- 告警具有负责人、阈值、[值班 runbook](operations-runbook.md) 和可执行止损动作；
- OTel/Langfuse 不可用时 Run 仍正确推进，关闭过程不超过既定超时。

### 6. 发布与升级

- `0.9.0` 从空核心 schema 执行唯一 core V001；需要 RAG 时在 `zyblw_agent_knowledge` 专属 schema/history
  执行唯一 1024 Space/Profile knowledge V001。启动只接受当前空库基线，业务数据和知识均从最新来源创建；
- 格式、`testFull`、PostgreSQL 18、`publishM2` 和独立 Maven consumer 全部通过；启用控制台时另加类型检查、lint、
  生产构建与 Playwright 浏览器契约；
- CHANGELOG、升级指南、tag、远端 main 和 Maven 制品来自同一提交；
- 先 canary，再受限租户/只读工具，最后开放写工具；每一步都有回滚或停止扩流条件。

## 业务接入门禁

先跑这一档，再把框架引进业务 Docker：

```bash
./scripts/verify-business-ready.sh
sbt -batch 'set ThisBuild / version := "0.9.0-local"; publishM2'
```

公开 Central 发布仍走 tag 触发的 release workflow。kill-recovery 与有界 soak 属于仓库机制证据，不是当前单库 Docker
拓扑的上线前提。

## 框架发布候选门禁

```bash
./scripts/verify-business-ready.sh
sbt -batch 'set ThisBuild / version := "0.9.0-local"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.9.0-local sbt -batch 'clean; compile'
```

使用控制台的部署还需在 `modules/agent-dashboard` 执行：

```bash
npm ci
npm run typecheck && npm run lint && npm run build
npx playwright install --with-deps chromium
npm run test:e2e
npm run test:e2e:host
```

这些命令证明可构建、可迁移、可发布和可被独立 Scala 项目消费；它们不能替代业务数据集、容量、攻击、备份恢复和
真实 Provider 验证。

## 分阶段投产建议

| 阶段 | 允许能力 | 退出条件 |
|---|---|---|
| 开发 | Fake Provider、内存 Store、只读工具 | 垂直切片和确定性业务测试通过 |
| Pre-production validation | 本机 Compose 或生产小流量；PostgreSQL、真实 Provider、小额度、只读 RAG | 权限、质量、恢复、容量与低敏观测通过 |
| Limited Production | 小流量、单租户/白名单、受控审批写工具 | SLO 稳定、无高风险泄漏、值班与回滚演练通过 |
| 扩大流量 | 经验证的 Agent/Tool/RAG 组合 | 趋势 Eval、成本、故障率和人工反馈持续达标 |

Workflow、MCP/Sandbox、长期 Memory 与多 Agent 必须分别完成自己的验收，不能因为单 Agent 主线通过就自动开放。

## 当前仍阻止“通用生产 GA”宣称的证据缺口

- 已完成短时三 Worker/六 lane/48 Run 排他 drain、Worker Fiber 中断后过期重领、Worker 消失 + 数据库 pause/unpause
  组合故障后代际接管、本机独立 Worker JVM `SIGKILL` 后 generation 2 接管，以及 `pg_dump/pg_restore`；Workflow wake 与
  node execution 双租约也已通过独立 JVM `SIGKILL` 后 generation 2 接管；两条路径都验证同一 PostgreSQL 实例 restart
  后耐久事实与租约接管；正式 Start/Runtime 路径另有 3 Worker/6 lane、120 Run 的本机有界 soak 回归基线，全部完成且
  claim/terminal P95 通过宽松的仓库阈值；Workflow wait-as-command 路径也以 3 个独立 Store/Worker 完成 126 Run，wake/
  execution claim 与完成数一致、双 generation 零重领、最终未完成量为零；仍缺部署环境中的
  数小时/数天 soak、节点/Pod/VM 丢失、数据库主备切换和容量曲线；
- 真实 HikariCP/PgBouncer 饱和、滚动发布与备份恢复演练；
- RAG block/page/bbox lineage 已实现并通过 PostgreSQL round-trip；仍缺恶意 PDF/真实 OCR、大规模 corpus 容量和线上领域质量趋势；
- MCP OAuth/Roots/供应链、OCI 隔离攻击和真实第三方互操作；
- 独立外部业务用户的持续运行反馈。

因此，当前合理表述是“核心主线具备业务开发与受限生产采用条件”，不是“所有模块已经通用生产就绪”。最新证据与缺口
始终以[成熟度与路线](maturity-and-roadmap.md)为准。
