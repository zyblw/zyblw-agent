package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 一个已注册 Provider 的凭据状态。
  *
  * 只报告"就位与否"和"来自哪个引用",**永不报告值**。管理台需要回答的问题是"我切到这个 Provider 会不会因为缺凭据 而全线失败",这个问题不需要看到 Key。
  *
  * @param present
  *   装配时是否解析到非空凭据
  * @param reference
  *   凭据来源的可展示引用,例如 `env:DEEPSEEK_API_KEY`;不含值
  */
final case class ModelCredentialStatus(present: Boolean, reference: String) derives JsonCodec

/** 模型能力的线格式投影。
  *
  * 单独一个 view 而不是直接序列化 `ModelCapabilities`,是为了让管理台契约与模型 SPI 解耦:SPI 增加一个内部能力位 不应该自动出现在公开的管理 API 上。
  */
final case class ModelCapabilitiesView(
    toolCalls: Boolean,
    parallelToolCalls: Boolean,
    strictToolSchema: Boolean,
    specificToolChoice: Boolean,
    vision: Boolean,
    thinking: Boolean,
    streaming: Boolean,
    usageReporting: Boolean,
    maxInputTokens: Option[Long],
    maxOutputTokens: Option[Long]
) derives JsonCodec

/** 目录中的一个可选模型。
  *
  * @param provider
  *   Provider 路由名,与 `ModelSettings.provider` 同一命名空间
  * @param model
  *   模型名
  * @param displayName
  *   Provider 的可展示名称
  * @param protocol
  *   wire 协议标识,例如 `openai-chat-completions`
  * @param capabilities
  *   该 Provider+Model 的能力
  * @param isDefaultProvider
  *   是否为路由器的默认 Provider
  * @param declaredModel
  *   模型名是否来自 Provider 声明的模型清单;`false` 表示它只是该 Provider 的部署默认模型,能力回退到 Provider 级
  * @param credential
  *   凭据状态
  * @param price
  *   该模型在部署价格表中的单价描述;`None` 表示价格表未覆盖它,费用将估算为零
  */
final case class ModelOptionView(
    provider: String,
    model: String,
    displayName: String,
    protocol: String,
    capabilities: ModelCapabilitiesView,
    isDefaultProvider: Boolean,
    declaredModel: Boolean,
    credential: ModelCredentialStatus,
    price: Option[ModelPriceView]
) derives JsonCodec

/** 单价的线格式投影;金额用字符串,避免 JSON number 在前端被读成有精度损失的双精度。 */
final case class ModelPriceView(
    inputPerMillionTokens: String,
    outputPerMillionTokens: String,
    cachedInputPerMillionTokens: Option[String],
    currency: String
) derives JsonCodec

/** 向量化模型的只读描述。
  *
  * 它刻意**不提供切换入口**。维度被 Flyway 迁移固定(当前为 1024),而一份索引的向量只能与生成它的模型比较—— 换模型等于让整个知识库的既有向量失去意义。给管理台一个能保存成功却悄悄让 RAG
  * 召回质量崩塌的开关,比不给 开关危险得多;真正需要换模型的部署必须走"新维度迁移 + 全量重新摄入"的运维流程。
  *
  * @param provider
  *   Embedding Provider 标识
  * @param model
  *   Embedding 模型名
  * @param dimension
  *   模型输出维度
  * @param indexDimension
  *   知识库索引列的固定维度;与 `dimension` 不一致时摄入会在写入前失败
  * @param switchable
  *   恒为 false;保留字段使前端不必硬编码这一约束
  * @param immutableReason
  *   面向运维的解释,直接展示在管理台上
  */
final case class EmbeddingModelView(
    provider: String,
    model: String,
    dimension: Int,
    indexDimension: Option[Int],
    switchable: Boolean,
    immutableReason: String
) derives JsonCodec

object EmbeddingModelView:
  /** 框架对"为什么不能在管理台换 Embedding 模型"的统一说明。 */
  val DimensionLockedReason: String =
    "向量维度由知识库迁移固定,且既有向量只能与生成它的模型比较。更换 Embedding 模型需要执行新维度迁移并全量重新摄入,不能在运行时切换。"

/** 管理台模型页所需的完整快照。
  *
  * @param options
  *   全部已注册的 Provider+Model 组合
  * @param defaultProvider
  *   路由器的默认 Provider
  * @param effectiveProvider
  *   叠加运行时覆盖后实际生效的 Provider;`None` 表示沿用各 Agent 定义
  * @param effectiveModel
  *   叠加运行时覆盖后实际生效的模型名;`None` 表示沿用各 Agent 定义
  * @param embedding
  *   向量化模型的只读描述
  * @param priceCurrency
  *   价格表货币;`None` 表示未声明价格表,成本估算恒为零
  * @param pricedOptionCount
  *   价格表覆盖到的模型数,用于提示"成本看板只对部分模型有效"
  */
final case class ModelCatalogView(
    options: Chunk[ModelOptionView],
    defaultProvider: String,
    effectiveProvider: Option[String],
    effectiveModel: Option[String],
    embedding: Option[EmbeddingModelView],
    priceCurrency: Option[String],
    pricedOptionCount: Int
) derives JsonCodec

/** 一次连通性探活的请求。 */
final case class ModelProbeRequest(provider: String, model: Option[String] = None) derives JsonCodec

/** 一次连通性探活的结果。
  *
  * 不返回模型输出正文:探活只需要证明"凭据有效、路由可达、能力协商通过"。回显模型输出会让一个只需要 `agent:admin:debug` 的端点变成一个可以向任意 Provider 提问并读回答案的通道。
  *
  * @param provider
  *   实际路由到的 Provider
  * @param model
  *   实际使用的模型名
  * @param succeeded
  *   是否完成一次成功调用
  * @param latencyMillis
  *   端到端耗时
  * @param inputTokens
  *   本次探活消耗的输入 token
  * @param outputTokens
  *   本次探活消耗的输出 token
  * @param failureCode
  *   失败时的稳定分类码,不含 Provider 原始响应
  */
final case class ModelProbeResult(
    provider: String,
    model: String,
    succeeded: Boolean,
    latencyMillis: Long,
    inputTokens: Long,
    outputTokens: Long,
    failureCode: Option[String]
) derives JsonCodec

/** 已注册模型的目录 SPI。
  *
  * 它是**写入路径的校验依据**,不只是一个展示接口:允许把未注册的 provider/model 存进运行时覆盖,等于让一次拼写 错误把整个部署的每一次模型调用变成
  * `ProviderNotFound`,而管理台会显示"保存成功"。
  */
trait ModelCatalog:
  /** 列出全部已注册组合。 */
  def options: UIO[Chunk[ModelOptionView]]

  /** 路由器默认 Provider。 */
  def defaultProvider: UIO[String]

  /** 向量化模型的只读描述;未装配 Embedding 时为 None。 */
  def embedding: UIO[Option[EmbeddingModelView]] = ZIO.none

object ModelCatalog:
  /** 未装配目录时的 fail-closed 实现。
    *
    * 返回空清单,因此 [[validateOverride]] 会拒绝任何模型覆盖。这比"不校验直接放行"更安全:没有目录就没有依据判断 一个 provider 名是否可路由,而放行的代价是全线
    * `ProviderNotFound`。
    */
  val empty: ModelCatalog = new ModelCatalog:
    def options: UIO[Chunk[ModelOptionView]] = ZIO.succeed(Chunk.empty)
    def defaultProvider: UIO[String]         = ZIO.succeed("")

  /** 未装配目录时的层。 */
  val emptyLayer: ULayer[ModelCatalog] = ZLayer.succeed(empty)

  /** 校验一份模型覆盖是否指向已注册的组合。
    *
    * 规则:
    *   - 没有 provider/model 覆盖时无需校验(温度等采样参数对任何模型都有意义)。
    *   - 只覆盖 provider 时,该 provider 必须存在。
    *   - 覆盖了 model 时,`(生效 provider, model)` 必须存在于目录中。生效 provider 是覆盖值或默认值——只校验 model 名本身会放过"provider A 的模型名配到
    *     provider B 上"这种必然失败的组合。
    *
    * @return
    *   全部错误消息;空表示通过
    */
  def validateOverride(
      options: Chunk[ModelOptionView],
      defaultProvider: String,
      provider: Option[String],
      model: Option[String]
  ): Chunk[String] =
    if provider.isEmpty && model.isEmpty then Chunk.empty
    else if options.isEmpty then Chunk.single("未装配模型目录,无法校验 Provider 与模型是否可路由,因此拒绝模型覆盖")
    else
      val target    = provider.map(_.trim).getOrElse(defaultProvider)
      val providers = options.map(_.provider).toSet
      if !providers.contains(target) then
        Chunk.single(
          s"Provider $target 未注册;已注册: ${providers.toList.sorted.mkString(", ")}"
        )
      else
        model.map(_.trim) match
          case None       => Chunk.empty
          case Some(name) =>
            val available = options.filter(_.provider == target).map(_.model).toSet
            if available.contains(name) then Chunk.empty
            else
              Chunk.single(
                s"Provider $target 未注册模型 $name;可用: ${available.toList.sorted.mkString(", ")}"
              )

/** 管理面模型治理服务。
  *
  * 只读目录 + 探活。模型的**切换**不在这里,而是走 `RuntimeSettingsService` 的覆盖写入路径,以便复用它已有的
  * 乐观锁、审计历史与跨副本刷新;为模型再造一套版本化写入会产生两份可能互相矛盾的配置事实。
  */
trait ModelAdminService:
  /** 读取管理台模型页快照。 */
  def catalog: IO[AgentError, ModelCatalogView]

  /** 对一个已注册组合执行一次最小连通性探活。 */
  def probe(request: ModelProbeRequest): IO[AgentError, ModelProbeResult]
