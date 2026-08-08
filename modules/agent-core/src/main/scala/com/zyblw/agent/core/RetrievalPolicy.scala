package com.zyblw.agent.core

import zio.*

/** 可在运行时调整的检索工作点。
  *
  * 这里只包含"能在不重启进程的前提下安全改变"的三个量。硬上限（`maxTopK`、`maxQueryCodePoints`）刻意留在部署基线 `RagApplicationConfig`
  * 里：基线定义安全边界，覆盖层只在边界内移动工作点。让管理台能提高上限就等于让它自己解除自己的限制。
  *
  * @param topK
  *   调用方未显式指定时的检索条数
  * @param minimumScore
  *   注入 Context 前 seed 命中的最低得分；0 表示不过滤
  * @param rerankEnabled
  *   是否执行重排阶段
  */
final case class RetrievalPolicy(
    topK: Int = 5,
    minimumScore: Double = 0.0,
    rerankEnabled: Boolean = true
):
  require(topK > 0 && topK <= 100, "检索 topK 必须位于 1..100")
  require(
    minimumScore >= 0.0 && minimumScore <= 1.0 && minimumScore.isFinite,
    "检索最低得分必须位于 0.0..1.0"
  )

object RetrievalPolicy:
  /** 与 `RagApplicationConfig` 默认值一致的基线。 */
  val default: RetrievalPolicy = RetrievalPolicy()

/** 同步返回当前生效检索工作点的解析器。
  *
  * 与 `ToolPolicySource` 同一形状和同一理由：检索路径在纯表达式里读取 topK 与阈值，把这些读取变成效果会迫使 `DefaultRetriever` 的校验函数全部改写成 for
  * 推导，却换不来任何额外保证——读取一个不可变 `RetrievalPolicy` 引用本来就是无副作用的。
  *
  * 实现必须保证 [[current]] 是无阻塞、无异常的引用读取。覆盖通过替换被引用的不可变值生效，而不是原地修改对象。
  */
trait RetrievalPolicySource:
  /** 读取当前生效工作点。 */
  def current(): RetrievalPolicy

object RetrievalPolicySource:
  /** 永远返回同一份工作点；未接入管理面覆盖时使用。 */
  def static(policy: RetrievalPolicy): RetrievalPolicySource = new RetrievalPolicySource:
    def current(): RetrievalPolicy = policy

  /** 框架默认工作点。 */
  val default: RetrievalPolicySource = static(RetrievalPolicy.default)

  /** 从已装配的 `RetrievalPolicy` 构造静态解析器。 */
  val staticLayer: URLayer[RetrievalPolicy, RetrievalPolicySource] =
    ZLayer.fromFunction((policy: RetrievalPolicy) => static(policy))
