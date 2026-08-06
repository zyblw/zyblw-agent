package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.{ApprovalPolicy, ToolPolicyConfig, ToolPolicySource}
import java.util.concurrent.atomic.AtomicReference
import zio.*
import zio.json.*

/** 一项配置在被覆盖后何时真正生效。
  *
  * 管理台必须如实告诉运维“改完是否立刻起作用”，否则一个看似成功的保存会让人误以为限制已经收紧。 三个取值对应框架里三种真实的生效边界，不是估计值。
  */
enum RuntimeSettingApplies derives JsonCodec:
  /** 下一次工具执行即生效；覆盖写入后无需重启或新建 Run。 */
  case Immediate

  /** 只影响此后新建的 Run。既有 Run 在创建时已把该值冻结进 `AgentState`，改动它会让恢复语义与创建时不一致。 */
  case NextRun

  /** 需要重启进程。该值在装配 ZLayer 时被固化为不可变资源（例如信号量许可数）。 */
  case Restart

/** 可在运行时安全覆盖的有界配置集合。
  *
  * 这是一个**白名单**：只有列在这里的字段能被管理台改写。故意不做成“任意 key-value 覆盖”，原因是配置面一旦变成自由
  * 表单，就等于给管理台开了一个绕过编译期契约的后门，任何拼写错误都会静默失效，任何新字段都会缺少校验。
  *
  * 所有字段都是 `Option`：`None` 表示“沿用部署基线”，而不是“设为默认值”。这样覆盖层始终是基线之上的稀疏补丁， 删除一项覆盖与从未设置过它完全等价。
  *
  * @param toolAllowedTools
  *   工具白名单全量替换；空集合表示禁用全部工具（与 `ToolPolicyConfig` 的 fail-closed 语义一致）
  * @param toolDeniedTools
  *   工具黑名单全量替换；黑名单优先于白名单
  * @param toolDefaultTimeoutMillis
  *   单次工具执行超时
  * @param toolMaxResultBytes
  *   单次工具结果的 UTF-8 JSON 字节上限
  * @param toolApprovalPolicy
  *   审批策略；取值 `never` / `risk-based` / `always`
  * @param toolMaxCallsPerRun
  *   单个 Run 的工具调用总数上限；只影响新建 Run
  * @param toolMaxCallsPerStep
  *   单步工具调用数上限
  * @param retrievalTopK
  *   RAG 检索默认返回条数
  * @param retrievalMinimumScore
  *   注入 Context 前的最低检索得分
  * @param rerankEnabled
  *   是否启用重排
  * @param modelProvider
  *   覆盖所有 Agent 的 Provider 路由名；必须是装配时已注册的名称
  * @param modelName
  *   覆盖所有 Agent 的模型名
  * @param modelTemperature
  *   覆盖采样温度
  * @param modelMaxOutputTokens
  *   覆盖单次输出 token 上限
  */
final case class RuntimeOverrides(
    toolAllowedTools: Option[Set[String]] = None,
    toolDeniedTools: Option[Set[String]] = None,
    toolDefaultTimeoutMillis: Option[Long] = None,
    toolMaxResultBytes: Option[Long] = None,
    toolApprovalPolicy: Option[String] = None,
    toolMaxCallsPerRun: Option[Int] = None,
    toolMaxCallsPerStep: Option[Int] = None,
    retrievalTopK: Option[Int] = None,
    retrievalMinimumScore: Option[Double] = None,
    rerankEnabled: Option[Boolean] = None,
    modelProvider: Option[String] = None,
    modelName: Option[String] = None,
    modelTemperature: Option[Double] = None,
    modelMaxOutputTokens: Option[Int] = None
) derives JsonCodec:
  /** 当前设置了多少项覆盖；管理台用它提示“基线之外有 N 项改动”。 */
  def activeCount: Int = productIterator.count {
    case value: Option[?] => value.isDefined
    case _                => false
  }

object RuntimeOverrides:
  /** 不覆盖任何字段的空补丁。 */
  val none: RuntimeOverrides = RuntimeOverrides()

  /** 审批策略的稳定 wire 取值到领域枚举的 fail-closed 映射。 */
  def parseApprovalPolicy(value: String): Either[String, ApprovalPolicy] = value.trim.toLowerCase match
    case "never"      => Right(ApprovalPolicy.Never)
    case "risk-based" => Right(ApprovalPolicy.RiskBased)
    case "always"     => Right(ApprovalPolicy.Always)
    case other        => Left(s"未知审批策略: $other，允许 never / risk-based / always")

  /** 领域枚举到稳定 wire 取值的反向映射。 */
  def renderApprovalPolicy(policy: ApprovalPolicy): String = policy match
    case ApprovalPolicy.Never     => "never"
    case ApprovalPolicy.RiskBased => "risk-based"
    case ApprovalPolicy.Always    => "always"

  /** 在写入存储之前执行的完整校验。
    *
    * 校验必须发生在持久化之前而不是读取之后：一份非法覆盖一旦落库，进程每次重启都会重新加载它并失败， 把一次输入错误变成持续的启动故障。
    *
    * @return
    *   全部错误消息；空列表表示通过
    */
  def validate(overrides: RuntimeOverrides): Chunk[String] =
    val checks = Chunk(
      overrides.toolAllowedTools.filter(_.exists(_.trim.isEmpty)).map(_ => "工具白名单不能包含空名称"),
      overrides.toolDeniedTools.filter(_.exists(_.trim.isEmpty)).map(_ => "工具黑名单不能包含空名称"),
      overrides.toolDefaultTimeoutMillis
        .filterNot(millis => millis >= 100L && millis <= 600_000L)
        .map(_ => "工具超时必须在 100ms 到 600000ms 之间"),
      overrides.toolMaxResultBytes
        .filterNot(bytes => bytes >= 1024L && bytes <= 16L * 1024L * 1024L)
        .map(_ => "工具结果上限必须在 1KiB 到 16MiB 之间"),
      overrides.toolApprovalPolicy.flatMap(value => parseApprovalPolicy(value).left.toOption),
      overrides.toolMaxCallsPerRun
        .filterNot(value => value >= 1 && value <= 1000)
        .map(_ => "单 Run 工具调用上限必须在 1 到 1000 之间"),
      overrides.toolMaxCallsPerStep
        .filterNot(value => value >= 1 && value <= 100)
        .map(_ => "单步工具调用上限必须在 1 到 100 之间"),
      overrides.retrievalTopK
        .filterNot(value => value >= 1 && value <= 100)
        .map(_ => "检索 topK 必须在 1 到 100 之间"),
      overrides.retrievalMinimumScore
        .filterNot(value => value >= 0.0 && value <= 1.0 && value.isFinite)
        .map(_ => "检索最低得分必须在 0.0 到 1.0 之间"),
      overrides.modelProvider.filter(_.trim.isEmpty).map(_ => "Provider 覆盖不能为空字符串"),
      overrides.modelName.filter(_.trim.isEmpty).map(_ => "模型名覆盖不能为空字符串"),
      overrides.modelTemperature
        .filterNot(value => value >= 0.0 && value <= 2.0 && value.isFinite)
        .map(_ => "模型温度必须在 0.0 到 2.0 之间"),
      overrides.modelMaxOutputTokens
        .filterNot(value => value >= 1 && value <= 1_000_000)
        .map(_ => "模型输出上限必须在 1 到 1000000 之间")
    )
    val consistency =
      for
        allowed <- overrides.toolAllowedTools
        denied  <- overrides.toolDeniedTools
        overlap = allowed.intersect(denied)
        if overlap.nonEmpty
      yield s"工具同时出现在白名单和黑名单: ${overlap.toList.sorted.mkString(",")}"
    checks.flatten ++ Chunk.fromIterable(consistency)

/** 一次覆盖写入的完整审计记录。
  *
  * 记录保存操作者与原因，但不保存操作者的 token、IP 或完整身份负载：管理面审计要能回答“谁在什么时候把工具白名单 改成了什么”，不需要成为第二份认证日志。
  *
  * @param version
  *   单调递增版本；`0` 表示从未写入过覆盖
  * @param overrides
  *   本次写入后的完整覆盖快照，不是增量
  * @param updatedBy
  *   由认证层提供的操作者标识
  * @param reason
  *   低敏变更原因，持久化前截断
  * @param updatedAtEpochMilli
  *   存储权威时钟中的写入时间
  */
final case class RuntimeOverrideRecord(
    version: Long,
    overrides: RuntimeOverrides,
    updatedBy: String,
    reason: String,
    updatedAtEpochMilli: Long
) derives JsonCodec

object RuntimeOverrideRecord:
  /** 变更原因的持久化长度上限。 */
  val MaxReasonLength: Int = 512

  /** 尚未写入任何覆盖时的初始记录。 */
  val initial: RuntimeOverrideRecord =
    RuntimeOverrideRecord(0L, RuntimeOverrides.none, "system", "baseline", 0L)

/** 运行时配置覆盖的耐久存储 SPI。
  *
  * 写入使用 compare-and-set：两个管理员同时编辑同一份配置时，后提交的一方必须看到冲突并重新加载，而不是静默覆盖 对方的改动。这与 `RunStore` 的乐观锁是同一个理由。
  */
trait RuntimeOverrideStore:
  /** 读取当前生效的覆盖记录；从未写入时返回 [[RuntimeOverrideRecord.initial]]。 */
  def current: IO[StoreError, RuntimeOverrideRecord]

  /** 使用 compare-and-set 写入新的完整覆盖快照。
    *
    * @param expectedVersion
    *   调用方读取到的版本；不匹配返回 `OptimisticLock`
    * @param overrides
    *   校验通过的完整覆盖快照
    * @param updatedBy
    *   认证层提供的操作者标识
    * @param reason
    *   低敏变更原因
    */
  def put(
      expectedVersion: Long,
      overrides: RuntimeOverrides,
      updatedBy: String,
      reason: String
  ): IO[StoreError, RuntimeOverrideRecord]

  /** 按版本倒序读取变更历史。 */
  def history(limit: Int): IO[StoreError, Chunk[RuntimeOverrideRecord]]

object RuntimeOverrideStore:
  /** 单页历史条数上限。 */
  val MaxHistoryLimit: Int = 100

  /** 单进程测试与本地开发实现；进程退出即丢失，绝不能用于多副本部署。 */
  val inMemory: ULayer[RuntimeOverrideStore] = ZLayer.fromZIO {
    Ref.Synchronized.make(Chunk(RuntimeOverrideRecord.initial)).map { state =>
      new RuntimeOverrideStore:
        def current: IO[StoreError, RuntimeOverrideRecord] = state.get.map(_.last)

        def put(
            expectedVersion: Long,
            overrides: RuntimeOverrides,
            updatedBy: String,
            reason: String
        ): IO[StoreError, RuntimeOverrideRecord] =
          Clock.instant.flatMap { now =>
            state.modifyZIO { records =>
              val latest = records.last
              if latest.version != expectedVersion then
                ZIO.fail(AgentError.OptimisticLock(Version(expectedVersion.max(0L)), Version(latest.version)))
              else
                val next = RuntimeOverrideRecord(
                  latest.version + 1L,
                  overrides,
                  updatedBy,
                  reason.take(RuntimeOverrideRecord.MaxReasonLength),
                  now.toEpochMilli
                )
                ZIO.succeed(next -> (records :+ next))
            }
          }

        def history(limit: Int): IO[StoreError, Chunk[RuntimeOverrideRecord]] =
          state.get.map(_.reverse.take(limit.max(1).min(MaxHistoryLimit)))
    }
  }

/** 部署基线与覆盖层合并之后的有效运行时设置。
  *
  * @param toolPolicy
  *   合并后的工具治理配置；`maxParallelism` 始终取自基线，见 [[RuntimeSettingsService]]
  * @param retrievalTopK
  *   合并后的检索条数
  * @param retrievalMinimumScore
  *   合并后的最低检索得分
  * @param rerankEnabled
  *   合并后的重排开关
  * @param modelPolicy
  *   合并后的模型工作点；全为 None 表示各 Agent 沿用自己的定义
  * @param overrideVersion
  *   产生该结果的覆盖版本
  */
final case class RuntimeSettings(
    toolPolicy: ToolPolicyConfig,
    retrievalTopK: Int,
    retrievalMinimumScore: Double,
    rerankEnabled: Boolean,
    modelPolicy: ModelPolicy,
    overrideVersion: Long
):
  /** 投影成检索路径消费的工作点。
    *
    * 单独存在一个投影而不是让 `RuntimeSettings` 直接持有 `RetrievalPolicy`，是因为管理台字段列表需要逐项对比 基线值与覆盖值，扁平字段比嵌套对象更直接。
    */
  val retrievalPolicy: RetrievalPolicy =
    RetrievalPolicy(topK = retrievalTopK, minimumScore = retrievalMinimumScore, rerankEnabled = rerankEnabled)

/** 单个配置项在管理台中的展示形态。
  *
  * 同时给出基线值、覆盖值与生效值，是为了让运维一眼看出“这个值为什么是现在这样”。只显示生效值会让人无法区分 “部署里就是这么配的”和“有人临时改过”。
  *
  * @param key
  *   稳定字段名，与 [[RuntimeOverrides]] 的字段一一对应
  * @param baselineValue
  *   部署基线渲染值
  * @param overrideValue
  *   覆盖渲染值；None 表示未覆盖
  * @param effectiveValue
  *   实际生效渲染值
  * @param applies
  *   生效边界
  * @param sensitive
  *   是否属于安全敏感项，管理台应额外提示
  */
final case class RuntimeSettingField(
    key: String,
    baselineValue: String,
    overrideValue: Option[String],
    effectiveValue: String,
    applies: RuntimeSettingApplies,
    sensitive: Boolean
) derives JsonCodec

/** 管理台配置页的完整只读快照。 */
final case class RuntimeConfigView(
    overrideVersion: Long,
    overrideUpdatedBy: String,
    overrideReason: String,
    overrideUpdatedAtEpochMilli: Long,
    fields: Chunk[RuntimeSettingField],
    overrides: RuntimeOverrides
) derives JsonCodec

/** 合并部署基线与耐久覆盖层，并向执行路径提供当前有效设置。
  *
  * 服务持有一个进程内缓存，避免每次工具执行都打一次数据库。缓存通过 [[RuntimeSettingsService.refresh]] 显式刷新， 也由后台
  * [[RuntimeSettingsService.pollingRefresh]] 周期刷新，使一个副本上的改动能在有界延迟内传播到其它副本。 这里不使用数据库
  * LISTEN/NOTIFY：管理面配置的传播延迟以秒计完全够用， 而 NOTIFY 会给存储 SPI 增加一个所有 Adapter 都必须实现的推送契约。
  */
trait RuntimeSettingsService:
  /** 读取当前进程缓存的有效设置。 */
  def effective: UIO[RuntimeSettings]

  /** 返回可被 Runtime 在纯表达式中同步读取的工具策略源。
    *
    * Runtime 在规划工具批次和判断审批时需要裸值而不是效果，因此覆盖层通过原子引用发布，而不是让调用方 `unsafeRun` 一个 `UIO`。
    */
  def toolPolicySource: ToolPolicySource

  /** 返回可被检索路径在纯表达式中同步读取的工作点源。
    *
    * 与 [[toolPolicySource]] 同一理由：`DefaultRetriever` 在决定是否重排和如何过滤时读取裸值。 宿主把它装配给
    * `DefaultRetriever.governedLayer` 与 `RagApplication.governed` 之后，管理台对 topK、
    * 最低得分和重排开关的修改才真正生效；只装配其中一个会让另一半配置保存成功却无效果。
    */
  def retrievalPolicySource: RetrievalPolicySource

  /** 返回可被 Runtime 在构造 `ChatRequest` 时同步读取的模型工作点源。
    *
    * 宿主必须把它装配给 `AgentRuntimeLive`，否则管理台对 Provider、模型与采样参数的修改会保存成功却不影响任何 一次模型调用。它同时携带部署价格表，因此接上它也就打开了
    * `estimatedCost` 的估算。
    */
  def modelPolicySource: ModelPolicySource

  /** 从存储重新加载覆盖层并更新缓存。 */
  def refresh: IO[StoreError, RuntimeSettings]

  /** 读取管理台配置页所需的完整快照。 */
  def view: IO[StoreError, RuntimeConfigView]

  /** 校验并写入新的覆盖快照，成功后立即刷新缓存。 */
  def update(
      expectedVersion: Long,
      overrides: RuntimeOverrides,
      updatedBy: String,
      reason: String
  ): IO[AgentError, RuntimeConfigView]

  /** 读取覆盖变更历史。 */
  def history(limit: Int): IO[StoreError, Chunk[RuntimeOverrideRecord]]

object RuntimeSettingsService:
  /** 后台刷新间隔；覆盖改动在多副本部署中的最大传播延迟。 */
  val DefaultRefreshInterval: Duration = 15.seconds

  /** 模型字段未被覆盖时的渲染值；模型的"基线"是各 Agent 自己的定义，不是某个部署级模型名。 */
  val AgentDefinedBaseline: String = "(各 Agent 定义)"

  /** 从基线配置与覆盖存储构造服务，并立即完成一次加载。
    *
    * 首次加载失败会让整个 Layer 失败而不是回退到基线：如果覆盖存储不可达，管理员看到的“已生效配置”就是错的， 静默回退比启动失败更危险。
    *
    * @param baseline
    *   来自 ZIO Config 的部署基线
    * @param baselineRetrievalTopK
    *   检索条数基线，通常取自 `RagApplicationConfig.defaultTopK`
    * @param baselineRetrievalMinimumScore
    *   最低检索得分基线
    * @param baselineRerankEnabled
    *   重排开关基线
    * @param catalog
    *   已注册模型目录，用作模型覆盖的写入校验依据。默认 `ModelCatalog.empty` 会拒绝一切模型覆盖——没有目录就没有 依据判断一个 Provider 名是否可路由，放行的代价是全线
    *   `ProviderNotFound`
    * @param priceBook
    *   部署价格表；默认空表示不估算费用
    */
  def make(
      baseline: ToolPolicyConfig,
      baselineRetrievalTopK: Int = 5,
      baselineRetrievalMinimumScore: Double = 0.0,
      baselineRerankEnabled: Boolean = false,
      catalog: ModelCatalog = ModelCatalog.empty,
      priceBook: ModelPriceBook = ModelPriceBook.empty
  ): ZIO[RuntimeOverrideStore, StoreError, RuntimeSettingsService] =
    for
      store   <- ZIO.service[RuntimeOverrideStore]
      initial <- store.current
      merged = merge(
        baseline,
        baselineRetrievalTopK,
        baselineRetrievalMinimumScore,
        baselineRerankEnabled,
        initial
      )
      cache <- ZIO.succeed(new AtomicReference(merged))
    yield Live(
      store,
      cache,
      baseline,
      baselineRetrievalTopK,
      baselineRetrievalMinimumScore,
      baselineRerankEnabled,
      catalog,
      priceBook
    )

  /** 生产装配入口。 */
  def layer(
      baseline: ToolPolicyConfig,
      baselineRetrievalTopK: Int = 5,
      baselineRetrievalMinimumScore: Double = 0.0,
      baselineRerankEnabled: Boolean = false,
      priceBook: ModelPriceBook = ModelPriceBook.empty
  ): ZLayer[RuntimeOverrideStore & ModelCatalog, StoreError, RuntimeSettingsService] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[ModelCatalog](catalog =>
        make(
          baseline,
          baselineRetrievalTopK,
          baselineRetrievalMinimumScore,
          baselineRerankEnabled,
          catalog,
          priceBook
        )
      )
    )

  /** 在当前 Scope 中启动周期刷新 Fiber。
    *
    * 刷新失败只记录并继续，不终止 Fiber：存储的短暂不可用不应让所有副本停在最后一次成功的配置上并停止重试。
    */
  def pollingRefresh(
      interval: Duration = DefaultRefreshInterval
  ): ZIO[RuntimeSettingsService & Scope, Nothing, Fiber.Runtime[Nothing, Long]] =
    ZIO
      .serviceWithZIO[RuntimeSettingsService](service =>
        service.refresh.unit.catchAll(error => ZIO.logWarning(s"运行时配置覆盖刷新失败: ${error.message}"))
      )
      .repeat(Schedule.spaced(interval))
      .forkScoped

  /** 环境访问器：读取有效设置。 */
  def effective: URIO[RuntimeSettingsService, RuntimeSettings] =
    ZIO.serviceWithZIO[RuntimeSettingsService](_.effective)

  /** 把稀疏覆盖补丁应用到基线之上。
    *
    * `maxParallelism` 刻意不参与合并：它在装配时决定了 `ToolExecutor` 信号量的许可数，是一个不可变资源。 允许它被覆盖会产生一个保存成功却毫无效果的开关。
    */
  private def merge(
      baseline: ToolPolicyConfig,
      baselineTopK: Int,
      baselineMinimumScore: Double,
      baselineRerank: Boolean,
      record: RuntimeOverrideRecord
  ): RuntimeSettings =
    val overrides = record.overrides
    val policy    = baseline.copy(
      allowedTools = overrides.toolAllowedTools.fold(baseline.allowedTools)(_.map(ToolName(_))),
      deniedTools = overrides.toolDeniedTools.fold(baseline.deniedTools)(_.map(ToolName(_))),
      maxCallsPerRun = overrides.toolMaxCallsPerRun.getOrElse(baseline.maxCallsPerRun),
      maxCallsPerStep = overrides.toolMaxCallsPerStep.getOrElse(baseline.maxCallsPerStep),
      defaultTimeout = overrides.toolDefaultTimeoutMillis.fold(baseline.defaultTimeout)(Duration.fromMillis),
      maxResultBytes = overrides.toolMaxResultBytes.getOrElse(baseline.maxResultBytes),
      approvalPolicy = overrides.toolApprovalPolicy
        .flatMap(RuntimeOverrides.parseApprovalPolicy(_).toOption)
        .getOrElse(baseline.approvalPolicy)
    )
    RuntimeSettings(
      toolPolicy = policy,
      // 覆盖值已由 RuntimeOverrides.validate 校验，但基线来自宿主的 ZIO Config，可能超出 RetrievalPolicy
      // 的合法区间。在这里收敛而不是让 require 抛出，是因为一个越界的基线不该让整个进程以
      // IllegalArgumentException 启动失败——管理台随后会如实显示被收敛后的生效值。
      retrievalTopK = overrides.retrievalTopK.getOrElse(baselineTopK).max(1).min(100),
      // NaN 在 max/min 下会原样穿过，而一个 NaN 阈值会让所有比较为假、静默清空全部检索结果。
      retrievalMinimumScore = overrides.retrievalMinimumScore.getOrElse(baselineMinimumScore) match
        case value if value.isFinite => value.max(0.0).min(1.0)
        case _                       => 0.0,
      rerankEnabled = overrides.rerankEnabled.getOrElse(baselineRerank),
      // 模型覆盖没有"部署基线"可言：基线就是每个 Agent 自己的 modelSettings，而那是 Run 级数据而非部署级配置。
      // 因此这里只把稀疏覆盖原样带过去，由 ModelPolicy.applyTo 在调用点叠加到 Agent 定义上。
      modelPolicy = ModelPolicy(
        provider = overrides.modelProvider.map(_.trim),
        model = overrides.modelName.map(_.trim),
        temperature = overrides.modelTemperature.filter(_.isFinite).map(_.max(0.0).min(2.0)),
        maxOutputTokens = overrides.modelMaxOutputTokens.map(_.max(1).min(1_000_000))
      ),
      overrideVersion = record.version
    )

  /** 缓存有效设置并集中执行校验的实现。 */
  final private class Live(
      store: RuntimeOverrideStore,
      cache: AtomicReference[RuntimeSettings],
      baseline: ToolPolicyConfig,
      baselineTopK: Int,
      baselineMinimumScore: Double,
      baselineRerank: Boolean,
      catalog: ModelCatalog,
      priceBook: ModelPriceBook
  ) extends RuntimeSettingsService:
    def effective: UIO[RuntimeSettings] = ZIO.succeed(cache.get())

    val toolPolicySource: ToolPolicySource = new ToolPolicySource:
      def current(): ToolPolicyConfig = cache.get().toolPolicy

    val retrievalPolicySource: RetrievalPolicySource = new RetrievalPolicySource:
      def current(): RetrievalPolicy = cache.get().retrievalPolicy

    val modelPolicySource: ModelPolicySource = new ModelPolicySource:
      def current(): ModelPolicy          = cache.get().modelPolicy
      override def prices: ModelPriceBook = priceBook

    def refresh: IO[StoreError, RuntimeSettings] =
      store.current
        .map(merge(baseline, baselineTopK, baselineMinimumScore, baselineRerank, _))
        .tap(settings => ZIO.succeed(cache.set(settings)))

    def view: IO[StoreError, RuntimeConfigView] = store.current.map(toView)

    def update(
        expectedVersion: Long,
        overrides: RuntimeOverrides,
        updatedBy: String,
        reason: String
    ): IO[AgentError, RuntimeConfigView] =
      for
        // 目录校验是效果式的，因此不能塞进纯函数 RuntimeOverrides.validate。两类校验都必须在写入之前完成：
        // 一份指向未注册 Provider 的覆盖一旦落库，每次重启都会重新加载它，把一次输入错误变成持续的全线调用失败。
        options <- catalog.options
        default <- catalog.defaultProvider
        problems = RuntimeOverrides.validate(overrides) ++
          ModelCatalog.validateOverride(options, default, overrides.modelProvider, overrides.modelName)
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration(s"运行时配置覆盖校验失败: ${problems.mkString("; ")}"))
          .when(problems.nonEmpty)
        record <- store.put(expectedVersion, overrides, updatedBy, reason)
        _      <- ZIO.succeed(
          cache.set(merge(baseline, baselineTopK, baselineMinimumScore, baselineRerank, record))
        )
      yield toView(record)

    def history(limit: Int): IO[StoreError, Chunk[RuntimeOverrideRecord]] = store.history(limit)

    /** 把基线、覆盖与生效值渲染成管理台字段列表。 */
    private def toView(record: RuntimeOverrideRecord): RuntimeConfigView =
      val overrides = record.overrides
      val effective = merge(baseline, baselineTopK, baselineMinimumScore, baselineRerank, record)
      val fields    = Chunk(
        RuntimeSettingField(
          "toolAllowedTools",
          renderTools(baseline.allowedTools.map(_.value)),
          overrides.toolAllowedTools.map(renderTools),
          renderTools(effective.toolPolicy.allowedTools.map(_.value)),
          RuntimeSettingApplies.Immediate,
          sensitive = true
        ),
        RuntimeSettingField(
          "toolDeniedTools",
          renderTools(baseline.deniedTools.map(_.value)),
          overrides.toolDeniedTools.map(renderTools),
          renderTools(effective.toolPolicy.deniedTools.map(_.value)),
          RuntimeSettingApplies.Immediate,
          sensitive = true
        ),
        RuntimeSettingField(
          "toolDefaultTimeoutMillis",
          baseline.defaultTimeout.toMillis.toString,
          overrides.toolDefaultTimeoutMillis.map(_.toString),
          effective.toolPolicy.defaultTimeout.toMillis.toString,
          RuntimeSettingApplies.Immediate,
          sensitive = false
        ),
        RuntimeSettingField(
          "toolMaxResultBytes",
          baseline.maxResultBytes.toString,
          overrides.toolMaxResultBytes.map(_.toString),
          effective.toolPolicy.maxResultBytes.toString,
          RuntimeSettingApplies.Immediate,
          sensitive = false
        ),
        RuntimeSettingField(
          "toolApprovalPolicy",
          RuntimeOverrides.renderApprovalPolicy(baseline.approvalPolicy),
          overrides.toolApprovalPolicy,
          RuntimeOverrides.renderApprovalPolicy(effective.toolPolicy.approvalPolicy),
          RuntimeSettingApplies.NextRun,
          sensitive = true
        ),
        RuntimeSettingField(
          "toolMaxCallsPerRun",
          baseline.maxCallsPerRun.toString,
          overrides.toolMaxCallsPerRun.map(_.toString),
          effective.toolPolicy.maxCallsPerRun.toString,
          RuntimeSettingApplies.NextRun,
          sensitive = false
        ),
        RuntimeSettingField(
          "toolMaxCallsPerStep",
          baseline.maxCallsPerStep.toString,
          overrides.toolMaxCallsPerStep.map(_.toString),
          effective.toolPolicy.maxCallsPerStep.toString,
          RuntimeSettingApplies.NextRun,
          sensitive = false
        ),
        RuntimeSettingField(
          "toolMaxParallelism",
          baseline.maxParallelism.toString,
          None,
          baseline.maxParallelism.toString,
          RuntimeSettingApplies.Restart,
          sensitive = false
        ),
        RuntimeSettingField(
          "retrievalTopK",
          baselineTopK.toString,
          overrides.retrievalTopK.map(_.toString),
          effective.retrievalTopK.toString,
          RuntimeSettingApplies.Immediate,
          sensitive = false
        ),
        RuntimeSettingField(
          "retrievalMinimumScore",
          baselineMinimumScore.toString,
          overrides.retrievalMinimumScore.map(_.toString),
          effective.retrievalMinimumScore.toString,
          RuntimeSettingApplies.Immediate,
          sensitive = false
        ),
        RuntimeSettingField(
          "rerankEnabled",
          baselineRerank.toString,
          overrides.rerankEnabled.map(_.toString),
          effective.rerankEnabled.toString,
          RuntimeSettingApplies.Immediate,
          sensitive = false
        ),
        // 模型四项的基线渲染为「各 Agent 定义」而不是某个具体模型名：部署层面并不存在唯一基线模型，
        // 编一个出来会让运维以为所有 Agent 本来都在用它。
        RuntimeSettingField(
          "modelProvider",
          AgentDefinedBaseline,
          overrides.modelProvider,
          effective.modelPolicy.provider.getOrElse(AgentDefinedBaseline),
          RuntimeSettingApplies.Immediate,
          sensitive = true
        ),
        RuntimeSettingField(
          "modelName",
          AgentDefinedBaseline,
          overrides.modelName,
          effective.modelPolicy.model.getOrElse(AgentDefinedBaseline),
          RuntimeSettingApplies.Immediate,
          sensitive = true
        ),
        RuntimeSettingField(
          "modelTemperature",
          AgentDefinedBaseline,
          overrides.modelTemperature.map(_.toString),
          effective.modelPolicy.temperature.fold(AgentDefinedBaseline)(_.toString),
          RuntimeSettingApplies.Immediate,
          sensitive = false
        ),
        RuntimeSettingField(
          "modelMaxOutputTokens",
          AgentDefinedBaseline,
          overrides.modelMaxOutputTokens.map(_.toString),
          effective.modelPolicy.maxOutputTokens.fold(AgentDefinedBaseline)(_.toString),
          RuntimeSettingApplies.Immediate,
          sensitive = false
        )
      )
      RuntimeConfigView(
        overrideVersion = record.version,
        overrideUpdatedBy = record.updatedBy,
        overrideReason = record.reason,
        overrideUpdatedAtEpochMilli = record.updatedAtEpochMilli,
        fields = fields,
        overrides = overrides
      )

    /** 工具集合的稳定渲染，保证基线与覆盖的比较不受集合迭代顺序影响。 */
    private def renderTools(tools: Set[String]): String =
      if tools.isEmpty then "(空)" else tools.toList.sorted.mkString(",")
