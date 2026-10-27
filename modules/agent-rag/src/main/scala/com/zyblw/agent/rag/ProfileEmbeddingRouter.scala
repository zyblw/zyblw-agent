package com.zyblw.agent.rag

import zio.*

/** 按 Profile 选择查询向量模型。
  *
  * 换 Embedding 模型会得到一个新 Profile。蓝绿切换期间,active Profile 的向量仍由旧模型产生,而新模型只在构建中的 目标 Profile
  * 里使用。若查询一律用"当前配置的"模型,切换完成前的每次检索都会因向量身份不符而失败;若一律用旧模型, 切换前对目标 Profile 的验收探测又会失败。因此查询模型必须在 Profile 解析**之后**按
  * Profile 选择:
  *   - pinned/active Profile 是构建 Profile → 构建模型
  *   - 是已登记的旧模型 Profile → 对应旧模型
  *   - 其它(未知 Profile 或尚无 active) → 构建模型;向量身份断言仍会对真正不匹配的情况 fail-closed
  */
trait ProfileEmbeddingRouter:
  def forProfile(profile: Option[IndexProfileId]): UIO[EmbeddingModel]

object ProfileEmbeddingRouter:
  /** 只有一个模型的部署。 */
  def single(model: EmbeddingModel): ProfileEmbeddingRouter =
    new ProfileEmbeddingRouter:
      def forProfile(profile: Option[IndexProfileId]): UIO[EmbeddingModel] = ZIO.succeed(model)

  /** 由构建模型与若干候选(通常是此前使用过、仍保留连接的模型)组成路由。
    *
    * 候选的 Profile id 默认用与构建模型相同的切块策略与 enricher 计算,只替换 Embedding 身份——这与 `KnowledgeIndexer.buildSpec` 的推导
    * 规则一致;若蓝绿迁移同时变更了切块策略、enricher 或 documentInstruction,可通过 `historicalSpecs` 或 `explicitBindings` 登记历史
    * Profile 绑定。
    */
  def make(
      build: EmbeddingModel,
      buildSpec: IndexBuildSpec,
      candidates: Iterable[EmbeddingModel],
      historicalSpecs: Iterable[(IndexBuildSpec, EmbeddingModel)] = Nil,
      explicitBindings: Map[IndexProfileId, EmbeddingModel] = Map.empty,
      store: Option[KnowledgeIndexStore] = None
  ): ProfileEmbeddingRouter =
    val allModels   = Chunk(build) ++ Chunk.fromIterable(candidates)
    val byCandidate = candidates.iterator
      .map(model => profileOf(model, buildSpec) -> model)
      .toMap
    val byHistoricalSpec = historicalSpecs.iterator.map { case (spec, model) =>
      IndexProfileIds.fromSpec(spec) -> model
    }.toMap
    val byProfile = (byCandidate ++ byHistoricalSpec ++ explicitBindings)
      .updated(IndexProfileIds.fromSpec(buildSpec), build)
    new ProfileEmbeddingRouter:
      def forProfile(profile: Option[IndexProfileId]): UIO[EmbeddingModel] =
        profile match
          case None     => ZIO.succeed(build)
          case Some(id) =>
            byProfile.get(id) match
              case Some(matched) => ZIO.succeed(matched)
              case None          =>
                store match
                  case None             => ZIO.succeed(build)
                  case Some(indexStore) =>
                    indexStore.registeredProfiles
                      .map { persisted =>
                        persisted
                          .collectFirst {
                            case (persistedId, spec) if persistedId == id =>
                              allModels.find(model => spec.matches(model.descriptor.denseDescriptor))
                          }
                          .flatten
                          .getOrElse(build)
                      }
                      .orElseSucceed(build)

  /** 将已持久化的 `(IndexProfileId, IndexBuildSpec)` 匹配到可用向量模型列表，并返回匹配绑定与缺失模型列表。 */
  def bindPersistedSpecs(
      persisted: Iterable[(IndexProfileId, IndexBuildSpec)],
      models: Iterable[EmbeddingModel]
  ): (Map[IndexProfileId, EmbeddingModel], Chunk[(IndexProfileId, IndexBuildSpec)]) =
    val modelList = Chunk.fromIterable(models)
    val bindings  = Map.newBuilder[IndexProfileId, EmbeddingModel]
    val missing   = ChunkBuilder.make[(IndexProfileId, IndexBuildSpec)]()
    persisted.foreach { case (profileId, spec) =>
      modelList.find(model => spec.matches(model.descriptor.denseDescriptor)) match
        case Some(matched) => bindings += (profileId -> matched)
        case None          => missing += (profileId  -> spec)
    }
    bindings.result() -> missing.result()

  /** 候选模型在相同切块策略下对应的 Profile id。 */
  def profileOf(model: EmbeddingModel, buildSpec: IndexBuildSpec): IndexProfileId =
    IndexProfileIds.fromSpec(
      IndexBuildSpec.of(
        model.descriptor.denseDescriptor,
        buildSpec.indexingStrategy,
        tokenizerId = model.capabilities.tokenizerId.getOrElse(IndexBuildSpec.UndeclaredTokenizer),
        documentInstruction = buildSpec.documentInstruction,
        enricher = buildSpec.enricher
      )
    )
