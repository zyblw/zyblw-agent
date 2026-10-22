# 0.10.0 全新安装

> 状态：当前唯一安装基线  
> 最后核验：2026-10-22  
>
> 2026-10-22 对照：现行安装是 0.10 空库（核心与 1024 知识各一份 V001，包含结构快照与大纲检索）。执行内核是 `AgentKernel` + `AgentRuntimeDriver`。模型治理支持 `LiveModelRegistry` 与原币价目冻结；计费估算支持 `BillingTariff`。知识检索支持对比规划、双路召回与 `knowledge_outline`。等待使用 `Suspension`。稳定 HTTP 是 OpenAPI `1.0.0`。

`0.10.0` 只支持全新数据库。不要导入旧 Flyway history、旧 Run、旧 embedding cache 或旧知识索引；业务资料应按当前 tokenizer、Embedding、切分和 ACL 规则重新摄入。

## 数据库

1. 创建空 PostgreSQL 数据库并安装 `pg_trgm` 与 `vector` 扩展。
2. 调用 `AgentPostgresMigrations.migrateCoreAndKnowledge1024`。
3. 确认核心 history 只有 `V001__zyblw_agent_0_9_baseline.sql`，知识 history 只有 `V001__agent_knowledge_0_9_baseline.sql`。
4. 核心使用 `zyblw_agent_core`，知识使用 `zyblw_agent_knowledge`，vector/pg_trgm 使用 `zyblw_extensions`，不使用 `public`。知识 schema 固定 1024 维；更换 Embedding 模型、维度或 strategyId 时通过 `KnowledgeProfileMigration` 创建新索引版本。
5. 结构探针失败时修正目标数据库或重新创建空库，不执行 Flyway repair/baseline。

## 检索、结构大纲与防护

- `RetrievalMode` 支持 `Hybrid`、`VectorOnly`、`LexicalOnly`、`Phrase`。
- `DeterministicQueryPlanner` 支持对比检索意图规划（`comparisonParts` 对比/比较两端实体），触发 Hybrid 与 Phrase 精确短语双重召回。
- `RetrievalFilter` 在 ACL 之后、打分之前约束 document、chunk、page、heading 和 metadata。
- `knowledge_search` 与 `knowledge_fetch` 是正式只读工具；tenant 和 permissions 只能来自可信宿主上下文。资料范围由宿主显式声明：`documentScope=unrestricted` 或非空文档 ID。未命中时返回带 `guidance` 建议。`knowledge_fetch` 支持 `expandNeighbors`（最多 4 个相邻块）。
- `knowledge_outline` 读取已授权文档的结构大纲（`StructureOutline`），在目录节点超限时按紧凑策略收敛，不进入默认工具层，也不产生 chunk 引用。
- `UntrustedContentMonitor` 拦截对抗注入与零宽字符隐写，支持 Unicode 空白字符归一化与固定 `Locale.ROOT` 大小写转换。

## 模型治理与计费规则

- `LiveModelRegistry` 支持纯配置值定义连接并按版本增量重载，换 Key 或 URL 无需重启服务进程；支持 OpenRouter 与 OpenAI-compatible 模型发现。
- `ModelPrice` 支持原币报价、汇率依据与冻结价目表；模型调用记录历史单价，防止历史账本漂移。
- `BillingTariff` 规则引擎支持互斥上下文/执行条件与确定性成本明细估算。

## 生产宿主

生产入口必须提供 PostgreSQL durable stores、受控 `ChatModel`、Embedding、对象存储 source resolver、权限解析、费用限额、telemetry 和健康检查。缺少数据库或 Provider 时启动失败，不能静默回退到内存实现、脚本模型或哈希向量。

真实知识摄入配置示例：

```bash
export EMBEDDING_API_KEY=...
export EMBEDDING_MODEL=...
export EMBEDDING_DIMENSION=1024
export EMBEDDING_TOKENIZER=cl100k-base   # text-embedding-3；通义文本向量改为 cjk-approx-v1
```

凭据只通过宿主 secret 注入，不写入配置样例、日志、trace 或 Git。

## 上线前验证

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.10.0-local"; publishM2'
```

随后使用独立 Maven consumer 验证 POM、资源、source/doc JAR，并在真实业务宿主中验证空库迁移、PDF/OCR、检索 ACL、引用、撤回、费用、失败重试和观测链路。只有宿主环境的容量、备份恢复、告警与故障演练完成后，才能声明生产就绪。
