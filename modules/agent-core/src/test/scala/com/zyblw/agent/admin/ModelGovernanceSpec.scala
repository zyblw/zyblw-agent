package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.ToolPolicyConfig
import zio.*
import zio.json.*
import zio.test.*

/** 模型治理的契约测试：覆盖的叠加语义、计费口径与写入校验的 fail-closed 行为。 */
object ModelGovernanceSpec extends ZIOSpecDefault:

  private val fakeKey = "sk-do-not-leak-2f4a9c"

  private def option(provider: String, model: String, declared: Boolean = true): ModelOptionView =
    ModelOptionView(
      provider = provider,
      model = model,
      displayName = provider,
      protocol = "test",
      capabilities = ModelCapabilitiesView(
        toolCalls = true,
        parallelToolCalls = false,
        strictToolSchema = false,
        specificToolChoice = false,
        vision = false,
        thinking = false,
        streaming = false,
        usageReporting = true,
        maxInputTokens = None,
        maxOutputTokens = None
      ),
      isDefaultProvider = provider == "primary",
      declaredModel = declared,
      credential = ModelCredentialStatus(present = true, reference = "env:TEST_API_KEY"),
      price = None
    )

  private val catalogOptions = Chunk(
    option("primary", "primary-large"),
    option("primary", "primary-small"),
    option("fallback", "fallback-large")
  )

  /** 目录 stub；`options` 为空时用于验证未装配目录的 fail-closed 行为。 */
  private def catalogOf(registered: Chunk[ModelOptionView], default: String = "primary"): ModelCatalog =
    new ModelCatalog:
      def options: UIO[Chunk[ModelOptionView]] = ZIO.succeed(registered)
      def defaultProvider: UIO[String]         = ZIO.succeed(default)

  private def settingsService(
      catalog: ModelCatalog,
      priceBook: ModelPriceBook = ModelPriceBook.empty
  ): ZIO[Any, StoreError, RuntimeSettingsService] =
    RuntimeSettingsService
      .make(ToolPolicyConfig.secureDefault, catalog = catalog, priceBook = priceBook)
      .provide(RuntimeOverrideStore.inMemory)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("模型治理")(
    suite("ModelPolicy 叠加")(
      test("稀疏覆盖只替换被指定的字段，未指定字段保留 Agent 定义") {
        val defined = ModelSettings(
          provider = Some("primary"),
          model = Some("primary-large"),
          temperature = Some(0.7),
          maxOutputTokens = Some(2048)
        )
        val applied = ModelPolicy(provider = Some("fallback"), temperature = Some(0.1)).applyTo(defined)
        assertTrue(
          applied.provider.contains("fallback"),
          applied.temperature.contains(0.1),
          // 只改 Provider 时模型名必须保留，否则运维只想切 Provider 却顺手把模型抹成了默认值。
          applied.model.contains("primary-large"),
          applied.maxOutputTokens.contains(2048)
        )
      },
      test("空覆盖原样返回，不改动 toolChoice 与 providerOptions") {
        val defined = ModelSettings(
          provider = Some("primary"),
          toolChoice = ToolChoice.Required,
          providerOptions = Map("thinking" -> zio.json.ast.Json.Bool(true))
        )
        assertTrue(ModelPolicy.default.applyTo(defined) == defined, ModelPolicy.default.isEmpty)
      },
      test("toolChoice 不可被部署级覆盖改动") {
        // toolChoice 是 Agent 的行为契约而非部署工作点。ModelPolicy 里根本没有这个字段，
        // 这个测试锁住这一点，防止后来有人"顺手"把它加进覆盖白名单。
        val defined = ModelSettings(toolChoice = ToolChoice.Specific("echo"))
        val applied =
          ModelPolicy(provider = Some("fallback"), model = Some("fallback-large")).applyTo(defined)
        assertTrue(applied.toolChoice == ToolChoice.Specific("echo"))
      },
      test("越界的温度与输出上限在构造时被拒绝") {
        assertTrue(
          scala.util.Try(ModelPolicy(temperature = Some(2.5))).isFailure,
          scala.util.Try(ModelPolicy(temperature = Some(Double.NaN))).isFailure,
          scala.util.Try(ModelPolicy(maxOutputTokens = Some(0))).isFailure,
          scala.util.Try(ModelPolicy(provider = Some("  "))).isFailure
        )
      }
    ),
    suite("价格表")(
      test("缓存命中的输入 token 不被重复计费") {
        // inputTokens 已经包含 cachedInputTokens，两个字段各自乘单价会把缓存部分收两次费。
        val price = ModelPrice(
          inputPerMillionTokens = BigDecimal(10),
          outputPerMillionTokens = BigDecimal(30),
          cachedInputPerMillionTokens = Some(BigDecimal(1))
        )
        val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 0, cachedInputTokens = 900_000)
        // 正确口径：100_000 * 10/1e6 + 900_000 * 1/1e6 = 1.0 + 0.9 = 1.9
        // 重复计费会得到 10 + 0.9 = 10.9
        assertTrue(price.estimate(usage) == BigDecimal("1.9"))
      },
      test("未声明缓存单价时缓存 token 按普通输入价计算") {
        val price = ModelPrice(BigDecimal(10), BigDecimal(30))
        val usage = TokenUsage(inputTokens = 1_000_000, cachedInputTokens = 500_000)
        assertTrue(price.estimate(usage) == BigDecimal(10))
      },
      test("推理输出 token 不被额外计费") {
        // reasoningOutputTokens 是 outputTokens 的子集，为它再乘一次单价就是重复计费。
        val price    = ModelPrice(BigDecimal(0), BigDecimal(30))
        val withoutR = price.estimate(TokenUsage(outputTokens = 1_000_000))
        val withR    = price.estimate(TokenUsage(outputTokens = 1_000_000, reasoningOutputTokens = 800_000))
        assertTrue(withoutR == withR, withR == BigDecimal(30))
      },
      test("价格表未覆盖的模型估算为零，而不是猜一个价") {
        val book = ModelPriceBook.of(("primary", "primary-large", ModelPrice(BigDecimal(10), BigDecimal(30))))
        assertTrue(
          book.estimate("primary", "unknown-model", TokenUsage(1000, 1000)) == BigDecimal(0),
          book.estimate("other", "primary-large", TokenUsage(1000, 1000)) == BigDecimal(0),
          book.price("primary", "primary-large").isDefined
        )
      },
      test("混用多种货币的价格表在构造时被拒绝") {
        // estimatedCost 是单一标量，混币会把不可比的金额直接相加。
        assertTrue(
          scala.util
            .Try(
              ModelPriceBook.of(
                ("a", "m", ModelPrice(BigDecimal(1), BigDecimal(1), currency = "USD")),
                ("b", "m", ModelPrice(BigDecimal(1), BigDecimal(1), currency = "CNY"))
              )
            )
            .isFailure
        )
      }
    ),
    suite("目录校验")(
      test("未装配目录时拒绝任何模型覆盖，但不阻挡采样参数") {
        val rejected = ModelCatalog.validateOverride(Chunk.empty, "", Some("primary"), None)
        val allowed  = ModelCatalog.validateOverride(Chunk.empty, "", None, None)
        assertTrue(rejected.length == 1, rejected.head.contains("未装配模型目录"), allowed.isEmpty)
      },
      test("未注册的 Provider 被拒绝，并列出可用名称") {
        val problems = ModelCatalog.validateOverride(catalogOptions, "primary", Some("nonexistent"), None)
        assertTrue(
          problems.length == 1,
          problems.head.contains("nonexistent"),
          problems.head.contains("fallback"),
          problems.head.contains("primary")
        )
      },
      test("跨 Provider 的模型名组合被拒绝") {
        // 只校验模型名本身会放过"provider A 的模型配到 provider B 上"这种必然 404 的组合。
        val problems =
          ModelCatalog.validateOverride(catalogOptions, "primary", Some("fallback"), Some("primary-large"))
        assertTrue(problems.length == 1, problems.head.contains("primary-large"))
      },
      test("只覆盖模型名时按默认 Provider 校验") {
        val ok  = ModelCatalog.validateOverride(catalogOptions, "primary", None, Some("primary-small"))
        val bad = ModelCatalog.validateOverride(catalogOptions, "primary", None, Some("fallback-large"))
        assertTrue(ok.isEmpty, bad.length == 1)
      },
      test("已注册组合通过校验") {
        assertTrue(
          ModelCatalog
            .validateOverride(catalogOptions, "primary", Some("fallback"), Some("fallback-large"))
            .isEmpty
        )
      }
    ),
    suite("RuntimeSettingsService 模型覆盖")(
      test("写入未注册模型被拒绝，且拒绝发生在落库之前") {
        for
          service <- settingsService(catalogOf(catalogOptions))
          failure <- service
            .update(0L, RuntimeOverrides(modelProvider = Some("ghost")), "ops", "试错")
            .either
          // 校验必须在写入前完成：一份非法覆盖落库后，每次重启都会重新加载它。
          after <- service.view
        yield assertTrue(
          failure.isLeft,
          failure.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration]),
          after.overrideVersion == 0L,
          after.overrides.modelProvider.isEmpty
        )
      },
      test("已注册模型写入成功并立即体现在 modelPolicySource 上") {
        for
          service <- settingsService(catalogOf(catalogOptions))
          before = service.modelPolicySource.current()
          view <- service.update(
            0L,
            RuntimeOverrides(
              modelProvider = Some("fallback"),
              modelName = Some("fallback-large"),
              modelTemperature = Some(0.2)
            ),
            "ops",
            "primary 降级"
          )
          after = service.modelPolicySource.current()
        yield assertTrue(
          before.isEmpty,
          view.overrideVersion == 1L,
          after.provider.contains("fallback"),
          after.model.contains("fallback-large"),
          after.temperature.contains(0.2),
          after.maxOutputTokens.isEmpty
        )
      },
      test("未装配目录的部署无法写入模型覆盖，但仍能写入工具与检索覆盖") {
        for
          service <- settingsService(ModelCatalog.empty)
          model   <- service.update(0L, RuntimeOverrides(modelName = Some("anything")), "ops", "试").either
          tools   <- service.update(0L, RuntimeOverrides(retrievalTopK = Some(9)), "ops", "调优")
        yield assertTrue(model.isLeft, tools.overrides.retrievalTopK.contains(9))
      },
      test("配置视图把模型基线渲染为「各 Agent 定义」而不是编造一个模型名") {
        for
          service <- settingsService(catalogOf(catalogOptions))
          view    <- service.view
          fields = view.fields.map(field => field.key -> field).toMap
        yield assertTrue(
          fields.contains("modelProvider"),
          fields("modelProvider").baselineValue == RuntimeSettingsService.AgentDefinedBaseline,
          fields("modelProvider").effectiveValue == RuntimeSettingsService.AgentDefinedBaseline,
          fields("modelProvider").overrideValue.isEmpty,
          // Provider 与模型属于安全敏感项：它们决定数据发往哪个厂商。
          fields("modelProvider").sensitive,
          fields("modelName").sensitive,
          fields("modelTemperature").applies == RuntimeSettingApplies.Immediate
        )
      },
      test("价格表通过 modelPolicySource 暴露给 Runtime") {
        val book = ModelPriceBook.of(("primary", "primary-large", ModelPrice(BigDecimal(3), BigDecimal(15))))
        for service <- settingsService(catalogOf(catalogOptions), book)
        yield assertTrue(
          service.modelPolicySource.prices.currency.contains("USD"),
          service.modelPolicySource.prices.estimate(
            "primary",
            "primary-large",
            TokenUsage(1_000_000, 1_000_000)
          ) == BigDecimal(18)
        )
      },
      test("越界的温度与输出上限在写入前被纯校验拦下") {
        for
          service <- settingsService(catalogOf(catalogOptions))
          hot     <- service.update(0L, RuntimeOverrides(modelTemperature = Some(3.0)), "ops", "试").either
          zero    <- service.update(0L, RuntimeOverrides(modelMaxOutputTokens = Some(0)), "ops", "试").either
          blank   <- service.update(0L, RuntimeOverrides(modelName = Some("   ")), "ops", "试").either
        yield assertTrue(hot.isLeft, zero.isLeft, blank.isLeft)
      }
    ),
    suite("凭据不泄漏")(
      test("凭据状态只含引用，序列化后不出现 Key 值") {
        val status = ModelCredentialStatus(present = true, reference = "env:TEST_API_KEY")
        val view   = ModelCatalogView(
          options = Chunk(option("primary", "primary-large").copy(credential = status)),
          defaultProvider = "primary",
          effectiveProvider = None,
          effectiveModel = None,
          embedding = Some(
            EmbeddingModelView(
              provider = "openai",
              model = "text-embedding-3-small",
              dimension = 1536,
              indexDimension = Some(1536),
              switchable = false,
              immutableReason = EmbeddingModelView.DimensionLockedReason
            )
          ),
          priceCurrency = None,
          pricedOptionCount = 0
        )
        val json = view.toJson
        assertTrue(
          !json.contains(fakeKey),
          json.contains("env:TEST_API_KEY"),
          // Embedding 必须以只读呈现；一个能保存成功却让整个知识库索引失效的开关比没有开关危险得多。
          !view.embedding.exists(_.switchable)
        )
      }
    )
  )
