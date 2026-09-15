package com.zyblw.agent.runtime

import com.zyblw.agent.composition.*
import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import com.zyblw.agent.tools.*
import zio.*

/** 运行组合的冻结与漂移门禁。
  *
  * 一个 Run 在创建时冻结它依赖的全部能力身份；恢复与每次模型调用前都会与当前进程重新比较。这样部署替换指令、工具、 扩展、执行环境、路由或价格表时，既有 Run 不会静默改变语义，而是 fail-closed。
  *
  * 冻结规则只有这一份实现：`submitStart`、Harness Goal 启动与 Runtime 恢复必须算出同一份指纹，否则创建与恢复会在 第一次比较时就互相拒绝。
  */
final private[agent] class CompositionGuard(
    registry: RegisteredToolRegistry,
    live: LiveComposition,
    roleCatalog: ModelRoleCatalog,
    publisher: RunEventPublisher
):
  /** 在 Run 创建前解析 ModelRole，使 provider/model 进入冻结定义与组合指纹。 */
  def resolveRole(agent: AgentDefinition): IO[AgentError, AgentDefinition] =
    roleCatalog.applyTo(agent.modelSettings).map(settings => agent.copy(modelSettings = settings))

  /** 创建与恢复使用同一套冻结规则，避免两个入口算出两份指纹。 */
  def freeze(agent: AgentDefinition): RuntimeCompositionFingerprint = live.freeze(agent)

  /** 恢复前比较冻结组合与当前进程；同时核验冻结计划要求的工具仍存在于注册表。
    *
    * 没有"缺少指纹所以跳过检查"的分支：组合指纹是状态的必填字段，因此每次恢复都会真正比较。
    */
  def ensureCompatible(state: AgentState): IO[AgentError, Unit] =
    val required = AgentKernel.pendingToolNames(state)
    for
      names <- liveToolNames(required)
      _     <- requireCompatible(
        state.runId,
        RuntimeComposition.compare(state.composition, freeze(state.definition), names, required)
      )
    yield ()

  /** 捕获本次调用的生效模型设置，并阻止连续运行中的管理面覆盖绕过创建时的组合冻结。 */
  def effectiveModelSettings(
      state: AgentState,
      agent: AgentDefinition,
      prices: ModelPriceBook
  ): IO[AgentError, ModelSettings] =
    val effective = live.effectiveModelSettings(agent)
    val current   = live.fingerprint(agent, effective, prices)
    requireCompatible(state.runId, RuntimeComposition.compare(state.composition, current)).as(effective)

  /** 两个非兼容分支都 fail-closed：能力漂移不能被静默接受。
    *
    * 拒绝前先发一条 `CompositionDriftDetected`，让漂移在 metric 与事件流里可见——只靠错误返回值的话，同步调用方的失败会被 计入普通
    * `RunFailed`，漂移这一类别就永远统计不到。
    */
  private def requireCompatible(runId: RunId, drift: CompositionDrift): IO[AgentError, Unit] =
    drift match
      case CompositionDrift.Compatible => ZIO.unit
      case other                       =>
        val changed = other.changedFields
        val reason  = other.explain
        RunClock.millis
          .flatMap(at => publisher.emit(AgentEvent.CompositionDriftDetected(runId, other.code, changed, at)))
          *> ZIO.fail(other match
            case CompositionDrift.RequiresRevalidation(_) =>
              AgentError.CompositionRequiresRevalidation(runId, changed, reason)
            case _ => AgentError.CompositionIncompatible(runId, changed, reason))

  private def liveToolNames(required: Set[String]): UIO[Set[String]] =
    if required.isEmpty then ZIO.succeed(Set.empty)
    else registry.definitions(required.map(ToolName(_))).map(_.map(_.name).toSet)
