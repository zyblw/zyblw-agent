# 0.9.0 全新安装

> 状态：当前唯一安装基线
> 最后核验：2026-09-13

`0.9.0` 只支持全新数据库。不要导入旧 Flyway history、旧 Run、旧 embedding cache 或旧知识索引；业务资料应按当前 tokenizer、Embedding、切分和 ACL 规则重新摄入。

## 数据库

1. 创建空 PostgreSQL 数据库并安装 `pg_trgm` 与 `vector`。
2. 调用 `AgentPostgresMigrations.migrateCoreAndKnowledge1024`。
3. 确认核心 history 只有 `V001__zyblw_agent_0_9_baseline.sql`，知识 history 只有 `V001__agent_knowledge_0_9_baseline.sql`。
4. 知识 schema 固定 1024 维；更换 Embedding 模型、维度或 strategyId 时创建新索引版本。
5. 结构探针失败时修正目标数据库或重新创建空库，不执行 Flyway repair/baseline。

## 检索与引用

- `RetrievalMode` 支持 `Hybrid`、`VectorOnly`、`LexicalOnly`、`Phrase`。
- `RetrievalFilter` 在 ACL 之后、打分之前约束 document、chunk、page、heading 和 metadata。
- `knowledge_search` 与 `knowledge_fetch` 是正式只读工具；tenant 和 permissions 只能来自可信宿主上下文。
- `AgentState` schemaVersion 7 保存有界 citations 与 retrievalEvidence。
- 文档提取、chunk、embedding、rerank、回答与 citation 必须记录同一条 retrieval lineage。

## 生产宿主

生产入口必须提供 PostgreSQL durable stores、受控 `ChatModel`、Embedding、对象存储 source resolver、权限解析、费用限额、telemetry 和健康检查。缺少数据库或 Provider 时启动失败，不能静默回退到内存实现、脚本模型或哈希向量。

框架示例可以用 `ZYBLW_AGENT_RUNTIME_MODE=contract` 运行确定性测试；该模式不得进入生产部署。真实知识摄入至少配置：

```bash
export EMBEDDING_API_KEY=...
export EMBEDDING_MODEL=...
export EMBEDDING_DIMENSION=1024
```

凭据只通过宿主 secret 注入，不写入配置样例、日志、trace 或 Git。

## 上线前验证

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.9.0-local"; publishM2'
```

随后使用独立 Maven consumer 验证 POM、资源、source/doc JAR，并在真实业务宿主中验证空库迁移、PDF/OCR、检索 ACL、引用、撤回、费用、失败重试和观测链路。只有宿主环境的容量、备份恢复、告警与故障演练完成后，才能声明生产就绪。
