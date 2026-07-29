package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.memory.{MemoryStore, RunCommandStore, RunStore, RunSubmissionStore}
import com.zyblw.agent.rag.{EmbeddingCacheStore, EmbeddingQuotaStore, KnowledgeIndexStore, VectorStore}
import com.zyblw.agent.evals.EvalTrendStore
import com.zyblw.agent.workflow.WorkflowCheckpointStore
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

  /** 在控制面基础上加入长期 MemoryStore。
    *
    * 不默认加入 pgvector 知识表，因为它是需要显式选择固定维度和 optional migration 的独立部署能力。
    */
  val layerWithMemory: URLayer[DataSource, RunStore & RunCommandStore & RunSubmissionStore & MemoryStore] =
    layer ++ PostgresMemoryStore.layer

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
    * 两个 Adapter 共享同一个 DataSource、固定向量维度和 `agent_knowledge_chunks` 正式快照： `KnowledgeIndexStore` 负责
    * Building→stage→activate，`VectorStore` 只查询 active 发布结果。 业务仍需显式执行对应维度的 optional pgvector migration。
    */
  def knowledge(
      dimension: Int,
      hybridConfig: PostgresHybridSearchConfig = PostgresHybridSearchConfig()
  ): URLayer[DataSource, KnowledgeIndexStore & VectorStore] =
    PostgresKnowledgeIndexStore.layer(dimension) ++ PostgresPgVectorStore.layer(dimension, hybridConfig)

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
