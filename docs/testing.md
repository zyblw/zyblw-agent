# 测试与验证

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-08-02
>
> 事实来源：对应模块源码、测试与构建定义

## 聚合测试的资源隔离

根构建通过 `Tags.limit(Tags.Test, 1)` 顺序调度各子项目的 Test task；单个子项目内部仍保留 ZIO Test 并行执行。这是为了
避免 OpenAI、Anthropic、Gemini、Cohere、OTLP、MCP 等多个模块同时各自创建 Netty event-loop group，导致 CI/开发机
先耗尽 native thread。真正需要验证的 Provider 并发、取消和慢流仍在每个模块内部并发运行。

若看到 `unable to create native thread`，应先检查是否绕过根构建并并行启动了多个 sbt 进程；不要通过删除流式契约测试
或增大生产连接池来掩盖测试宿主资源问题。

```bash
sbt "scalafmtCheckAll; scalafmtSbtCheck"
sbt testFull
```

Pull request 和发布工作流都会先执行同一 Scalafmt 门禁。格式基线由仓库 `.scalafmt.conf` 与
`sbt-scalafmt` 锁定，不以某位开发者的编辑器配置为准。

### 最近一次完整本地证据

2026-08-02 的业务生产基线候选复核：

- `scalafmtCheckAll; scalafmtSbtCheck; testFull`：全部已执行确定性测试通过、0 失败；core 103 项通过，PostgreSQL
  用例按默认开关忽略；
- WorkerHost 新增单实例有界多 Run lane 契约：配置为 2 时只允许两个不同 Run 同时进入 Runtime，lane 释放后第三条继续
  领取；同 Run 串行仍由已有 dispatcher 测试保护；配置加载覆盖默认值、显式值和 256 硬上限；
- `RUN_POSTGRES_INTEGRATION=1 postgres/testFull`：PostgreSQL 16.14、0.3 fresh V001 与 optional pgvector 共 30 项通过、
  0 失败、0 忽略；
- `0.3.0-local publishM2` 成功生成 11 个公开模块的 POM、binary、sources 与 Scaladoc JAR；独立 Maven consumer 不引用
  仓库源码，重新编译 Agent Definition、Worker 配置、PostgreSQL 控制面、知识 Store 和 durable Application 生产装配；
- `QuickstartAgentExample` 与 `RagAgentExample` 实际运行到 `Completed`，后者同时完成文档摄取、active snapshot、检索和引用；
- 尚未执行长时间 process kill/数据库 restart/multi-Worker soak 和真实业务容量曲线，因此结论是“0.3.0 生产基线候选”，
  不是通用规模 GA。

2026-08-01 的 `0.3.0` breaking development line 复核：

- `scalafmtCheckAll; scalafmtSbtCheck; testFull`：全部已执行确定性测试通过、0 失败；PostgreSQL 用例按默认开关忽略；
- Workflow core 共 20 项契约，新增 `Awaiting` 原子注册/消费、signal 幂等/冲突、Pending/未 claim 恢复拒绝、wait identity
  fail-closed、deadline 后 signal/timeout 唯一胜者、wake worker timer 恢复、长恢复 heartbeat，以及 wake lease 排他/过期 fencing；
  整个 core 模块 102 项通过；
- `RUN_POSTGRES_INTEGRATION=1 postgres/testFull`：PostgreSQL 16.14 只执行一个
  `V001__zyblw_agent_0_3_baseline.sql`，连同 optional pgvector 共 30 项通过、0 失败、0 忽略；
- PostgreSQL Workflow 共 9 项：除 checkpoint/ledger/timeline/fencing 外，真实验证 wait 与 checkpoint 原子注册、跨 Store
  signal 去重、毫秒 deadline 身份、signal/`expireDue` 唯一决议，以及两个 Store 并发唯一 wake claim、数据库租约过期重领和旧 fence 拒绝；
- `0.3.0-local publishM2` 生成 11 个公开模块的 POM、binary、sources 与 Scaladoc JAR；独立
  `integration-tests/maven-consumer` 仅解析 Maven Local 坐标并完成 clean compile；
- 尚未执行长期 process kill/数据库 restart/multi-worker soak；上述证据证明事务与短时并发契约正确性，不等同于生产容量结论。

2026-07-30 至 2026-08-01 的 `0.2.1` 发布复核：

- `scalafmtCheckAll; scalafmtSbtCheck; testFull`：所有已执行确定性测试通过、0 失败；PostgreSQL 用例按默认开关忽略；
- Workflow 为 14 项 core 契约与 6 项真实 PostgreSQL 契约；新增低敏 timeline 复合游标、跨 Run 隔离、分页上限，以及
  同一 Run 跨 step 的 Workflow/version/session identity 串行仲裁；
- Anthropic、Gemini 与 Langfuse 的本机 ZIO HTTP 契约改用 Server 原子分配动态端口，目标用例分别为 2、2、6 项，
  消除了探测端口后关闭再绑定的竞争；
- `RUN_POSTGRES_INTEGRATION=1 postgres/testFull`：PostgreSQL 16.14 下正式 V001/V007/V008/V009 与 optional pgvector
  migration 全部执行，27 项通过、0 失败、0 忽略；
- `0.2.1-local publishM2`：11 个公开 artifact 的 POM、binary、sources 与 Scaladoc JAR 完整；
  `integration-tests/maven-consumer` 只从 Maven Local 解析这些坐标并执行 clean compile 成功；
- `QuickstartAgentExample` 实际完成异步 command/worker/runtime 主线，`GraphWorkflowExample` 实际完成 durable
  execution 示例；
- release provenance gate 的隔离成功用例通过，并确认旧 `v0.2.0` 对当前 `0.2.1` CHANGELOG 会 fail-closed；正式
  annotated `v0.2.1` 已验证升级指南、CHANGELOG 与远端 `main` 一致；
- [GitHub CI #15](https://github.com/zyblw/zyblw-agent/actions/runs/30547389663) 与
  [Release workflow](https://github.com/zyblw/zyblw-agent/actions/runs/30547847643) 全部成功；GitHub Release 标记为 Latest，
  Maven Central 已公开解析 `io.github.zyblw:zyblw-agent-core_3:0.2.1` 的 POM；
- 独立 consumer 使用全新 Coursier cache，并把 repository 限定为 Maven Central 后，成功下载正式 `core/providers 0.2.1`
  JAR 并执行 clean compile；没有读取 Maven Local 候选。

2026-07-29 的结构化文档 RAG R2-A 复核：

- `scalafmtCheckAll; scalafmtSbtCheck; testFull`：所有已执行测试通过，0 失败；23 项 PostgreSQL 用例按默认开关忽略；
- `rag/test; documentLoaders/test; examples/compile`：RAG 32 项、Document Loader 9 项全部通过，示例编译成功；
- Docling Serve v1 合同测试 4 项：multipart/API Key/Markdown 解码、HTTP 错误分类与脱敏、输入/响应上限和
  `partial_success` fail-closed；
- `RUN_POSTGRES_INTEGRATION=1 postgres/testOnly ...PostgresKnowledgeIndexIntegrationSpec`：PostgreSQL 16 +
  pgvector 真实迁移与知识索引发布契约 1 项通过；
- `RagAgentExample` 实际运行到 `Completed`，输出 `ingestion=Indexed`；Loader、Markdown 结构分块、active
  知识发布、检索与引用链路闭合；
- `RUN_POSTGRES_INTEGRATION=1 postgres/testFull`：V001/V007/V008、optional pgvector 迁移及 25 项 PostgreSQL
  契约全部通过，0 失败、0 忽略；
- `0.2.0-local publishM2` 生成 11 个公开 artifact 的 POM、binary、sources 与 Scaladoc JAR；独立
  `integration-tests/maven-consumer` 只从 Maven Local 解析这些坐标并编译成功。

2026-07-29 的 Workflow G2-A 复核：

- `scalafmtCheckAll; scalafmtSbtCheck; testFull`：所有已执行测试通过，0 失败；23 项 PostgreSQL 用例按默认开关忽略；
- `RUN_POSTGRES_INTEGRATION=1 postgres/testFull`：正式 V001/V007/V008 与 optional pgvector migration 全部执行，
  25 项 PostgreSQL 16/pgvector 契约通过、0 失败、0 忽略；
- 其中 Workflow 为 11 项 core 契约与 4 项真实 PostgreSQL 契约。

2026-07-30 的 Workflow G2-B 增量复核：

- 新增 execution lease generation/fencing 与 prepare 后、checkpoint 前故障注入，Workflow core 契约增至 13 项；
- V009 在 PostgreSQL 16 验证活跃 owner 互斥、过期 Prepared outcome 跨 owner 复用、旧 generation 拒绝，以及 ledger 与
  checkpoint 同事务提交；Workflow PostgreSQL 契约增至 5 项；
- `scalafmtCheckAll; scalafmtSbtCheck; testFull` 全部通过；`RUN_POSTGRES_INTEGRATION=1 postgres/testFull` 执行正式
  V001/V007/V008/V009 与 optional pgvector migration，26 项 PostgreSQL 16/pgvector 契约通过、0 失败、0 忽略。

2026-07-26 的独立仓库 `0.1.0` 发布准备复核：

- `testFull`：所有已执行的确定性测试通过，0 失败；19 项 PostgreSQL 用例按默认开关忽略；
- `0.1.0-local publishM2`：11 个公开 artifact 的 POM、binary、sources 与 Scaladoc JAR 完整；
- `integration-tests/maven-consumer`：不使用源码 `ProjectRef`，解析全部 11 个 Maven-local 坐标并编译成功；
- `RUN_POSTGRES_INTEGRATION=1 postgres/testFull`：使用 Testcontainers/PostgreSQL 16 与 pgvector，
  21 项通过、0 失败、0 忽略；
- 公开 CI 已使用 `postgres/testFull`；旧的 `postgres/test` 在 sbt 2 中可能报告 0 tests，不能作为门禁证据。

公开仓库首次 GitHub Actions 必须重新取得与本地相同的容器证据，不能只引用本地结果。

2026-07-25 使用 Temurin JDK 21 完成：

- `testFull`：所有确定性模块测试通过；PostgreSQL 用例按设计在该命令中忽略；
- `RUN_POSTGRES_INTEGRATION=1 postgres / Test / testFull`：21 项通过、0 失败、0 忽略；
- ZIO HTTP Inspector/协议：35 项通过；
- 五分钟 Quickstart：实际运行到 `Completed`；
- `0.1.0-local publishM2`：11 个公开 artifact 均生成 POM、binary、sources 与 Scaladoc JAR；
- `zyblw-server` 源码 ProjectRef 与 Maven-local 二进制两种模式各 28 项通过；两项 server 自身的远程数据库用例未启用。

上述是可重复的本地发布候选证据，不是 GitHub Actions 已经跑过的结果，也不是生产容量结论。OpenAI、DeepSeek、GLM、
Anthropic 与 Gemini 密钥均未配置，因此没有执行真实付费 Provider smoke；默认 stub/协议测试不能替代这一证据。

当前确定性测试不访问真实网络或 API Key，覆盖：

- Provider 路由和能力差异。
- OpenAI-compatible 与 OpenAI Responses 原生请求兼容。
- SSE 任意 byte 分块、UTF-8、工具参数、usage、空流和错误。
- 文本回答、工具循环、未知工具、预算、审批和恢复。
- 流式事件、显式取消和 Cancelled 状态。
- RunStore 乐观锁与事件幂等。
- RunStore 乐观锁、幂等事件、版本递增与级联删除。
- Durable Runtime 直接状态、事件序号、审批、账本成功复用与非幂等 Unknown 恢复。
- DurableRunEventStream 有界分页、sequence 缺口拒绝、事件/状态查询之间提交的 TOCTOU 安全重读、TestClock 轮询恢复、
  超前游标拒绝；HTTP 覆盖 Last-Event-ID、SSE 终态结束与统一 tenant/user 读取授权。
- ZIO HTTP Provider stub server：真实路径/正文、usage 解码和 429 retryable 分类。
- Provider 故障契约：SSE 断流、慢流超时、负 usage、429、5xx 和取消传播。
- Responses typed SSE：reasoning item 回放、扁平 function tool、工具参数增量、唯一完成事件与 HTTP Body 取消传播。
- Anthropic Messages：content blocks、tool_use/tool_result、thinking/signature 回放、任意 byte 分块、慢流、断流、
  429/500、负 usage、唯一完成事件与 HTTP Body 取消传播。
- ProviderContract 2.0：成功语义、错误分类/retryable、Transport 取消和 Redacted cassette 的公共报告。
- Live Provider Smoke Runner：complete/stream、marker、usage、token、延迟、能力失败和报告不含 prompt/响应/错误原文；
  公网 smoke 由显式 CLI 与 CI secret 运行，不进入默认 `testFull`。
- AgentDefinitionBuilder：不可变定义组合、缺失指令、工具策略漂移、敏感 metadata 和非法模型参数的启动期门禁。
- AgentApplication：异步 Start、Worker claim、审批恢复、Cancel 抢占活动 lease/模型 Fiber、durable 依赖显式接入和 scoped Worker 中断；验证便利层没有旁路
  耐久状态机或静默注入生产治理默认值。
- AgentQuickstart：五分钟入口仍经过 submit/claim/Runtime/inspect，工具白名单引用未注册工具时在付费模型调用前拒绝。
- Run Inspector：Timeline 不含消息、Prompt、工具参数/结果与失败正文；分页游标、sequence 缺口、Run 混入、审批、
  usage 和终态事件诊断稳定。
- AgentApplication Context 压缩装配：`*WithContextCompressor` 真实进入主 loop、checkpoint 与辅助 usage 被持久化；
  Deterministic 策略不会误调用已装配的 LLM compressor，ModelAssisted 缺能力时在 Provider 前 fail-closed。
- AgentHttpHost：live/ready、主 API 与附加 routes 组合、no-store、TestClock readiness 硬超时、失败脱敏、关键 Worker/Server
  双向故障传播、Host 中断立即关闭内部子 Scope，以及进程名称/非空/唯一性启动门禁。
- 遥测敏感边界：模型 delta/最终答案不投影，prompt 被删除，Authorization 固定替换，Langfuse 配置不泄漏密钥。
- Metrics/Trace：Run/Model/Tool duration 配对、暂停恢复 active gauge、GenAI usage、RAG/Memory/Eval 观测和低基数 allow-list。
- Exporter 故障：真实 OTLP endpoint 不可达时 record/emit 与业务错误通道隔离，Scope 仍能关闭。
- command 幂等正文冲突、同 Run 串行、Cancel 抢占、DeadLetter 与人工 retry 计数。
- TestClock heartbeat 抢占中断及 finalizer；WorkerHost 完整传递 command/owner/token/generation。
- 永久错误直接 DeadLetter；可重试错误安全重排队且不泄露 Provider 原文。
- AgentCommandService 的 approvalId 绑定、tenant/user 归属与管理员 scope。
- ZIO HTTP 审批/取消/恢复/重试返回 `202 + commandId`，命令查询不暴露完整 payload。
- PostgreSQL 真实锁验证：旧 generation 即使持有正确 AgentState version，也无法写入状态或事件；新 generation 可继续提交。
- 工具读写冲突批次、部分失败聚合与确定性结果顺序。
- 主 Runtime 批量 Prepared、批内并行、部分成功时强制中断、成功账本复用、未完成调用单独重试和 Tool 消息顺序。
- 工具 callId 的同批幂等恢复与跨批身份冲突拒绝。
- 单次模型响应的工具总预算前置门禁，验证任何 pending write 和业务副作用发生前即拒绝超限。
- 业务 eval：工具选择、引用正确率、恢复重复副作用、延迟、token 和成本门禁。
- RAG eval：Recall@K、Precision@K、MRR、NDCG、引用 excerpt 证据、required source、tenant/permission、禁止片段、
  重复/非有限分数、延迟和空数据集 fail-closed。
- Context 压缩 eval：版本化严格 UTF-8 数据集、重复运行、关键证据/引用最差召回、禁止内容零命中、摘要哈希与版本稳定性、
  主动 Fiber 超时、输入/输出 token、模型调用、摘要长度、带版本成本，以及 typed failure 脱敏后继续执行。
- Context 压缩 live smoke：工具能力和模型辅助声明在计费前校验，固定约束/引用/注入数据、关闭确定性 fallback、
  defect 脱敏、调用方取消不继续重复请求；默认测试使用 stub，不访问公网。
- Eval 趋势与发布门禁：Agent/RAG/Context Compression 报告投影不含 input/details/错误正文；默认策略拒绝硬门禁失败、
  通过率/维度分数下降和用例/维度删除；首次 baseline 必须显式开启；文件 Store 覆盖并发追加、幂等重放、FileLock、
  checksum 篡改拒绝、半写尾恢复、同 evaluationId 内容冲突和最近成功基线选择；未授权的首次通过候选不追加，连续运行
  不能隐式绕过 bootstrap。
- Eval 正式 CLI：ZIO Config 文件/PostgreSQL 互斥加载、`Config.Secret` 脱敏、普通文件/NOFOLLOW_LINKS/严格 UTF-8/
  容量/领域语义 artifact 校验，以及 0 通过、2 质量拒绝、3 配置错误、4 基础设施错误的稳定分类。
- ZIO Config 部署契约：AgentApplication、Context Compressor、HTTP Host 与 Eval CLI 使用点分 prefix +
  `snake_case` 叶子键，确保默认环境 Provider 真实命中 `.env.example` 中的 `ZYBLW_AGENT_*` 变量。
- DataSource 连接池耗尽映射为 typed、retryable 持久化错误。
- OutboxPublisher 根据 typed error 执行 published/abandon/dead-letter，并与 heartbeat Fiber 结构化绑定。
- CompensationRegistry 重名拒绝与 CompensationWorker 固定 handler 执行。
- Embedding HTTP stub：分批、乱序 index、固定维度、usage 汇总、429、慢 Body、非法响应与取消关闭连接。
- Cohere Reranker HTTP stub：v2 wire/Bearer、index 映射、search units、429 相同正文重试、401 不重试、重复 index、
  越界分数、响应上限、总超时和取消关闭 Body。
- Embedding 治理：请求内去重、同租户缓存命中、跨租户隔离、并发原子配额、requestId 幂等/冲突和裸调用拒绝。
- KnowledgeIndexer：ingestion 幂等、active 乐观条件、失败 manifest、版本化块与完成后不重复调用 Provider。
- DocumentLoader：重复 MIME 拒绝、身份漂移、可信 metadata 优先级、并发输出顺序、Continue/FailFast 和 Fiber 取消；
  Tika 使用内存夹具真实解析纯文本、HTML、PDF、EPUB，并验证预声明/实际字节上限与伪装 PDF；Docling Serve v1
  适配器验证有界 PDF multipart、API Key、Markdown 响应、超时/状态分类和错误脱敏。
- MarkdownStructureChunker：标题路径、表格、围栏代码、Unicode code point 上限与 overlap、内容寻址稳定 ID、
  重复片段确定性消歧，以及 Knowledge manifest 使用实际参数化 `strategyId`。
- RagApplication：正式 Loader→Indexer→active snapshot→Retriever 主路径、tenant/permission 前置过滤，以及 query/topK
  在 Retriever/Embedding 前的硬限制；内存知识 Store 同一实例承担发布与查询。
- MemoryLifecycle：CAS 冲突、删除 tombstone、分批过期、证据优先、敏感推断/低置信/未授权删除拒绝。
- Memory 用户治理：自有/跨租户/Session/匿名授权矩阵、纠正字段投影与 CAS、删除幂等、query/正文/key/scopes/
  attributes 不进入审计；Retention Worker 验证最大批次、固定 cutoff、瞬时错误 Schedule 重试、永久错误和 Scope 中断。
- Memory ZIO HTTP：身份只来自 resolver、User scope 不接受客户端参数、400/403/404/409 映射、CAS、幂等删除和响应脱敏。
- LLM MemoryExtractor：唯一 strict tool、逐字 evidence quote、角色派生证据、安全 repair、敏感默认拒绝、Lifecycle 二次治理与模型总超时。
- Workspace：路径穿越、空段、Windows 分隔符、symlink 逃逸、原子写入、禁止覆盖和单文件/总容量配额。
- OCI Sandbox：默认断网/只读/cap-drop/no-new-privileges/资源限额 argv，secret 不进入 argv，运行时保留环境变量拒绝，
  真实 JDK 子进程 stdout/stderr 并行排空、合计输出截断、非零退出码和墙钟硬超时。
- Langfuse Scores：Basic Auth/endpoint、完整 typed payload、稳定 id/name/timestamp、429 相同正文重试、401 不重试、
  响应上限、无限 Body 取消关闭、自由文本/comment/NaN/名称白名单，以及 Eval 投影不上传 details/业务用例名。
- Context：system/Memory/RAG/recent 分区预算、重复来源去重、JSON/tool arguments token 计数、工具输出压缩、原子
  tool-call/result 裁剪、重度历史淘汰 rot signal、Debug View 无正文，以及 Runtime Trace/Metrics 只接受白名单 code。
- LLM Context Compressor：唯一 strict tool、逐字 evidence/reference、本地 schema、温度归零、输出预算、有限 repair、
  validation 确定性降级、辅助模型调用预留、超时取消、usage 累计；耐久 summary checkpoint 复用与源前缀哈希篡改拒绝。
- Runtime Context checkpoint：摘要、辅助模型 usage、事件序号和 AgentState 版本在主模型前原子提交；恢复不会重复压缩同一
  历史前缀，HTTP/Telemetry/OTel 只公开版本、计数和 token。

## PostgreSQL Testcontainers

```bash
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresRunStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresRunCommandStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresSideEffectIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresKnowledgeIndexIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresMemoryStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresEmbeddingGovernanceIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresEvalTrendStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresWorkflowCheckpointStoreIntegrationSpec"
```

该测试使用 PostgreSQL 16 而不是 H2，执行正式 Flyway migration，并验证事务、乐观锁、审批状态恢复、工具
账本、并发取消、24 worker command `SKIP LOCKED` claim、租约过期抢占、Cancel 原子抢占、旧 generation 状态 fencing，
真实 `pg_dump`/`pg_restore` 后 Run、命令正文与 dispatcher generation 的恢复，以及业务 mutation/outbox/补偿同事务、
错误回滚、发布确认崩溃窗口、旧 generation 拒绝、inbox/consumer mutation 同事务去重。独立 CI job 与发布门禁均设置
该变量；本地默认
关闭是为了不强迫纯单元测试环境安装 Docker。知识索引用例使用 `pgvector/pgvector:pg16`，验证 staging 不可见、
块数失败回滚、active 原子切换、RRF ranking signals 和 tenant/permission 过滤。Memory 用例验证并发 CAS 仅一个胜出、
tenant+user 隔离、中文检索、tombstone 真实清空正文、多 worker 过期领取，以及审计约束失败时纠正回滚和成功纠正/
低敏审计同事务。
Embedding 治理用例另外验证 0.3 基线中的三张治理表、REAL[] 批量编解码、跨 Store 实例缓存、tenant 隔离、窗口行锁、并发硬配额、
requestId/hash 幂等冲突与窗口清理级联释放。
Eval 趋势用例验证低敏表、跨 Store 并发 `ON CONFLICT` 仲裁、同 ID 内容冲突、kind 隔离、精确时间排序、最近成功
部分索引语义、TEXT/JSONB 双表示一致性、checksum 篡改拒绝和原始 grade details 不落库。
- 工具空白名单默认拒绝。
- Workflow 声明式边的启动校验、不可达/缺失目标诊断、循环访问上限、完成/暂停 checkpoint 恢复、
  definition/session identity、单调写冲突、未声明动态路由拒绝，以及 `AllSucceeded` fan-out 失败时的兄弟 Fiber 中断和
  join checkpoint 隔离；durable 模式另覆盖 lease generation/fencing 和 Prepared outcome 故障恢复。
- PostgreSQL Workflow：0.3 fresh baseline、跨 Store 并发幂等与单调 step、identity 漂移拒绝、checksum 损坏 fail-closed、
  暂停后跨 Adapter 实例恢复、execution ledger/checkpoint/wait 原子提交、signal 去重与数据库时钟 deadline 竞态。
- RAG tenant/permission 前置过滤。

Testkit 提供 Scripted/Recording Provider、Stub/Failing/Slow/NonInterruptible Tool、固定 TokenCounter 和确定性 ID。

尚需补充：多节点网络分区/进程 `SIGKILL` 混沌测试、持续数小时 soak test、真实 HikariCP/PgBouncer 饱和测试、
MCP 已有脚本协议、真实 JDK stdio 子进程和 ZIO HTTP stub contract，覆盖取消、非法 stdout、SSE 断流、Last-Event-ID、404 session 恢复、Bearer、审批与实验 Tasks；仍需 OAuth server、真实第三方 MCP 互操作、模糊测试和容器故障注入。当前 64/24 并发规格是回归负载，不应冒充容量结论。
