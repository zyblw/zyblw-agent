package com.zyblw.agent.model

import com.zyblw.agent.composition.CanonicalDigest
import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 能力/成本档；任务角色继续由 ModelRole 表达。 */
enum ModelProfile derives JsonCodec:
  case Fast, Standard, Reasoning

/** 由可信宿主声明的整份上下文上界；不是模型或分类器给出的授权。 */
enum DataSensitivity derives JsonCodec:
  case Public, Internal, Confidential, Restricted

final case class ModelRequirement(
    profile: ModelProfile = ModelProfile.Standard,
    sensitivity: DataSensitivity = DataSensitivity.Internal,
    vision: Boolean = false,
    toolCalling: Boolean = false,
    strictToolSchema: Boolean = false
) derives JsonCodec

/** Provider 是宿主注册的 Adapter 身份，必须随 endpoint/协议版本变化而变更。 */
final case class ModelRef(provider: String, model: String) derives JsonCodec:
  require(provider.trim.nonEmpty && model.trim.nonEmpty, "模型引用不能为空")

final case class ModelRouteCandidate(
    ref: ModelRef,
    profiles: Set[ModelProfile] = Set(ModelProfile.Standard),
    maxSensitivity: DataSensitivity = DataSensitivity.Internal
) derives JsonCodec

/** 第一版只按配置顺序选择；没有隐藏评分、探活或自动故障切换。 */
final case class ModelRoutingPolicy(
    version: String,
    candidates: Chunk[ModelRouteCandidate],
    defaultMaxOutputTokens: Int = 1024,
    sensitivityFloor: DataSensitivity = DataSensitivity.Internal
) derives JsonCodec:
  require(version.trim.nonEmpty, "路由版本不能为空")
  require(candidates.nonEmpty && candidates.length <= 64, "路由候选必须为 1..64 个")
  require(candidates.map(_.ref).distinct.length == candidates.length, "路由候选不能重复")
  require(defaultMaxOutputTokens > 0 && defaultMaxOutputTokens <= 1000000, "路由输出上限非法")

  /** 内容摘要也参与恢复检查，防止修改配置却忘记更新 version。 */
  def fingerprint: String =
    val stable = copy(candidates = candidates.map(c => c.copy(profiles = Set.empty)))
    CanonicalDigest.sha256(
      stable.toJson + candidates.map(_.profiles.toList.map(_.toString).sorted.mkString(",")).mkString("\n")
    )

/** 每个候选的低敏过滤证据；顺序就是确定性排序依据。 */
final case class ModelCandidateDecision(ref: ModelRef, rejectionCodes: Chunk[String]) derives JsonCodec

/** 单次实际调用的路由证据；与 ModelCall 同事务保存，不另建事实源。 */
final case class RouteDecision(
    requirement: ModelRequirement,
    selectedModel: ModelRef,
    policyVersion: String,
    policyFingerprint: String,
    candidates: Chunk[ModelCandidateDecision],
    explicitModelPinned: Boolean,
    estimatedInputTokens: Long,
    maxOutputTokens: Int,
    pricingFingerprint: String,
    selectedPrice: Option[ModelPrice],
    estimatedCost: Option[BigDecimal]
) derives JsonCodec:
  /** Inspector 使用的低敏解释码；不含 prompt、密钥、价格合同或健康原文。 */
  def decisionCodes: Chunk[String] =
    Chunk(s"selected:${selectedModel.provider}/${selectedModel.model}") ++
      candidates.flatMap(candidate =>
        candidate.rejectionCodes.map(code => s"skip:${candidate.ref.provider}/${candidate.ref.model}:$code")
      )

object ModelRouter:
  /** 纯选择规则：输入顺序稳定，同分选择第一个，所有拒绝原因可持久化。 */
  def select(
      candidates: Chunk[ModelCandidateDecision],
      limits: RunLimits = RunLimits()
  ): Either[AgentError, ModelRef] =
    candidates.find(_.rejectionCodes.isEmpty).map(_.ref).toRight {
      // 有本可调用、仅被预算挡住的候选时，保持 Runtime 的 BudgetExceeded 终止语义。
      candidates
        .find(c => c.rejectionCodes.nonEmpty && c.rejectionCodes.forall(_.startsWith("budget-"))) match
        case Some(candidate) =>
          val (name, limit) = candidate.rejectionCodes.head match
            case "budget-input"  => "inputTokens"  -> limits.maxInputTokens
            case "budget-output" => "outputTokens" -> limits.maxOutputTokens
            case "budget-total"  => "tokens"       -> limits.maxTotalTokens
            case "budget-cost"   =>
              "estimatedCostMicros" -> limits.maxEstimatedCost
                .map(cost => (cost * BigDecimal(1000000)).setScale(0, BigDecimal.RoundingMode.CEILING).toLong)
                .getOrElse(0L)
            case _ => "modelCalls" -> limits.maxModelCalls.toLong
          AgentError.BudgetExceeded(name, limit)
        case None => AgentError.InvalidConfiguration("没有满足模型路由硬约束的候选")
    }

  /** 使用现有 RoutedChatModel 的注册表，不能嵌套 FallbackChatModel 绕过路由与账本。 */
  def adapter(model: ChatModel, ref: ModelRef): Either[AgentError, ChatModel] = model match
    case routed: RoutedChatModel =>
      routed.providers
        .get(ref.provider)
        .toRight(AgentError.ProviderNotFound(ref.provider))
        .flatMap(adapter(_, ref))
    case _: FallbackChatModel => Left(AgentError.InvalidConfiguration("耐久路由不能嵌套 FallbackChatModel"))
    case leaf if leaf.provider == ref.provider => Right(leaf)
    case _                                     => Left(AgentError.ProviderNotFound(ref.provider))

  /** 请求内容与显式要求共同过滤；能力声明仍以 ModelCapabilities 为权威。 */
  def rejectionCodes(
      candidate: ModelRouteCandidate,
      requirement: ModelRequirement,
      request: ChatRequest,
      capabilities: ModelCapabilities,
      estimatedInputTokens: Long,
      budget: BudgetState,
      price: Option[ModelPrice]
  ): Chunk[String] =
    val output   = request.settings.maxOutputTokens.getOrElse(0)
    val usage    = budget.consumed
    val limits   = budget.limits
    val hasImage = request.messages.exists(_.content.exists {
      case ContentPart.ImageUrl(_, _) | ContentPart.ImageArtifact(_, _, _) => true
      case _                                                               => false
    })
    val estimate = price.map(_.estimate(TokenUsage(estimatedInputTokens, output.toLong)))
    Chunk.fromIterable(
      List(
        Option.when(candidate.maxSensitivity.ordinal < requirement.sensitivity.ordinal)("data-policy"),
        Option.when((requirement.vision || hasImage) && !capabilities.vision)("vision"),
        Option.when((requirement.toolCalling || request.tools.nonEmpty) && !capabilities.toolCalls)(
          "tool-calling"
        ),
        // ToolDefinition.strict 是 Adapter 可按兼容档案省略的 wire 偏好；只有步骤 Requirement 才把严格 Schema 提升为硬约束。
        Option.when(requirement.strictToolSchema && !capabilities.strictToolSchema)("strict-tool-schema"),
        Option.when(
          request.settings.toolChoice.isInstanceOf[ToolChoice.Specific] && !capabilities.specificToolChoice
        )("specific-tool-choice"),
        Option.when(
          request.settings.reasoningEffort.exists(!capabilities.reasoningEfforts.contains(_))
        )("reasoning-effort"),
        Option.when(capabilities.maxInputTokens.exists(_ < estimatedInputTokens))("context-input"),
        Option.when(capabilities.maxOutputTokens.exists(_ < output.toLong))("context-output"),
        Option.when(estimatedInputTokens < 0 || output <= 0)("invalid-token-estimate"),
        Option.when(usage.modelCalls >= limits.maxModelCalls)("budget-model-calls"),
        Option.when(BigInt(usage.inputTokens) + estimatedInputTokens > limits.maxInputTokens)("budget-input"),
        Option.when(BigInt(usage.outputTokens) + output > limits.maxOutputTokens)("budget-output"),
        Option.when(BigInt(usage.totalTokens) + estimatedInputTokens + output > limits.maxTotalTokens)(
          "budget-total"
        ),
        Option.when(limits.maxEstimatedCost.nonEmpty && estimate.isEmpty)("price-unknown"),
        Option.when(
          limits.maxEstimatedCost.exists(limit => estimate.exists(_ + usage.estimatedCost > limit))
        )("budget-cost")
      ).flatten
    )
