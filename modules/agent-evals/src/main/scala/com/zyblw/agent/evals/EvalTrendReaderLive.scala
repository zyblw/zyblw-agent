package com.zyblw.agent.evals

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import zio.*

/** `EvalTrendReader` 在 `EvalTrendStore` 之上的只读适配器。
  *
  * 跟踪哪些趋势线由部署显式声明（构造时传入的 `tracked`），而不是从仓库里发现：`EvalTrendStore` 的发布契约没有 枚举方法，为管理台给已发布 trait
  * 增加抽象方法会让所有外部实现无法编译。显式声明同时更符合管理台的语义—— 首屏要展示的是运维承诺看护的少数几条发布门禁线，而不是数据库里碰巧存在的一切历史。
  *
  * 适配器留在 `agent-evals`：只有这里认识 `EvalSuiteSnapshot` 的维度结构，`agent-core` 的管理面 SPI 因此不必 依赖评测模块，HTTP
  * 层也不会因为挂载趋势路由而引入评测依赖。
  */
final class EvalTrendReaderLive(store: EvalTrendStore, tracked: Chunk[EvalTrendIdentity])
    extends EvalTrendReader:
  import EvalTrendReaderLive.*

  /** 返回本部署声明跟踪的趋势线，保持声明顺序，便于管理台稳定渲染。 */
  def suites: IO[AgentError, Chunk[EvalSuiteIdentityView]] = ZIO.succeed(tracked.map(identityView))

  /** 读取一条趋势线的最近若干数据点，按时间升序。
    *
    * 身份未出现在 `tracked` 时仍然允许查询：`suites` 是展示声明而不是授权边界，端点本身已经要求管理读权限。 但 `kind` 与 ID 字符集必须先通过校验——未知 `kind`
    * 无法映射到任何可比较的历史，静默返回空序列会让管理台 把"参数写错"显示成"这条线没有数据"。
    */
  def history(identity: EvalSuiteIdentityView, limit: Int): IO[AgentError, EvalTrendSeries] =
    for
      typed     <- identityOf(identity)
      _         <- EvalTrendIdentity.validate(typed)
      snapshots <- store.history(typed, limit.max(1).min(EvalTrendReader.MaxHistoryLimit))
    yield EvalTrendSeries(identityView(typed), snapshots.map(pointView))

object EvalTrendReaderLive:
  /** 校验声明并构造适配器。
    *
    * 在装配阶段而不是首次请求时校验部署声明：一个拼错的 suiteId 应该让启动失败，而不是让管理台在某个页签上 得到一个无法解释的 400。
    */
  def make(
      store: EvalTrendStore,
      tracked: Chunk[EvalTrendIdentity]
  ): IO[AgentError.InvalidConfiguration, EvalTrendReader] =
    val distinct = tracked.distinct
    ZIO
      .foreachDiscard(distinct)(EvalTrendIdentity.validate)
      .as(new EvalTrendReaderLive(store, distinct))

  /** 标准装配；`tracked` 来自部署配置。 */
  def layer(
      tracked: Chunk[EvalTrendIdentity]
  ): ZLayer[EvalTrendStore, AgentError.InvalidConfiguration, EvalTrendReader] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[EvalTrendStore](make(_, tracked)))

  /** 投影类型化身份为 provider-neutral 视图。 */
  def identityView(identity: EvalTrendIdentity): EvalSuiteIdentityView = EvalSuiteIdentityView(
    kind = identity.kind.toString,
    suiteId = identity.suiteId,
    datasetId = identity.datasetId,
    datasetVersion = identity.datasetVersion
  )

  /** 解析外部身份；未知 `kind` fail-closed。
    *
    * 比较忽略大小写，与 HTTP 层解析 Run 状态的约定一致；返回值使用枚举的规范拼写，因此响应里的 `kind` 始终是 前端可以原样回传的值。
    */
  def identityOf(view: EvalSuiteIdentityView): IO[AgentError.InvalidConfiguration, EvalTrendIdentity] =
    ZIO
      .fromOption(EvalSuiteKind.values.find(_.toString.equalsIgnoreCase(view.kind.trim)))
      .orElseFail(
        AgentError.InvalidConfiguration(s"eval-trend:unknown-suite-kind:${view.kind.trim.take(40)}")
      )
      .map(kind => EvalTrendIdentity(kind, view.suiteId.trim, view.datasetId.trim, view.datasetVersion.trim))

  /** 投影一次评测快照为趋势数据点。
    *
    * `dimensionScores` 与 `dimensionGates` 必须分别计算：分数是同名维度在全部用例上的算术平均，用于观察趋势；
    * 门禁是同名维度在全部用例上的合取，用于回答"这次能不能发布"。一个维度完全可以平均分很高而仍有个别用例 硬门禁失败，把二者合成一个数字会让管理台把一次真实的阻塞画成一条平滑曲线。
    */
  def pointView(snapshot: EvalSuiteSnapshot): EvalTrendPointView =
    val dimensions = snapshot.cases.flatMap(_.dimensions).groupBy(_.name)
    EvalTrendPointView(
      evaluationId = snapshot.metadata.evaluationId,
      harnessVersion = snapshot.metadata.harnessVersion,
      commitSha = snapshot.metadata.commitSha,
      provider = snapshot.metadata.provider,
      model = snapshot.metadata.model,
      finishedAtEpochMilli = snapshot.metadata.finishedAt.toEpochMilli,
      passed = snapshot.passed,
      passRate = snapshot.passRate,
      caseCount = snapshot.cases.length,
      dimensionScores =
        dimensions.map((name, values) => name -> values.map(_.score).sum / values.length.toDouble),
      dimensionGates = dimensions.map((name, values) => name -> values.forall(_.passed))
    )
