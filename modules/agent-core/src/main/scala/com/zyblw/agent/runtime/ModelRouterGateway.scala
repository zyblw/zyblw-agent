package com.zyblw.agent.runtime

import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*

/** 把一次模型请求路由到具体候选，并冻结路由决定。
  *
  * 路由只准备"这次调用发给谁"，账本与结算仍由 Runtime 的同一事务控制。候选集合在这里被冻结进 [[RouteDecision]]，因此 恢复时能解释答案来自哪个模型，而不是每次恢复重新选一个。
  */
final private[agent] class ModelRouterGateway(
    model: ChatModel,
    profile: RuntimeProfile
):
  import ModelRouterGateway.Routing

  /** 选择本次调用的适配器与请求形状。
    *
    * 未启用路由时保持直连语义：声明了 `ModelRequirement` 却没有开启路由是配置错误，必须显式失败而不是忽略要求。
    */
  def route(
      state: AgentState,
      request: ChatRequest,
      estimatedInputTokens: Long,
      prices: ModelPriceBook
  ): IO[AgentError, Routing] =
    profile.modelRouting match
      case None =>
        ZIO
          .fail(AgentError.InvalidConfiguration("ModelRequirement 需要显式启用 modelRouting"))
          .when(request.settings.requirement.nonEmpty)
          .as(Routing(request, model, None))
      case Some(policy) => routeWithin(policy, state, request, estimatedInputTokens, prices)

  private def routeWithin(
      policy: ModelRoutingPolicy,
      state: AgentState,
      request: ChatRequest,
      estimatedInputTokens: Long,
      prices: ModelPriceBook
  ): IO[AgentError, Routing] =
    val declared = request.settings.requirement.getOrElse(ModelRequirement())
    // 部署可以抬高敏感度下限，但调用方不能把它降下来。
    val requirement = declared.copy(sensitivity =
      if declared.sensitivity.ordinal >= policy.sensitivityFloor.ordinal then declared.sensitivity
      else policy.sensitivityFloor
    )
    val explicit   = request.settings.provider.nonEmpty || request.settings.model.nonEmpty
    val candidates =
      if explicit then
        policy.candidates.filter(candidate =>
          request.settings.provider.contains(candidate.ref.provider) &&
            request.settings.model.contains(candidate.ref.model)
        )
      else policy.candidates.filter(_.profiles.contains(requirement.profile))
    val output = request.settings.maxOutputTokens.getOrElse(policy.defaultMaxOutputTokens)
    val agent  = state.definition
    for
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("候选路由第一版要求 FullSnapshot 上下文"))
        .when(agent.contextPolicy.worldStateDelivery != WorldStateDelivery.FullSnapshot)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("Provider 原生选项要求显式 provider/model"))
        .when(!explicit && request.settings.providerOptions.nonEmpty)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("路由要求显式 provider/model 成对出现且在候选目录中注册"))
        .when(explicit && candidates.isEmpty)
      evaluated <- ZIO.foreach(candidates) { candidate =>
        val next = request.copy(settings =
          request.settings.copy(
            provider = Some(candidate.ref.provider),
            model = Some(candidate.ref.model),
            maxOutputTokens = Some(output)
          )
        )
        for
          adapter <- ZIO.fromEither(ModelRouter.adapter(model, candidate.ref))
          caps    <- adapter.capabilities(Some(candidate.ref.model))
          codes = ModelRouter.rejectionCodes(
            candidate,
            requirement,
            next,
            caps,
            estimatedInputTokens,
            state.budget,
            prices.price(candidate.ref.provider, candidate.ref.model)
          )
        yield (ModelCandidateDecision(candidate.ref, codes), adapter, next)
      }
      selected <- ZIO.fromEither(ModelRouter.select(evaluated.map(_._1), state.budget.limits))
      chosen   <- ZIO
        .fromOption(evaluated.find(_._1.ref == selected))
        .orElseFail(AgentError.InvalidConfiguration("路由选中项不在冻结候选中"))
      price = prices.price(selected.provider, selected.model)
    yield Routing(
      chosen._3,
      chosen._2,
      Some(
        RouteDecision(
          requirement,
          selected,
          policy.version,
          policy.fingerprint,
          evaluated.map(_._1),
          explicit,
          estimatedInputTokens,
          output,
          prices.fingerprint,
          price,
          price.map(_.estimate(TokenUsage(estimatedInputTokens, output.toLong)))
        )
      )
    )

private[agent] object ModelRouterGateway:
  /** 一次路由的完整结论：发什么、发给谁、以及冻结的路由决定。
    *
    * `decision` 为 `None` 表示未启用路由的直连调用；此时调用方仍需自行校验 Provider 能力。
    */
  final case class Routing(
      request: ChatRequest,
      adapter: ChatModel,
      decision: Option[RouteDecision]
  ):
    def routed: Boolean = decision.nonEmpty
