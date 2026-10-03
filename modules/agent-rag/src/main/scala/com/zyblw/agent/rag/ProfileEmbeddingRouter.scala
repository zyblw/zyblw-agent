package com.zyblw.agent.rag

import zio.*

/** 按 Profile 选择查询向量模型。
  *
  * 换 Embedding 模型会得到一个新 Profile。蓝绿切换期间,active Profile 的向量仍由旧模型产生,而新模型只在构建中的 目标 Profile
  * 里使用。若查询一律用"当前配置的"模型,切换完成前的每次检索都会因向量身份不符而失败;若一律用旧模型, 切换前对目标 Profile
  * 的验收探测又会失败。因此查询模型必须在 Profile 解析**之后**按 Profile 选择:
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
    * 候选的 Profile id 用与构建模型相同的切块策略与 enricher 计算,只替换 Embedding 身份——这与 `KnowledgeIndexer.buildSpec` 的推导
    * 规则一致,所以旧 Profile 能被准确认出。
    */
  def make(
      build: EmbeddingModel,
      buildSpec: IndexBuildSpec,
      candidates: Iterable[EmbeddingModel]
  ): ProfileEmbeddingRouter =
    val byProfile = candidates.iterator
      .map(model => profileOf(model, buildSpec) -> model)
      .toMap
      .updated(IndexProfileIds.fromSpec(buildSpec), build)
    new ProfileEmbeddingRouter:
      def forProfile(profile: Option[IndexProfileId]): UIO[EmbeddingModel] =
        ZIO.succeed(profile.flatMap(byProfile.get).getOrElse(build))

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
