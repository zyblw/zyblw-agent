package com.zyblw.agent.integrations

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import com.zyblw.agent.rag.EmbeddingProviderDescriptor
import zio.*

/** 从已装配的 Provider 派生的模型目录。
  *
  * 目录**只反映真实装配**,不接受第二份"可用模型清单"配置。一份与装配分离的清单必然漂移,而它同时是运行时模型覆盖的 写入校验依据:清单多一项,运维就能存下一个让全线调用失败的组合;少一项,一个明明可用的
  * Provider 会被拒绝。
  *
  * 快照在装配期计算一次。路由拓扑、Provider 能力声明与凭据都只能在重启时变化(热增 Provider 需要新凭据和新 HTTP 客户端),因此每次读取都重新投影一遍只会在每个管理台请求上重复同样的纯计算。
  */
final class ModelCatalogLive private (
    snapshot: Chunk[ModelOptionView],
    routerDefaultProvider: String,
    embeddingSnapshot: Option[EmbeddingModelView]
) extends ModelCatalog:
  def options: UIO[Chunk[ModelOptionView]] = ZIO.succeed(snapshot)

  def defaultProvider: UIO[String] = ZIO.succeed(routerDefaultProvider)

  override def embedding: UIO[Option[EmbeddingModelView]] = ZIO.succeed(embeddingSnapshot)

object ModelCatalogLive:
  /** 构造目录快照。
    *
    * @param registry
    *   已校验的 Provider 声明与路由拓扑
    * @param priceBook
    *   部署价格表;**必须与传给 `RuntimeSettingsService` 的是同一份**,否则管理台展示的单价与成本看板实际使用的单价 会来自两张表
    * @param embedding
    *   向量化模型的只读描述;未装配 Embedding 时为 None
    */
  def make(
      registry: ProviderRegistry,
      priceBook: ModelPriceBook = ModelPriceBook.empty,
      embedding: Option[EmbeddingModelView] = None
  ): ModelCatalog =
    new ModelCatalogLive(
      optionsOf(registry, priceBook),
      registry.defaultProvider,
      embedding
    )

  /** 标准装配。 */
  def layer(
      priceBook: ModelPriceBook = ModelPriceBook.empty,
      embedding: Option[EmbeddingModelView] = None
  ): URLayer[ProviderRegistry, ModelCatalog] =
    ZLayer.fromFunction((registry: ProviderRegistry) => make(registry, priceBook, embedding))

  /** 把一个 Embedding 适配器描述投影为管理面视图。
    *
    * `switchable` 恒为 false 由契约固定,这里不提供任何覆盖入口:能在管理台保存成功却让整个知识库的既有向量失去 意义的开关,比没有开关危险得多。
    *
    * @param indexDimension
    *   知识库索引列的固定维度;与模型维度不一致时摄入会在写入前失败,管理台据此提前告警
    */
  def embeddingView(
      descriptor: EmbeddingProviderDescriptor,
      indexDimension: Option[Int] = None
  ): EmbeddingModelView =
    EmbeddingModelView(
      provider = descriptor.provider,
      model = descriptor.model,
      dimension = descriptor.dimension,
      indexDimension = indexDimension,
      switchable = false,
      immutableReason = EmbeddingModelView.DimensionLockedReason
    )

  /** 展开全部 Provider+Model 组合。
    *
    * 展开规则由 Provider 是否声明了模型清单决定:
    *   - 声明了清单:逐个模型产出条目,能力取该模型的独立声明。这是唯一能让管理台如实展示"同一 Provider 下 A 支持 视觉而 B 不支持"的形态。
    *   - 未声明清单:只产出一条部署默认模型。此时 `declaredModel = false`,能力回退到 Provider 级——框架内置适配器
    *     目前都属于这一类,它们的能力矩阵描述的是协议而不是单个模型。
    *
    * 结果按 `(provider, model)` 排序,使前端 diff 与快照测试稳定。
    */
  private def optionsOf(
      registry: ProviderRegistry,
      priceBook: ModelPriceBook
  ): Chunk[ModelOptionView] =
    registry.registrations
      .flatMap { registration =>
        val descriptor = registration.chatModel.descriptor
        val provider   = registration.provider
        val declared   = Chunk.fromIterable(descriptor.models.toList.sortBy(_._1))
        val entries    =
          if declared.nonEmpty then declared.map((model, capabilities) => (model, capabilities, true))
          else Chunk.single((registration.defaultModel, descriptor.capabilities, false))
        entries.map { (model, capabilities, fromDeclaredList) =>
          ModelOptionView(
            provider = provider,
            model = model,
            displayName = descriptor.displayName,
            protocol = descriptor.protocol,
            capabilities = capabilitiesView(capabilities),
            isDefaultProvider = provider == registry.defaultProvider,
            declaredModel = fromDeclaredList,
            credential = registration.credential,
            price = priceBook.price(provider, model).map(priceView)
          )
        }
      }
      .sortBy(option => (option.provider, option.model))

  /** 投影模型能力。
    *
    * 只映射管理面契约声明的位,`developerRole`、`audio` 等 SPI 内部能力不外泄:管理台不需要它们,而把 SPI 的每一个新 布尔位自动带到公开 API
    * 上会让一次内部演进变成一次契约变更。
    */
  private def capabilitiesView(capabilities: ModelCapabilities): ModelCapabilitiesView =
    ModelCapabilitiesView(
      toolCalls = capabilities.toolCalls,
      parallelToolCalls = capabilities.parallelToolCalls,
      strictToolSchema = capabilities.strictToolSchema,
      specificToolChoice = capabilities.specificToolChoice,
      vision = capabilities.vision,
      thinking = capabilities.thinking,
      streaming = capabilities.streaming,
      usageReporting = capabilities.usageReporting,
      maxInputTokens = capabilities.maxInputTokens,
      maxOutputTokens = capabilities.maxOutputTokens
    )

  /** 投影单价。
    *
    * 用 `toPlainString` 而不是 `toString`:后者会把很小的单价渲染成科学计数法,而线格式用字符串的全部意义就是让前端 原样展示金额。
    */
  private def priceView(price: ModelPrice): ModelPriceView = ModelPriceView(
    inputPerMillionTokens = price.inputPerMillionTokens.bigDecimal.toPlainString,
    outputPerMillionTokens = price.outputPerMillionTokens.bigDecimal.toPlainString,
    cachedInputPerMillionTokens = price.cachedInputPerMillionTokens.map(_.bigDecimal.toPlainString),
    currency = price.currency
  )
