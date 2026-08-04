package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.memory.{MemoryStore, RunCommandStore, RunStore, RunSubmissionStore}
import com.zyblw.agent.rag.{EmbeddingCacheStore, EmbeddingQuotaStore, KnowledgeIndexStore, VectorStore}
import com.zyblw.agent.evals.EvalTrendStore
import com.zyblw.agent.workflow.{WorkflowCheckpointStore, WorkflowExecutionStore}
import javax.sql.DataSource
import zio.*
import zio.json.JsonCodec

/** Agent 耐久控制面的推荐 PostgreSQL 组合层。
  *
  * 业务接入方通常不应分别遗漏某个 Adapter：RunStore 保存事实状态，RunCommandStore 负责租约调度，RunSubmissionStore 保证新建 Run
  * 的四事实原子提交。该层让三者共享同一个宿主 DataSource；它不会私自创建第二个连接池。
  *
  * 使用示例：
  * {{{
  * val persistence: URLayer[DataSource, RunStore & RunCommandStore & RunSubmissionStore] =
  *   PostgresAgentPersistence.layer
  * }}}
  */
object PostgresAgentPersistence:
  /** 同时暴露 Runtime、WorkerHost 和 AgentCommandService 所需的三个持久化 SPI。 */
  val layer: URLayer[DataSource, RunStore & RunCommandStore & RunSubmissionStore] =
    PostgresRunStore.layer ++ PostgresRunCommandStore.layer ++ PostgresRunSubmissionStore.layer

  /** 自动迁移后再构造核心持久化 Adapter 的便捷生产层。
    *
    * 该层只在 ZLayer 构建阶段执行一次 Flyway migrate/validate 和关键表探针；失败会阻止应用及 Worker 启动，绝不回退到内存。 已由平台统一执行 migration
    * 的大型宿主可继续使用 [[layer]]，避免应用账号持有 DDL 权限。
    */
  val migratedLayer: RLayer[DataSource, RunStore & RunCommandStore & RunSubmissionStore] =
    ZLayer.fromZIOEnvironment {
      for
        dataSource <- ZIO.service[DataSource]
        _          <- AgentPostgresMigrations.migrate(dataSource)
      yield ZEnvironment[RunStore](PostgresRunStore(dataSource)) ++
        ZEnvironment[RunCommandStore](PostgresRunCommandStore(dataSource)) ++
        ZEnvironment[RunSubmissionStore](PostgresRunSubmissionStore(dataSource))
    }

  /** 在控制面基础上加入长期 MemoryStore。
    *
    * 不默认加入 pgvector 知识表，因为它是需要显式选择固定维度和 optional migration 的独立部署能力。
    */
  val layerWithMemory: URLayer[DataSource, RunStore & RunCommandStore & RunSubmissionStore & MemoryStore] =
    layer ++ PostgresMemoryStore.layer

  /** 与 [[migratedLayer]] 相同，但同时装配长期记忆。 */
  val migratedLayerWithMemory: RLayer[
    DataSource,
    RunStore & RunCommandStore & RunSubmissionStore & MemoryStore
  ] =
    ZLayer.fromZIOEnvironment {
      for
        dataSource <- ZIO.service[DataSource]
        _          <- AgentPostgresMigrations.migrate(dataSource)
      yield ZEnvironment[RunStore](PostgresRunStore(dataSource)) ++
        ZEnvironment[RunCommandStore](PostgresRunCommandStore(dataSource)) ++
        ZEnvironment[RunSubmissionStore](PostgresRunSubmissionStore(dataSource)) ++
        ZEnvironment[MemoryStore](PostgresMemoryStore(dataSource))
    }

  /** Embedding 治理持久化组合层。
    *
    * 该层只提供 Cache/Quota Store，不悄悄构造具体 Provider 或 `GovernedEmbeddingService`。业务应用仍需显式选择 模型、租户配额和缓存策略，再把这两个
    * Store 交给治理门面；这种拆分避免“引入 persistence 就立即调用模型”。
    *
    * 使用示例：
    * {{{
    * val stores: URLayer[DataSource, EmbeddingCacheStore & EmbeddingQuotaStore] =
    *   PostgresAgentPersistence.embeddingGovernance
    * }}}
    */
  val embeddingGovernance: URLayer[DataSource, EmbeddingCacheStore & EmbeddingQuotaStore] =
    PostgresEmbeddingCacheStore.layer ++ PostgresEmbeddingQuotaStore.layer

  /** 版本化知识摄取与 hybrid retrieval 的推荐同源组合层。
    *
    * 两个 Adapter 共享同一个 DataSource、固定向量维度和 `zyblw_agent_knowledge.agent_knowledge_chunks` 正式快照：
    * `KnowledgeIndexStore` 负责 Building→stage→activate，`VectorStore` 只查询 active 发布结果。 业务仍需显式执行对应维度的 optional
    * pgvector migration。
    */
  def knowledge(
      dimension: Int,
      hybridConfig: PostgresHybridSearchConfig = PostgresHybridSearchConfig()
  ): URLayer[DataSource, KnowledgeIndexStore & VectorStore] =
    PostgresKnowledgeIndexStore.layer(dimension) ++ PostgresPgVectorStore.layer(dimension, hybridConfig)

  /** 0.4 的一站式 1536 维知识库层：在空库自动创建/校验 vector 扩展和知识表，再暴露版本摄取与检索 Store。
    *
    * 维度被 migration 固定为 1536，因此此入口不接受运行时 dimension，消除“ZLayer 配置与物理列不一致”的无效状态。 如果生产数据库由 DBA/部署任务负责 DDL，请先显式调用
    * `migrateKnowledge1536`，运行进程再使用 [[knowledge]]。
    */
  def migratedKnowledge1536(
      hybridConfig: PostgresHybridSearchConfig = PostgresHybridSearchConfig()
  ): RLayer[DataSource, KnowledgeIndexStore & VectorStore] =
    ZLayer.fromZIOEnvironment {
      for
        dataSource <- ZIO.service[DataSource]
        _          <- AgentPostgresMigrations.migrateKnowledge1536(dataSource)
      yield ZEnvironment[KnowledgeIndexStore](PostgresKnowledgeIndexStore(dataSource, 1536)) ++
        ZEnvironment[VectorStore](PostgresPgVectorStore(dataSource, 1536, hybridConfig))
    }

  /** 生产评测趋势仓库。
    *
    * 该层只保存已经脱敏的 `EvalSuiteSnapshot`，不会运行 Eval、调用模型或自动决定发布。CI/发布任务应显式调用
    * `EvalReleaseGate.evaluateAndAppend`，再依据返回的 `passed` 决定是否放行。
    */
  val evalTrends: URLayer[DataSource, EvalTrendStore] =
    PostgresEvalTrendStore.layer

  /** 声明式 Workflow 的耐久 checkpoint。
    *
    * 状态类型必须提供 `JsonCodec`；完整快照会经过容量、checksum、identity 和单调 step 校验。该层不包含 Workflow Engine 或分布式 lease。
    */
  def workflowCheckpoints[S: JsonCodec: Tag]: URLayer[DataSource, WorkflowCheckpointStore[S]] =
    PostgresWorkflowCheckpointStore.layer[S]

  /** Workflow 的推荐生产耐久层。
    *
    * 同一 Adapter 在一个事务中提交节点 execution ledger、Prepared outcome 与 checkpoint，并通过 owner/token/generation/expiry
    * fencing 拒绝迟到 worker。
    */
  def workflowExecutions[S: JsonCodec: Tag]: URLayer[DataSource, WorkflowExecutionStore[S]] =
    PostgresWorkflowCheckpointStore.executionLayer[S]

  /** 控制面与评测发布事实源的常用组合。
    *
    * 二者共享宿主连接池，但没有跨表事务耦合：Run 执行与离线 Eval 属于不同生命周期，强行放在一个事务中反而会制造 长事务和不必要的锁竞争。
    */
  val layerWithEvalTrends: URLayer[
    DataSource,
    RunStore & RunCommandStore & RunSubmissionStore & EvalTrendStore
  ] = layer ++ evalTrends

  /** 控制面、长期记忆与 Embedding 治理的常用完整组合；仍不包含固定维度的 optional pgvector 知识索引。
    */
  val layerWithMemoryAndEmbeddingGovernance: URLayer[
    DataSource,
    RunStore & RunCommandStore & RunSubmissionStore & MemoryStore & EmbeddingCacheStore & EmbeddingQuotaStore
  ] = layerWithMemory ++ embeddingGovernance
