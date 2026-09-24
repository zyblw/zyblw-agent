package com.zyblw.agent.model

import com.zyblw.agent.composition.*
import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.test.*

object ModelRoutingSpec extends ZIOSpecDefault:
  private val ref       = ModelRef("provider", "model")
  private val candidate = ModelRouteCandidate(ref)
  private val request   =
    ChatRequest(Chunk(AgentMessage.user("hello")), settings = ModelSettings(maxOutputTokens = Some(10)))
  private val budget = BudgetState(RunLimits(), UsageSummary(), 0)

  def spec = suite("Model routing contract")(
    test("可信宿主固定的 Run 模型不被后来部署覆盖改写") {
      val pinned    = ModelSettings(temperature = Some(0.2)).pinModel("first", "reasoning")
      val policy    = ModelPolicy(provider = Some("second"), model = Some("fast"), temperature = Some(0.7))
      val effective = policy.applyTo(pinned)
      assertTrue(
        effective.provider.contains("first"),
        effective.model.contains("reasoning"),
        effective.temperature.contains(0.7),
        policy.applyTo(ModelSettings()).provider.contains("second")
      )
    },
    test("重试资格与故障切换资格独立，鉴权/安全/协议错误不得换模型") {
      val conflict    = AgentError.ModelHttpFailure("p", 409)
      val unavailable = AgentError.ModelHttpFailure("p", 503)
      assertTrue(
        conflict.retryable,
        !conflict.fallbackable,
        unavailable.retryable,
        unavailable.fallbackable,
        !AgentError.ModelHttpFailure("p", 401).fallbackable,
        !AgentError.InvalidModelResponse("malformed").fallbackable,
        !AgentError.UnsupportedModelCapability("p", "vision", "missing").fallbackable
      )
    },
    test("仅预算阻挡时保持 BudgetExceeded，未知价格仍为配置拒绝") {
      val limited = ModelRouter.select(
        Chunk(ModelCandidateDecision(ref, Chunk("budget-output"))),
        RunLimits(maxOutputTokens = 5)
      )
      val unpriced = ModelRouter.select(Chunk(ModelCandidateDecision(ref, Chunk("price-unknown"))))
      assertTrue(
        limited == Left(AgentError.BudgetExceeded("outputTokens", 5)),
        unpriced.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
      )
    },
    test("路由 Run 价格变更阻止恢复，legacy 不新增价格约束") {
      val policy    = ModelRoutingPolicy("v1", Chunk(candidate))
      val agent     = AgentDefinition(AgentId("priced"), "priced", "answer")
      val oldPrices = ModelPolicySource.static(
        ModelPolicy.default,
        ModelPriceBook.of(("provider", "model", ModelPrice(1, 2)))
      )
      val newPrices = ModelPolicySource.static(
        ModelPolicy.default,
        ModelPriceBook.of(("provider", "model", ModelPrice(2, 3)))
      )
      val profile = RuntimeProfile(modelRouting = Some(policy))
      assertTrue(
        RuntimeComposition
          .compare(
            RuntimeComposition.freeze(profile, agent, oldPrices),
            RuntimeComposition.freeze(profile, agent, newPrices)
          )
          .isInstanceOf[CompositionDrift.Incompatible],
        RuntimeComposition.compare(
          RuntimeComposition.freeze(RuntimeProfile.default, agent, oldPrices),
          RuntimeComposition.freeze(RuntimeProfile.default, agent, newPrices)
        ) == CompositionDrift.Compatible
      )
    },
    test("固定顺序跳过拒绝候选，空集 fail-closed") {
      val second = ModelRef("provider", "second")
      val rows   =
        Chunk(ModelCandidateDecision(ref, Chunk("vision")), ModelCandidateDecision(second, Chunk.empty))
      assertTrue(ModelRouter.select(rows) == Right(second), ModelRouter.select(Chunk.empty).isLeft)
    },
    test("请求本身的图片与工具需求不能通过省略 requirement 绕过") {
      val imageRequest = request.copy(messages =
        Chunk(
          AgentMessage
            .user("image")
            .copy(
              content = Chunk(ContentPart.ImageUrl("https://example.invalid/image.png", None))
            )
        )
      )
      val codes = ModelRouter.rejectionCodes(
        candidate,
        ModelRequirement(),
        imageRequest,
        ModelCapabilities(vision = false),
        10,
        budget,
        None
      )
      assertTrue(codes.contains("vision"))
    },
    test("未知价格不能通过费用硬限，明确零价格可以") {
      val limited = budget.copy(limits = RunLimits(maxEstimatedCost = Some(BigDecimal(1))))
      val unknown = ModelRouter.rejectionCodes(
        candidate,
        ModelRequirement(),
        request,
        ModelCapabilities(),
        10,
        limited,
        None
      )
      val free = ModelRouter.rejectionCodes(
        candidate,
        ModelRequirement(),
        request,
        ModelCapabilities(),
        10,
        limited,
        Some(ModelPrice(0, 0))
      )
      assertTrue(unknown.contains("price-unknown"), free.isEmpty)
    },
    test(
      "ToolDefinition 默认 strict 不等于步骤硬需求，显式 strict Requirement 才过滤候选"
    ) {
      val toolRequest = request.copy(
        tools = Chunk(ToolDefinition("lookup", "lookup", zio.json.ast.Json.Obj()))
      )
      val capabilities = ModelCapabilities(toolCalls = true, strictToolSchema = false)
      val compatible   = ModelRouter.rejectionCodes(
        candidate,
        ModelRequirement(toolCalling = true),
        toolRequest,
        capabilities,
        10,
        budget,
        None
      )
      val strict = ModelRouter.rejectionCodes(
        candidate,
        ModelRequirement(toolCalling = true, strictToolSchema = true),
        toolRequest,
        capabilities,
        10,
        budget,
        None
      )
      assertTrue(!compatible.contains("strict-tool-schema"), strict.contains("strict-tool-schema"))
    },
    test("敏感级和输入输出预算先于候选顺序") {
      val limited =
        budget.copy(limits = RunLimits(maxInputTokens = 5, maxOutputTokens = 5, maxTotalTokens = 8))
      val codes = ModelRouter.rejectionCodes(
        candidate,
        ModelRequirement(sensitivity = DataSensitivity.Restricted),
        request,
        ModelCapabilities(),
        10,
        limited,
        None
      )
      assertTrue(
        codes.contains("data-policy"),
        codes.contains("budget-input"),
        codes.contains("budget-output"),
        codes.contains("budget-total")
      )
    },
    test("配置版本不变但候选顺序变化仍阻止恢复，旧默认指纹保持兼容") {
      val first =
        ModelRoutingPolicy("v1", Chunk(candidate, candidate.copy(ref = ModelRef("provider", "second"))))
      val agent  = AgentDefinition(AgentId("routing"), "routing", "answer")
      val frozen = RuntimeComposition.freeze(
        RuntimeProfile(modelRouting = Some(first)),
        agent,
        ModelPolicySource.default
      )
      val changed = RuntimeComposition.freeze(
        RuntimeProfile(modelRouting = Some(first.copy(candidates = first.candidates.reverse))),
        agent,
        ModelPolicySource.default
      )
      val old = RuntimeComposition.freeze(RuntimeProfile.default, agent, ModelPolicySource.default)
      assertTrue(
        RuntimeComposition.compare(frozen, changed).isInstanceOf[CompositionDrift.Incompatible],
        RuntimeComposition.compare(old, old) == CompositionDrift.Compatible,
        old.modelRoutingFingerprint.isEmpty
      )
    },
    test("Profile 集合顺序不影响指纹，单价内容可重算") {
      val a = ModelRoutingPolicy(
        "v1",
        Chunk(candidate.copy(profiles = Set(ModelProfile.Fast, ModelProfile.Standard)))
      )
      val b = ModelRoutingPolicy(
        "v1",
        Chunk(candidate.copy(profiles = Set(ModelProfile.Standard, ModelProfile.Fast)))
      )
      val prices = ModelPriceBook.of(("p", "m", ModelPrice(1, 2)))
      assertTrue(
        a.fingerprint == b.fingerprint,
        prices.fingerprint == ModelPriceBook(prices.prices).fingerprint,
        ModelPrice(1, 2).toJson.fromJson[ModelPrice] == Right(ModelPrice(1, 2))
      )
    },
    test("旧 ModelSettings JSON 可读取，显式 requirement 进入组合指纹") {
      val legacy = "{}".fromJson[ModelSettings]
      val base   = ModelSettings(provider = Some("first"), model = Some("reasoning"))
      assertTrue(
        legacy == Right(ModelSettings()),
        RuntimeComposition.modelSettingsFingerprint(ModelSettings()) !=
          RuntimeComposition.modelSettingsFingerprint(ModelSettings(requirement = Some(ModelRequirement()))),
        RuntimeComposition.modelSettingsFingerprint(base) !=
          RuntimeComposition.modelSettingsFingerprint(base.pinModel("first", "reasoning"))
      )
    }
  )
