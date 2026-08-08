package com.zyblw.agent.core

/** 单个 Provider+Model 的单价,按每百万 token 计。
  *
  * 用每百万 token 而不是每 token,是因为主流厂商都以这个粒度公布价格,直接照抄能避免运维在录入时自己做一次除法; 用 `BigDecimal` 而不是 `Double`,是因为这个数会累加进
  * `UsageSummary.estimatedCost` 并出现在成本看板上, 二进制浮点的累加误差在账单类数字上不可接受。
  *
  * @param inputPerMillionTokens
  *   未命中缓存的输入 token 单价
  * @param outputPerMillionTokens
  *   输出 token 单价
  * @param cachedInputPerMillionTokens
  *   命中提示缓存的输入 token 单价;`None` 表示该 Provider 不为缓存单独计价,按输入价计算
  * @param currency
  *   计价货币;同一价格表内必须一致
  */
final case class ModelPrice(
    inputPerMillionTokens: BigDecimal,
    outputPerMillionTokens: BigDecimal,
    cachedInputPerMillionTokens: Option[BigDecimal] = None,
    currency: String = "USD"
):
  require(inputPerMillionTokens >= 0, "输入单价不能为负数")
  require(outputPerMillionTokens >= 0, "输出单价不能为负数")
  require(cachedInputPerMillionTokens.forall(_ >= 0), "缓存输入单价不能为负数")
  require(currency.trim.nonEmpty, "计价货币不能为空")

  /** 估算一次调用的费用。
    *
    * `cachedInputTokens` 是 `inputTokens` 的子集,因此未命中缓存的部分是两者之差——把两个字段各自乘以单价再相加会 把缓存命中的 token 收费两次,这是照着 usage
    * 字段直觉实现时最容易踩的错。
    *
    * `reasoningOutputTokens` 同样是 `outputTokens` 的子集,并且主流厂商按普通输出 token 计费,因此这里刻意不为它 单独计价:再乘一次就是重复计费。
    */
  def estimate(usage: TokenUsage): BigDecimal =
    val cached      = usage.cachedInputTokens.min(usage.inputTokens).max(0L)
    val freshInput  = (usage.inputTokens - cached).max(0L)
    val cachedPrice = cachedInputPerMillionTokens.getOrElse(inputPerMillionTokens)
    val million     = BigDecimal(1_000_000)
    (BigDecimal(freshInput) * inputPerMillionTokens +
      BigDecimal(cached) * cachedPrice +
      BigDecimal(usage.outputTokens.max(0L)) * outputPerMillionTokens) / million

/** 部署声明的模型价格表。
  *
  * 框架**不内置**任何厂商价格。价格随时变化、随合同变化、随区域变化,把一份猜测的价目表编译进框架只会让成本看板 显示一个看起来精确但其实错误的数字,而运维没有任何线索知道它错了。缺失条目返回零,与
  * `UsageSummary.addModel` 的既有约定一致:未知费用保持零,不伪造账单事实。
  *
  * @param prices
  *   以 `(provider, model)` 为键的单价表
  */
final case class ModelPriceBook(prices: Map[(String, String), ModelPrice]):
  // 单一 estimatedCost 标量无法表达多币种,混币价格表会把不可比的金额直接相加。这必须在装配期挡住,
  // 而不是等到某张成本看板上出现一个既非美元也非人民币的数。
  require(
    prices.values.map(_.currency.trim.toUpperCase).toSet.size <= 1,
    "价格表不能混用多种计价货币"
  )

  /** 价格表的计价货币;空表返回 None。 */
  def currency: Option[String] = prices.values.headOption.map(_.currency)

  /** 精确查询单价;不做"同 Provider 其他模型"之类的近似回退。 */
  def price(provider: String, model: String): Option[ModelPrice] = prices.get(provider -> model)

  /** 估算费用;没有条目时返回零。 */
  def estimate(provider: String, model: String, usage: TokenUsage): BigDecimal =
    price(provider, model).fold(BigDecimal(0))(_.estimate(usage))

  /** 是否已声明任何单价;管理台用它区分"费用为零"与"没有价格表"。 */
  def isEmpty: Boolean = prices.isEmpty

object ModelPriceBook:
  /** 未声明价格表的部署;所有费用估算为零。 */
  val empty: ModelPriceBook = ModelPriceBook(Map.empty)

  /** 从 `(provider, model, price)` 三元组构造。 */
  def of(entries: (String, String, ModelPrice)*): ModelPriceBook =
    ModelPriceBook(entries.map((provider, model, price) => (provider, model) -> price).toMap)
