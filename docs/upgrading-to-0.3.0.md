# 进入 0.3.0 开发线：清库重建

> 状态：开发期破坏性重建说明
> 最后核验：2026-08-01

当前 `main` 不提供从 `0.2.x` 的源码、二进制、持久化 outcome 或 Flyway history 原地升级。`0.3.0` 尚未发布，目标是先把
Workflow durable wait 与后续 RAG lineage 契约收敛到正确形态，再建立正式兼容基线。

采用当前源码时：

1. 停止所有旧 Worker；保留需要审计的旧数据库备份，但不要让新代码读取旧表。
2. 创建空 schema 或新数据库，并使用默认专属 history table 执行
   `V001__zyblw_agent_0_3_baseline.sql`。
3. 重新导入允许保留的业务源文档；RAG 向量、FTS、chunk 和 eval 投影均视为可重建派生数据。
4. 为每个 Workflow 使用新的 definition version；不要恢复 `0.2.x` 的活跃 checkpoint、Prepared outcome 或 lease。
5. 为每个 durable definition 启动 `WorkflowWakeWorker.startScoped`；webhook 只调用幂等 `signal`，不要直接调用 `resume`。
6. 运行框架、PostgreSQL、Maven-local consumer 和实际业务契约测试后再接入开发环境。

旧数据库不是自动删除目标。备份、保留和最终清理由宿主负责人决定；框架只明确拒绝把旧 schema 伪装成新 baseline。
