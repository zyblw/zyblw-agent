package com.zyblw.agent.core

import zio.*

/** 可在运行时调整的模型工作点。
  *
  * 它只回答"这次调用走哪个已注册的 Provider、哪个模型、什么采样参数",不回答"凭据是什么"。凭据由部署在装配阶段 通过环境变量或宿主的密钥后端提供,因此**可以在已注册的 Provider
  * 之间热切换,但不能热增一个全新 Provider**—— 后者需要新凭据和新 HTTP 客户端,只能重启。这个边界不是妥协:让管理面能写入凭据就等于把业务库变成密钥库, 并连带承担静态加密、轮换、备份脱敏与
  * `pg_dump` 泄漏面,而这些都不是 Agent 框架该解决的问题。
  *
  * 覆盖是稀疏的:`None` 表示沿用 `AgentDefinition.modelSettings`,而不是"清空成 Provider 默认"。这一点很重要—— 一个 Agent
  * 的模型是它定义的一部分,部署级覆盖用于故障切换和成本调优,不应该在运维只想调温度时把模型也一起抹掉。
  *
  * @param provider
  *   覆盖 Provider 路由名;必须是装配时已注册的名称
  * @param model
  *   覆盖模型名
  * @param temperature
  *   覆盖采样温度
  * @param maxOutputTokens
  *   覆盖单次输出上限
  */
final case class ModelPolicy(
    provider: Option[String] = None,
    model: Option[String] = None,
    temperature: Option[Double] = None,
    maxOutputTokens: Option[Int] = None
):
  require(
    temperature.forall(value => value >= 0.0 && value <= 2.0 && value.isFinite),
    "模型温度必须位于 0.0..2.0"
  )
  require(maxOutputTokens.forall(value => value > 0 && value <= 1_000_000), "模型输出上限必须位于 1..1000000")
  require(provider.forall(_.trim.nonEmpty), "模型 Provider 覆盖不能为空字符串")
  require(model.forall(_.trim.nonEmpty), "模型名覆盖不能为空字符串")

  /** 是否存在任何覆盖;没有覆盖时调用方应保持 Agent 定义原样,而不是走一遍无意义的合并。 */
  def isEmpty: Boolean =
    provider.isEmpty && model.isEmpty && temperature.isEmpty && maxOutputTokens.isEmpty

  /** 把覆盖叠加到 Agent 自己的设置上。
    *
    * `toolChoice`、`providerOptions` 与 `metadata` 不参与覆盖:它们是 Agent 行为契约而不是部署工作点, 用一个部署级开关改掉某个 Agent 的 tool
    * choice 会让该 Agent 的行为与其定义不再一致。
    */
  def applyTo(settings: ModelSettings): ModelSettings =
    if isEmpty then settings
    else
      settings.copy(
        provider = provider.orElse(settings.provider),
        model = model.orElse(settings.model),
        temperature = temperature.orElse(settings.temperature),
        maxOutputTokens = maxOutputTokens.orElse(settings.maxOutputTokens)
      )

object ModelPolicy:
  /** 不做任何覆盖的基线。 */
  val default: ModelPolicy = ModelPolicy()

/** 同步返回当前生效模型工作点的解析器。
  *
  * 与 `ToolPolicySource`、`RetrievalPolicySource` 同一形状和同一理由:Runtime 在构造 `ChatRequest` 的纯表达式里
  * 读取工作点,把这个读取变成效果会迫使调用链改写却换不来任何额外保证——读取一个不可变引用本来就没有副作用。
  *
  * 实现必须保证 [[current]] 是无阻塞、无异常的引用读取。
  */
trait ModelPolicySource:
  /** 读取当前生效工作点。 */
  def current(): ModelPolicy

  /** 部署声明的价格表,用于把 token 用量折算为 `UsageSummary.estimatedCost`。
    *
    * 价格表放在这里而不是独立成第二个环境服务:它与工作点一样是"部署拥有的模型元数据,在每次调用路径上被读取",
    * 而为一张静态查询表再增加一个环境依赖,会让每个既有装配都多改一行却换不到任何隔离性。默认空表意味着费用 保持零,与 `UsageSummary.addModel` 的约定一致。
    */
  def prices: ModelPriceBook = ModelPriceBook.empty

object ModelPolicySource:
  /** 永远返回同一份工作点;未接入管理面覆盖时使用。 */
  def static(policy: ModelPolicy, priceBook: ModelPriceBook = ModelPriceBook.empty): ModelPolicySource =
    new ModelPolicySource:
      def current(): ModelPolicy          = policy
      override def prices: ModelPriceBook = priceBook

  /** 框架默认:完全沿用 Agent 定义,不做任何覆盖,不估算费用。 */
  val default: ModelPolicySource = static(ModelPolicy.default)

  /** 未接入管理面覆盖的部署使用的层。 */
  val defaultLayer: ULayer[ModelPolicySource] = ZLayer.succeed(default)

  /** 只声明价格表、不做任何覆盖的部署使用的层;成本看板可用而模型仍由 Agent 定义决定。 */
  def pricedLayer(priceBook: ModelPriceBook): ULayer[ModelPolicySource] =
    ZLayer.succeed(static(ModelPolicy.default, priceBook))
